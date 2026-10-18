"""The vector of battles: one JVM call a decision for every battle, the results read in place.

:class:`VectorBattle` wraps the env module's ``org.crforge.env.VectorEnv``. Its buffers are direct
memory in the machine's byte order; the numpy arrays here are views of that memory, made once, so
a step moves no bytes across the bridge: Python writes the actions into ``actions``, calls
``step()``, and reads the new ``observations``, ``masks``, ``rewards``, ``dones`` and ``infos``
where they already are.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import jpype
import numpy as np

from . import _jvm
from .schema import Codes, Schema

ENGINES = ("ladder", "synthetic")
FAULT_KINDS = ("refusal", "bug", "env_bug")

CARD_TABLES = {
    "spells_characters": "troop",
    "spells_buildings": "building",
    "spells_other": "spell",
}
"""The tables of the cards a deck holds (the env module's ``Vocabulary.DECK_TABLES``) and the type
of card each holds: a card that places characters, one that places a building, and every other
card (spells, and cards that place neither directly)."""


@dataclass(frozen=True)
class SpellFacts:
    """What a spell card cast on the enemy does, as a player reads it off the card: where it hits
    and how hard, at the level the vector plays its cards at (:meth:`VectorBattle.spell_facts`).

    The damage is per target hit, in hit points; 0 where the card deals none of its own a player
    can read off it (a spell that deals its damage through a buff, an action or the characters it
    places), so only the targets it covers count. Distances are game units of the seat's frame.
    """

    radius: int
    """the circle it hits around the point it is cast on, or a rolling spell's half width"""
    damage: int = 0
    """the hit points it takes from each character it hits, over all its waves and hits"""
    tower_damage: int = 0
    """the hit points a crown tower takes from it, 0 when it cannot take a tower"""
    hits_air: bool = True
    hits_ground: bool = True
    roll: int = 0
    """a rolling spell's length: it hits from the point it is cast on forwards (towards the enemy)
    over this distance; 0 for a spell that hits a circle"""
    max_targets: int = 0
    """the most characters it hits, 0 for no limit"""


@dataclass(frozen=True)
class UnitFacts:
    """What a troop or building card places, as a player reads it off the card
    (:meth:`VectorBattle.unit_facts`): the unit it places first, its reach and what it attacks.
    Distances are game units."""

    unit: int
    """the vocabulary id of the unit's row, as an entity row of the observation names it"""
    range: int
    """how far it attacks from (the row's ``Range``), 0 for a unit that does not attack"""
    buildings_only: bool = False
    """it attacks buildings only (the row's ``TargetOnlyBuildings``): towers and buildings"""
    building: bool = False
    """the unit is a building (the row's ``IsBuilding``)"""
    hitpoints: int = 0
    """one unit's hit points at the vector's card level"""


@dataclass(frozen=True)
class LadderConfig:
    """How Ladder battles are played (``org.crforge.env.EnvConfig``); a field left None takes the
    env module's default."""

    decision_ticks: int | None = None
    """ticks one decision advances the battle (5 by default, 1 decides every tick)"""
    level: int | None = None
    """the level every card is played at"""
    tower_level: int | None = None
    """the level of every tower"""
    max_ticks: int | None = None
    """the tick an episode is cut short at with no result, 0 for none"""
    fault_bundles: str | Path | None = None
    """the folder a faulted episode's bundle (its replay and ``fault.json``) is written under, the
    first of each fault signature the vector meets; None writes none. Bundles are local files of
    the run and are never committed"""
    mask_version: int | None = None
    """the rule the mask's play tiles follow: 2 (``MASK_LANDING_TILES``, the default) opens only the
    tiles a card lands on without being moved, 1 (``MASK_ALL_TILES``) every tile of an open hand
    slot. Not part of the schema: a model trained under one rule reads the same observations and
    actions under the other"""


@dataclass(frozen=True)
class Fault:
    """Why an environment's episode ended with no result (``org.crforge.env.EnvFault``)."""

    kind: str
    """``refusal`` (the battle core refused a behaviour it does not model), ``bug`` (any other
    exception of the battle core) or ``env_bug`` (the environment's own)"""
    exception: str
    message: str
    signature: str
    """the kind, the exception's class, the message with its numbers as ``#``, the throwing frame"""
    tick: int
    bundle: str | None = None
    """the folder its bundle was written to, or None: bundles off, an earlier bundle of the same
    signature, or the writing failed"""


def _view(buffer, dtype) -> np.ndarray:
    """A numpy view of a Java direct buffer, sharing its memory."""
    array = np.frombuffer(memoryview(buffer), dtype=dtype)
    if array.flags["OWNDATA"]:
        raise RuntimeError("the buffer was copied, not viewed")
    return array


def _load_json(source: str | Path | Mapping[str, Any]) -> dict[str, Any]:
    """A metadata export given as a mapping, a JSON string or the path of a JSON file."""
    if isinstance(source, Mapping):
        return dict(source)
    if isinstance(source, Path) or not str(source).lstrip().startswith("{"):
        return json.loads(Path(source).read_text(encoding="utf-8"))
    return json.loads(str(source))


class _SpellReader:
    """Reads a spell card's :class:`SpellFacts` through the battle core's row readers and level
    scaling (``BattleRecords``, ``ProjectileAmounts``, ``LevelScaling``)."""

    def __init__(self, tables, level: int):
        java = _jvm.java_class
        self.records = java("org.crforge.core.battle.data.BattleRecords")(tables)
        self.level = level
        self.packed = java("org.crforge.core.pathfinding.combat.PackedLevel")
        self.amounts = java("org.crforge.core.battle.projectile.ProjectileAmounts")
        self.scaling = java("org.crforge.core.pathfinding.combat.LevelScaling")
        self.mode = java("org.crforge.core.pathfinding.combat.ScalingMode").CARD_DAMAGE
        self.globals = java("org.crforge.core.pathfinding.combat.ScalingGlobals").standard()
        self.direct_hit = java("org.crforge.core.pathfinding.target.DirectHit")

    def projectile(self, name: str):
        """A projectile row, its damage at the level and a crown tower's share of it."""
        data = self.records.projectile(name)
        at = self.packed.fromLevel(self.level, data.rarity())
        damage = int(self.amounts.damage(self.globals, data, at))
        return data, damage, int(self.amounts.towerDamage(self.globals, data, at))

    def scaled(self, base: int, rarity) -> int:
        at = self.packed.fromLevel(self.level, rarity)
        return int(self.scaling.scale(self.globals, base, at, self.mode, rarity))

    def fact(self, name: str) -> SpellFacts | None:
        """A spell card's facts, or None for a card that places a character (itself or by its
        projectile) or casts nothing."""
        card = self.records.card(name)
        if card.unit() is not None:
            return None
        radius = int(card.radius())
        if card.projectile() is not None:
            waves = max(int(card.projectileWaves()), 1)
            data, damage, tower = self.projectile(str(card.projectile()))
            if data.spawnCharacter() is not None:
                return None  # a spell that places characters (a barrel) is not cast on a group
            roll = 0
            if damage == 0 and data.spawnProjectile() is not None:
                # A projectile that deals nothing itself and rolls on as another (a log).
                data, damage, tower = self.projectile(str(data.spawnProjectile()))
                roll = int(data.projectileRange())
                radius = radius or int(data.projectileRadius())
            return SpellFacts(
                radius=radius or int(data.radius()),
                damage=damage * waves,
                tower_damage=tower * waves,
                hits_air=bool(data.aoeToAir()),
                hits_ground=bool(data.aoeToGround()),
                roll=roll,
            )
        if card.areaEffect() is not None:
            data = self.records.areaEffect(str(card.areaEffect()))
            speed = int(data.hitSpeedMs())
            hits = max(int(data.lifeDurationMs()) // speed if speed > 0 else 1, 1)
            max_targets = 0
            if data.projectile() is not None:
                # An area effect that strikes with a projectile, a target or a few at a time.
                _, damage, tower = self.projectile(str(data.projectile()))
                if int(data.maximumTargets()) > 0:
                    max_targets = int(data.maximumTargets()) * hits
            else:
                typed = data.typedDamage()
                if typed is None:
                    damage = self.scaled(int(data.damage()), data.rarity()) * hits
                    percent = int(data.crownTowerDamagePercent())
                    tower = int(self.direct_hit.crownTowerDamage(percent, damage))
                else:
                    damage = self.scaled(int(typed.amount(False)), data.rarity()) * hits
                    tower = self.scaled(int(typed.amount(True)), data.rarity()) * hits
            return SpellFacts(
                radius=radius or int(data.radius()),
                damage=damage,
                tower_damage=tower,
                max_targets=max_targets,
            )
        return None


class VectorBattle:
    """``count`` environments stepped together on ``threads`` JVM threads.

    ``engine`` is ``"ladder"`` for Ladder battles on a data version's game tables (``tables``, the
    folder the build makes, required), or ``"synthetic"`` for environments that write observations
    of the same shape from no battle, to test and measure the bridge without tables; a synthetic
    environment can be told to fail at a decision of each episode (``synthetic_fault_at``, counted
    from 1, with ``synthetic_fault_kind``), so the fault path can be shown to work.

    ``install`` is the env module's installed distribution (``./gradlew :env:installDist`` makes
    ``env/build/install/env``); the JVM starts with its jars, once per process.

    The arrays are views of the JVM's buffers, valid for the object's life:

    - ``observations``: int32 ``(count, 2, seat_ints)``, each seat in its own frame
    - ``masks``: uint8 ``(count, 2, actions)``, 1 for an action the seat may take
    - ``actions``: int32 ``(count, 2)``, filled before each ``step``
    - ``rewards``: float32 ``(count, 2)``
    - ``dones``: uint8 ``(count,)``
    - ``infos``: int32 ``(count, info_ints)``

    At start the schema is read from the vector's metadata and its version checked; ``metadata``
    holds the export (schema, configuration and, for Ladder battles, the vocabulary by name with
    the data version, content sha and hash), to be kept with every run and checkpoint.
    """

    def __init__(
        self,
        count: int,
        threads: int = 1,
        *,
        install: str | Path,
        engine: str = "ladder",
        tables: str | Path | None = None,
        config: LadderConfig | None = None,
        synthetic_entities: int = 60,
        synthetic_episode_steps: int = 900,
        synthetic_fault_at: int = -1,
        synthetic_fault_kind: str = "bug",
        jvm_args: Sequence[str] = (),
    ):
        if engine not in ENGINES:
            raise ValueError(f"engine is one of {ENGINES}, not {engine!r}")
        _jvm.start(install, jvm_args)
        vector_env = _jvm.java_class("org.crforge.env.VectorEnv")
        self._illegal_argument = _jvm.java_class("java.lang.IllegalArgumentException")
        self._vocabulary = None
        self._tables = None
        if engine == "ladder":
            if tables is None:
                raise ValueError(
                    "a ladder vector needs the game tables folder of a data version (tables=...), "
                    "as the build makes it (docs/game-tables.md)"
                )
            folder = Path(tables).expanduser().resolve()
            if not folder.is_dir():
                raise FileNotFoundError(f"no game tables folder at {folder}")
            java_folder = _jvm.java_class("java.nio.file.Path").of(str(folder))
            game_tables = vector_env.tables(java_folder)
            self._tables = game_tables
            self._vocabulary = vector_env.vocabulary(game_tables)
            self._env = vector_env.ladder(game_tables, self._java_config(config), count, threads)
        else:
            if config is not None:
                raise ValueError("a synthetic vector plays no battle: it takes no LadderConfig")
            if synthetic_fault_kind not in FAULT_KINDS:
                raise ValueError(f"a fault kind is one of {FAULT_KINDS}")
            synthetic = _jvm.java_class("org.crforge.env.SyntheticEnv")
            kind = _jvm.java_class("org.crforge.env.EnvFault$Kind").valueOf(
                synthetic_fault_kind.upper()
            )
            arguments = (synthetic_entities, synthetic_episode_steps, synthetic_fault_at, kind)
            synthetic(*arguments)  # checks the arguments before the factory is handed over
            factory = jpype.JProxy(
                "java.util.function.Supplier", dict(get=lambda: synthetic(*arguments))
            )
            self._env = vector_env(factory, count, threads)
        self.engine = engine
        self.count = count
        self.threads = int(self._env.threads())
        self.metadata: dict[str, Any] = json.loads(str(self._env.metadataJson()))
        self.schema = Schema(self.metadata, Codes.from_java())
        # The mask rule, kept apart from the schema and the configuration: None for a synthetic
        # vector, which plays no battle.
        mask_version = self.metadata.get("mask_version")
        self.mask_version: int | None = None if mask_version is None else int(mask_version)
        # The id rule, kept apart from the schema too: 2, each seat numbers the entities in the
        # order it is first shown them; None for a synthetic vector and for an export written
        # before the rule (rule 1, the battle's own ids).
        id_version = self.metadata.get("id_version")
        self.id_version: int | None = None if id_version is None else int(id_version)
        vocabulary = self.metadata.get("vocabulary")
        if vocabulary is not None:
            self.vocabulary: list[str] = list(vocabulary["names"])
            self.data_version: str | None = vocabulary["data_version"]
            self.content_sha: str | None = vocabulary["content_sha"]
            self.vocabulary_hash: str | None = vocabulary["hash"]
            self.vocabulary_size = len(self.vocabulary)
            # The buffs an entity row's buff slots name, numbered apart from the vocabulary.
            self.buff_names: list[str] = list(vocabulary["buff_names"])
            self.max_elixir: int | None = int(vocabulary["max_elixir"])
        else:
            self.vocabulary = []
            self.buff_names = []
            self.max_elixir = None
            self.data_version = self.content_sha = self.vocabulary_hash = None
            synthetic_size = _jvm.java_class("org.crforge.env.SyntheticEnv").VOCABULARY_SIZE
            self.vocabulary_size = int(synthetic_size)
        self._ids = {name: i for i, name in enumerate(self.vocabulary)}
        s = self.schema
        self.observations = _view(self._env.observations(), np.int32).reshape(count, 2, s.seat_ints)
        self.masks = _view(self._env.masks(), np.uint8).reshape(count, 2, s.actions)
        self.actions = _view(self._env.actions(), np.int32).reshape(count, 2)
        self.rewards = _view(self._env.rewards(), np.float32).reshape(count, 2)
        self.dones = _view(self._env.dones(), np.uint8)
        self.infos = _view(self._env.infos(), np.int32).reshape(count, s.info_ints)
        self._closed = False

    @staticmethod
    def _java_config(config: LadderConfig | None):
        env_config = _jvm.java_class("org.crforge.env.EnvConfig")
        base = env_config.DEFAULT
        config = config or LadderConfig()

        def pick(value: int | None, default) -> int:
            return int(default) if value is None else int(value)

        return env_config(
            pick(config.decision_ticks, base.decisionTicks()),
            pick(config.level, base.level()),
            pick(config.tower_level, base.towerLevel()),
            pick(config.max_ticks, base.maxTicks()),
            None if config.fault_bundles is None else str(Path(config.fault_bundles).resolve()),
            pick(config.mask_version, base.maskVersion()),
        )

    # -- episodes ------------------------------------------------------------------------------

    def reset(
        self,
        seeds: Sequence[int],
        deck0: Sequence[str] | Sequence[Sequence[str]],
        deck1: Sequence[str] | Sequence[Sequence[str]],
        slots0: Sequence[int] | Sequence[Sequence[int]] | None = None,
        slots1: Sequence[int] | Sequence[Sequence[int]] | None = None,
    ) -> None:
        """Starts an episode in every environment.

        ``deck0`` and ``deck1`` are one deck of eight card row names of the tables (``"Knight"``,
        ``"Archer"``, ...) for every environment, or one deck per environment. ``slots0`` and
        ``slots1`` are each deck's slot flags, one per card (``schema.slot_evolution`` for the
        deck's evolution slot, ``schema.slot_hero`` for its hero slot, both or 0), as one list for
        every environment or one per environment; None plays every card plain. The seeds are one
        per environment; an environment reset later by autoreset draws its seed from the sequence
        its seed starts, so a run is reproduced by its seeds and actions alone. A bad deck or slot
        flags refuse the whole call (``ValueError``) and change nothing; a card the battle core
        does not take in a slot ends that environment's episode as a refusal fault.
        """
        if len(seeds) != self.count:
            raise ValueError(f"{self.count} seeds are needed, got {len(seeds)}")
        j_seeds = jpype.JArray(jpype.JInt)([int(x) for x in seeds])
        j_deck0 = jpype.JArray(jpype.JString)(self._flatten(deck0))
        j_deck1 = jpype.JArray(jpype.JString)(self._flatten(deck1))
        j_slots0 = self._flatten_slots(slots0)
        j_slots1 = self._flatten_slots(slots1)
        try:
            self._env.reset(j_seeds, j_deck0, j_deck1, j_slots0, j_slots1)
        except self._illegal_argument as e:
            raise ValueError(str(e.getMessage())) from None

    def _flatten_slots(self, slots):
        """One deck's slot flags for every environment, or one per environment, as the flat Java
        array; None for none."""
        if slots is None:
            return None
        rows = [list(row) for row in slots] if np.ndim(slots) == 2 else [list(slots)] * self.count
        if len(rows) != self.count:
            raise ValueError(
                f"slot flags for one deck or one per environment: {self.count} needed, got "
                f"{len(rows)}"
            )
        flat: list[int] = []
        for row in rows:
            if len(row) != self.schema.deck_size:
                raise ValueError(
                    f"a deck's slot flags are {self.schema.deck_size} values, got {len(row)}"
                )
            flat.extend(int(flag) for flag in row)
        return jpype.JArray(jpype.JInt)(flat)

    def _flatten(self, decks) -> list[str]:
        decks = list(decks)
        if decks and isinstance(decks[0], str):
            decks = [decks] * self.count
        if len(decks) != self.count:
            raise ValueError(f"one deck per environment: {self.count} needed, got {len(decks)}")
        flat: list[str] = []
        for deck in decks:
            deck = list(deck)
            if len(deck) != self.schema.deck_size:
                raise ValueError(f"a deck has {self.schema.deck_size} cards, got {len(deck)}")
            flat.extend(str(card) for card in deck)
        return flat

    def reset_env(self, index: int, seed: int) -> None:
        """Restarts one environment with a seed, keeping its decks."""
        self._env.resetEnv(int(index), int(seed))

    def set_autoreset(self, autoreset: bool) -> None:
        """Whether a finished environment is restarted by the next step (on by default): the step
        that follows a done ignores that environment's actions and reports the new episode's first
        observation with the info's ``reset`` set."""
        self._env.setAutoreset(bool(autoreset))

    def step(self, actions: np.ndarray | None = None) -> None:
        """Steps every environment one decision with the actions in ``actions`` (or the array
        given, copied in first); the results are in the arrays when it returns. A fault in an
        environment ends that environment's episode alone and is never raised here."""
        if actions is not None:
            np.copyto(self.actions, np.asarray(actions, dtype=np.int32).reshape(self.count, 2))
        self._env.step()

    # -- faults --------------------------------------------------------------------------------

    def fault(self, index: int) -> Fault | None:
        """The fault that ended an environment's current episode, or None."""
        fault = self._env.fault(int(index))
        if fault is None:
            return None
        return Fault(
            kind=str(fault.kind().name()).lower(),
            exception=str(fault.exception()),
            message=str(fault.message()),
            signature=str(fault.signature()),
            tick=int(fault.tick()),
            bundle=None if fault.bundle() is None else str(fault.bundle()),
        )

    def fault_counts(self) -> dict[str, int]:
        """How many faults of each signature the vector met since it was made."""
        return {str(k): int(v) for k, v in self._env.faultCounts().items()}

    def fault_kind_counts(self) -> dict[str, int]:
        """How many faults of each kind the vector met since it was made."""
        return {str(k.name()).lower(): int(v) for k, v in self._env.faultKindCounts().items()}

    # -- reading -------------------------------------------------------------------------------

    def card_id(self, name: str) -> int:
        """The vocabulary id of a row name of the tables."""
        if name not in self._ids:
            raise KeyError(f"the tables have no row named {name}")
        return self._ids[name]

    def card_types(self) -> dict[int, str]:
        """The type of every card a deck may hold, by vocabulary id: ``troop`` for a card of the
        tables' character cards, ``building`` for one of the building cards and ``spell`` for one
        of the other cards (:data:`CARD_TABLES`). A hand slot's card is ``hand_card`` of a seat's
        observation, its cost ``hand_cost``. Empty for a synthetic vector, which has no tables."""
        if self._tables is None:
            return {}
        types: dict[int, str] = {}
        for table, card_type in CARD_TABLES.items():
            for row in self._tables.table(table).rows():
                types[self.card_id(str(row.name()))] = card_type
        return types

    def spell_facts(self, level: int | None = None) -> dict[int, SpellFacts]:
        """The :class:`SpellFacts` of every spell card that is cast on the enemy, by vocabulary id,
        at ``level`` (the vector's card level by default). Empty for a synthetic vector.

        Read from the tables through the battle core's own readers: the card row (a spell card
        whose row sets ``OnlyEnemies``), its projectile or area effect row, and their damage at the
        level by the core's level scaling. A spell the battle core does not read (it would refuse
        the play) is left out, as is one whose projectile places characters (a barrel) and one with
        no radius. The damage is the projectile's damage times the card's waves; a projectile that
        rolls on as another projectile (a log) takes the rolling one's damage, width and range; an
        area effect's damage is its own times its hits, or its projectile's damage, with its hits
        as the most targets when it hits one target at a time. Everything else a spell does
        (buffs, actions, the characters an area effect places) is not read: a spell that deals its
        damage that way is listed with no damage."""
        if self._tables is None:
            return {}
        if level is None:
            level = int(self.metadata["config"]["level"])
        reader = _SpellReader(self._tables, level)
        unsupported = _jvm.java_class("java.lang.RuntimeException")
        facts: dict[int, SpellFacts] = {}
        table = next(t for t, kind in CARD_TABLES.items() if kind == "spell")
        for row in self._tables.table(table).rows():
            name = str(row.name())
            if not bool(row.bool("OnlyEnemies")):
                continue
            try:
                fact = reader.fact(name)
            except unsupported:
                continue  # a card the battle core would refuse to play
            if fact is not None and fact.radius > 0:
                facts[self.card_id(name)] = fact
        return facts

    def unit_facts(self, level: int | None = None) -> dict[int, UnitFacts]:
        """The :class:`UnitFacts` of every troop and building card, by vocabulary id, at ``level``
        (the vector's card level by default). Empty for a synthetic vector.

        Read from the tables through the battle core's own card reader and level scaling
        (``BattleRecords``, ``LevelScaling``): the unit a card places first (its summoned
        character, else the first of its list) and that unit's row. A card the battle core does
        not read (it would refuse the play) is left out, as is one that places no unit of its
        own."""
        if self._tables is None:
            return {}
        if level is None:
            level = int(self.metadata["config"]["level"])
        java = _jvm.java_class
        records = java("org.crforge.core.battle.data.BattleRecords")(self._tables)
        packed = java("org.crforge.core.pathfinding.combat.PackedLevel")
        scaling = java("org.crforge.core.pathfinding.combat.LevelScaling")
        mode = java("org.crforge.core.pathfinding.combat.ScalingMode").CARD_HITPOINTS
        scaling_globals = java("org.crforge.core.pathfinding.combat.ScalingGlobals").standard()
        unsupported = java("java.lang.RuntimeException")
        facts: dict[int, UnitFacts] = {}
        for table, kind in CARD_TABLES.items():
            if kind == "spell":
                continue
            for row in self._tables.table(table).rows():
                name = str(row.name())
                try:
                    unit = records.card(name).unit()
                except unsupported:
                    continue  # a card the battle core would refuse to play
                if unit is None or str(unit.name()) not in self._ids:
                    continue
                facts[self.card_id(name)] = UnitFacts(
                    unit=self.card_id(str(unit.name())),
                    range=int(unit.range()),
                    buildings_only=bool(unit.targetOnlyBuildings()),
                    building=bool(unit.building()),
                    hitpoints=int(
                        scaling.scale(
                            scaling_globals,
                            int(unit.hitpoints()),
                            packed.fromLevel(level, unit.rarity()),
                            mode,
                            unit.rarity(),
                        )
                    ),
                )
        return facts

    def info(self, index: int) -> dict[str, int]:
        """An environment's info record by field name."""
        return {name: int(self.infos[index, i]) for name, i in self.schema.info.items()}

    def seat_info(self, index: int, seat: int) -> dict[str, Any]:
        """What one seat is told after a step: its own counts, not the other seat's.

        - ``tick``, ``episode_step``, ``reset``, ``truncated`` (bool), ``first_side`` (the side
          given first this decision), ``not_modelled`` (plays refused as not modelled this
          episode, both seats)
        - ``winner``: the winning side, the draw code, or -1 while running and for an episode cut
          short or faulted
        - ``crowns``, ``crowns_enemy``: the crowns this seat and the other have taken
        - ``illegal``: 1 when this seat's action of the decision was refused as illegal;
          ``illegal_total``: this seat's illegal actions in the episode
        - ``last_refusal``: the name of the refusal of this seat's last action (``NONE`` for a
          wait, a play or an ability command given); ``last_run_refusal``: the code of its play
          refused at its run (0 for none); ``last_ability_run_refusal``: the gate code of its
          ability command refused at its run (0 for none)
        - ``fault``: ``none``, or the kind of the fault that ended the episode, with
          ``fault_message``, ``fault_signature`` and ``fault_bundle`` (the folder of its bundle);
          each empty without a fault, the bundle empty when none was written
        - ``action_mask``: the seat's mask, int8 of the action space's size
        """
        s = self.schema
        info = self.infos[index]
        observation = self.observations[index, seat]
        refusal = int(observation[s.header["last_refusal"][0]])
        fault_code = int(info[s.info["fault"]])
        fault = self.fault(index) if fault_code else None
        return {
            "tick": int(info[s.info["tick"]]),
            "episode_step": int(info[s.info["episode_step"]]),
            "reset": bool(info[s.info["reset"]]),
            "truncated": bool(info[s.info["truncated"]]),
            "winner": int(info[s.info["winner"]]),
            "crowns": int(info[s.info[f"crowns_{seat}"]]),
            "crowns_enemy": int(info[s.info[f"crowns_{1 - seat}"]]),
            "illegal": int(info[s.info[f"illegal_{seat}"]]),
            "illegal_total": int(observation[s.header["illegal"][0]]),
            "first_side": int(info[s.info["first_side"]]),
            "not_modelled": int(info[s.info["not_modelled"]]),
            "last_refusal": s.refusals.get(refusal, str(refusal)),
            "last_run_refusal": int(observation[s.header["last_run_refusal"][0]]),
            "last_ability_run_refusal": int(observation[s.header["last_ability_run_refusal"][0]]),
            "fault": s.fault_names[fault_code],
            "fault_message": fault.message if fault else "",
            "fault_signature": fault.signature if fault else "",
            "fault_bundle": (fault.bundle or "") if fault else "",
            "action_mask": self.masks[index, seat].astype(np.int8),
        }

    # -- metadata ------------------------------------------------------------------------------

    def replay_json(self, i: int) -> str:
        """Battle ``i``'s episode so far as a replay document (JSON text) of the tables' data
        version (``VectorEnv.replayJson``): its seed, decks, levels, towers and every command its
        seats gave, its ``endTick`` the battle's tick now. A Ladder vector only; with autoreset on,
        a finished episode is replaced by the next one at the next step."""
        return str(self._env.replayJson(int(i)))

    def write_metadata(self, path: str | Path) -> None:
        """Writes the metadata export, to be kept with a run or a checkpoint."""
        Path(path).write_text(str(self._env.metadataJson()) + "\n", encoding="utf-8")

    def check_metadata(self, saved: str | Path | Mapping[str, Any]) -> None:
        """Refuses a saved metadata export (a model's, a run's) this vector would read differently.

        The schema must be the same (version, sizes and field names); a saved vocabulary must
        number every name as these tables do (``Vocabulary.checkSame``): an export whose ids moved
        is refused, naming the first moved name, so a model is remapped by name rather than read
        shifted. ``saved`` is a mapping, a JSON string or the path of a JSON file.

        :raises ValueError: naming the first difference
        """
        saved = _load_json(saved)
        theirs = saved.get("schema", {})
        if theirs.get("version") != self.schema.version:
            raise ValueError(
                f"the saved export is of schema version {theirs.get('version')}, this vector "
                f"writes version {self.schema.version}"
            )
        for key, value in self.schema.metadata.items():
            if theirs.get(key) != value:
                raise ValueError(f"the saved export's schema differs in {key}")
        vocabulary = saved.get("vocabulary")
        if self._vocabulary is None:
            if vocabulary is not None:
                raise ValueError("the saved export has a vocabulary; a synthetic vector has none")
            return
        if vocabulary is None:
            raise ValueError("the saved export has no vocabulary to check")
        try:
            self._vocabulary.checkSame(json.dumps(vocabulary))
        except self._illegal_argument as e:
            raise ValueError(str(e.getMessage())) from None

    # -- life ----------------------------------------------------------------------------------

    def close(self) -> None:
        """Stops the vector's threads; the arrays must not be read after."""
        if not self._closed:
            self._env.close()
            self._closed = True

    def __enter__(self) -> VectorBattle:
        return self

    def __exit__(self, *exc) -> None:
        self.close()
