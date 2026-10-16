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
        vocabulary = self.metadata.get("vocabulary")
        if vocabulary is not None:
            self.vocabulary: list[str] = list(vocabulary["names"])
            self.data_version: str | None = vocabulary["data_version"]
            self.content_sha: str | None = vocabulary["content_sha"]
            self.vocabulary_hash: str | None = vocabulary["hash"]
            self.vocabulary_size = len(self.vocabulary)
        else:
            self.vocabulary = []
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
