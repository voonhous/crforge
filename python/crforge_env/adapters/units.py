"""Per entity row adapters: ``unit_age`` (registry 1.0), the ticks since the seat was first shown
each entity; ``attack_cooldown`` and ``enemy_hit_points`` (registry 1.1, on the row facts), the ms
until each unit's next hit and the hit points the seat can know.

The entity fields are a table of ``entity_capacity`` rows; ``entity.visible`` marks the rows a
seat is shown and ``entity.id`` gives each such row the seat's id of its entity, stable through the
entity's life and never given to another entity in the episode. An output per row is aligned with
that table, and is -1 on a row the seat is not shown.
"""

from __future__ import annotations

import numpy as np

from ..fields import SEATS, FieldView, Registry
from .base import Adapter, check_seat

TICK = "game.tick"
VISIBLE = "entity.visible"
ID = "entity.id"
NONE = -1
"""The value of a row the seat is not shown, and of a tick not recorded."""


class UnitAge(Adapter):
    """Per entity row, the ticks since the seat was first shown that entity.

    Output ``age``: ``(entity_capacity,)`` int32 per environment, aligned with the entity table:
    ``game.tick`` now less the tick of the first observation that showed the seat the row's entity
    (0 at that observation), -1 on a row the seat is not shown. An entity the seat was shown and no
    longer sees keeps its first tick, as its id stays the same.

    Stateful: per environment and seat it keeps the tick each of the seat's entity ids was first
    shown, from the observations it is given. So the age counts from the first observation that
    showed the entity, less than a decision's ticks after it appeared on the screen. Call
    :meth:`reset` for the environments whose battle ended (``dones``, after reading their last
    observation) or was replaced (``reset_env``, ``fork_from``, a replay's start): their ids are
    numbered afresh. A battle whose clock went back since the last observation is started afresh
    too, as an autoreset does, so a reset left out of a plain autoreset loop does not mix episodes.

    The state is the adapter's own record of the observations it was given, and nothing of it is in
    the battle. So it does not follow forks or look aheads: a fork moved into an environment
    (``fork_from``) or a look ahead's observation (``lookahead``) is a battle whose earlier
    observations the adapter did not see in that environment, and an age read there counts from
    whatever that environment's record holds. Reset an environment a fork is moved into, and read
    a look ahead's ages with an adapter of its own, knowing they count from the fork.
    """

    name = "unit_age"
    registry_version = "1.0"
    reads = (TICK, VISIBLE, ID)

    def __init__(self) -> None:
        self.capacity = 256
        self.outputs = {"age": ((self.capacity,), np.dtype(np.int32))}
        self._first: np.ndarray | None = None
        """``(count, 2, ids)`` int32: the tick each id was first shown, -1 for none yet."""
        self._last: np.ndarray | None = None
        """``(count, 2)`` int64: the tick of the last observation of each seat, -1 for none."""

    def bind(self, registry: Registry) -> None:
        """Takes the entity table's rows from the registry, binds and forgets every state."""
        super().bind(registry)
        self.capacity = registry.entity_capacity
        self.outputs = {"age": ((self.capacity,), np.dtype(np.int32))}
        self._first = self._last = None

    def reset(self, done: np.ndarray, seat: int) -> None:
        """Forgets seat ``seat``'s ids in the environments ``done`` marks, ``(count,)`` bool."""
        check_seat(seat)
        if self._first is None:
            return
        done = np.asarray(done, dtype=bool)
        self._first[done, seat] = NONE
        self._last[done, seat] = NONE

    def _state(self, count: int, top: int) -> tuple[np.ndarray, np.ndarray]:
        """The state of ``count`` environments, holding ids below ``top``."""
        if self._first is None:
            self._first = np.full((count, SEATS, max(1024, top)), NONE, dtype=np.int32)
            self._last = np.full((count, SEATS), NONE, dtype=np.int64)
        elif self._first.shape[0] != count:
            raise ValueError(
                f"{self.name} holds the state of {self._first.shape[0]} environments; the fields "
                f"hold {count} (one adapter per vector, or bind it again)"
            )
        if top > self._first.shape[2]:
            size = max(top, 2 * self._first.shape[2])
            grown = np.full((count, SEATS, size), NONE, dtype=np.int32)
            grown[:, :, : self._first.shape[2]] = self._first
            self._first = grown
        return self._first, self._last

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``{"age": (count, entity_capacity) int32}`` for seat ``seat``; records the ids it is
        shown for the first time."""
        check_seat(seat)
        tick = fields[TICK].astype(np.int64)
        ids = fields[ID][:, seat]
        shown = (fields[VISIBLE][:, seat] != 0) & (ids >= 0)
        env, row = np.nonzero(shown)
        entity = ids[env, row]
        top = int(entity.max()) + 1 if entity.size else 0
        first, last = self._state(ids.shape[0], top)
        back = tick < last[:, seat]
        first[back, seat] = NONE
        last[:, seat] = tick
        at = first[env, seat, entity]
        new = at < 0
        at = np.where(new, tick[env], at)
        first[env[new], seat, entity[new]] = at[new]
        age = np.full(ids.shape, NONE, dtype=np.int32)
        age[env, row] = tick[env] - at
        return {"age": age}


def _code(registry: Registry, enum: str, label: str) -> int:
    """The code (or bit index) of ``label`` in the registry's enum ``enum``."""
    for code, name in registry.enums[enum].items():
        if name == label:
            return code
    raise ValueError(f"the field registry's enum {enum!r} has no {label!r}")


def _unit_kinds(registry: Registry) -> tuple[int, int]:
    """The entity kinds of characters and towers, the kinds with hit points and attacks."""
    return _code(registry, "entity_kind", "character"), _code(registry, "entity_kind", "tower")


def _facts(adapter: Adapter, registry: Registry, names: tuple[str, ...]) -> dict[str, np.ndarray]:
    """The row facts ``names``, each with one value per vocabulary row; ValueError without."""
    missing = [n for n in names if n not in registry.rows or not len(registry.rows[n])]
    if missing:
        raise ValueError(
            f"adapter {adapter.name!r} needs the row facts {', '.join(missing)} with their values: "
            "the registry of a Ladder vector's metadata, which reads the tables"
        )
    return {n: registry.rows[n] for n in names}


def _shown_rows(fields: FieldView, seat: int) -> tuple[np.ndarray, np.ndarray]:
    """The (environment, row) indices of the rows seat ``seat`` is shown, in row order: a
    stateless per row adapter works on these alone, a few of the table's rows."""
    return np.nonzero(fields[VISIBLE][:, seat])


def _is_unit(kind: np.ndarray, units: tuple[int, int]) -> np.ndarray:
    """Whether each kind is a character's or a tower's (``_unit_kinds``)."""
    return (kind == units[0]) | (kind == units[1])


def _scatter(shape: tuple[int, int], at: tuple[np.ndarray, np.ndarray], values, fill, dtype):
    """A ``shape`` array of ``fill`` with ``values`` at the indices ``at``."""
    out = np.full(shape, fill, dtype=dtype)
    out[at] = values
    return out


def _per_row(fact: np.ndarray, row: np.ndarray, known: np.ndarray) -> np.ndarray:
    """A row fact for each entity row: its value at ``row`` where ``known``, else 0."""
    at = np.where(known, row, 0)
    return np.where(known, fact[at], 0).astype(np.int64)


def _ceil(a: np.ndarray, b: np.ndarray | int) -> np.ndarray:
    """ceil(a / b) of integers, b above 0."""
    return -(-a // b)


LOW_32 = (1 << 32) - 1
"""The low 32 bits of a 64-bit key: an id's place in it, the environment's above."""

TICK_MS = 50
"""The battle time of one tick."""


class AttackCooldown(Adapter):
    """Per entity row, the ms until the row's character or tower can fire its next hit.

    Worked out from the seat fields ``entity.attack_ms``, ``entity.load_ms`` and
    ``entity.hit_speed_percent`` with the row facts of the entity's row (``hit_speed_ms`` H,
    ``load_time_ms`` T, ``attack_step_ms`` b, ``attack_start_delay_ms`` D), by the battle's own
    timer rule (``org.crforge.env.Fields.rowFacts()``; docs/environment.md, the row facts): the
    ticks n until the tick that fires the next hit if the unit attacks on every tick from the next
    one on, as 50 * n ms. The row facts are static facts of the data version, read from the
    registry at :meth:`bind`, and no field of the step.

    Outputs, per environment, each ``(entity_capacity,)`` and aligned with the entity table:

    - ``ms``: int32, the ms until the next hit; -1 where no count is made: a row not shown, not a
      character or tower, a trail (``entity.row`` -1), a row that does not attack (H 0) or whose
      steps differ (b or a needed D of -1), a stunned or frozen unit (the step below 1 ms);
    - ``known``: bool, ``ms`` was counted;
    - ``exact``: bool, counted and none of the cases the rule does not cover: a row with the
      ``dash`` or ``special`` trait (it attacks otherwise in that range), a unit still deploying
      (``entity.deploy_ms`` above 0) or underground, a king tower not awake, a unit whose tags
      forbid it to attack (``no_attack``) or that is not alive, a unit attacking with no target or
      a target the seat is not shown alive (``entity.target`` against the shown rows'
      ``entity.id``: such a unit holds its timers), or a match over (``own.result`` not
      running).

    Even where exact, the count holds while the unit keeps attacking a target in range from the
    next tick on, with nothing resetting its timers and its hit speed percent the same. Stateless.
    """

    name = "attack_cooldown"
    registry_version = "1.1"
    reads = (
        VISIBLE,
        "entity.kind",
        "entity.row",
        "entity.state",
        "entity.flags",
        "entity.attack_ms",
        "entity.load_ms",
        "entity.hit_speed_percent",
        "entity.deploy_ms",
        "entity.id",
        "entity.target",
        "own.result",
    )
    FACTS = (
        "hit_speed_ms",
        "load_time_ms",
        "attack_step_ms",
        "attack_start_delay_ms",
        "attack_traits",
    )

    def __init__(self) -> None:
        self.capacity = 256
        self.outputs = self._outputs()

    def _outputs(self) -> dict[str, tuple[tuple[int, ...], np.dtype]]:
        row = (self.capacity,)
        return {
            "ms": (row, np.dtype(np.int32)),
            "known": (row, np.dtype(np.bool_)),
            "exact": (row, np.dtype(np.bool_)),
        }

    def bind(self, registry: Registry) -> None:
        """Binds, then takes the row facts and the codes it reads by from the registry."""
        super().bind(registry)
        self._facts = _facts(self, registry, self.FACTS)
        self.capacity = registry.entity_capacity
        self.outputs = self._outputs()
        self._units = _unit_kinds(registry)
        self._attacking = _code(registry, "state", "attacking")
        self._running = _code(registry, "result", "running")
        labels = ("alive", "charged", "underground", "king", "awake", "no_attack")
        self._bit = {label: 1 << _code(registry, "entity", label) for label in labels}
        dash, special = (1 << _code(registry, "attack_traits", t) for t in ("dash", "special"))
        self._inexact_traits = dash | special

    def _target_alive(
        self, fields: FieldView, seat: int, shown: tuple[np.ndarray, np.ndarray], flags: np.ndarray
    ) -> np.ndarray:
        """Per shown row, whether its target is an entity the seat is shown alive (by the seat's
        ids): its environment and target id among the environments and ids of the shown rows
        alive, as one 64-bit key each."""
        env = shown[0].astype(np.int64) << 32
        ids = fields[ID][:, seat][shown].astype(np.int64) & LOW_32
        target = fields["entity.target"][:, seat][shown]
        living = (flags & self._bit["alive"]) != 0
        aimed = env | (target.astype(np.int64) & LOW_32)
        keys = np.sort((env | ids)[living])
        if keys.size == 0:
            return np.zeros(target.shape, dtype=bool)
        found = keys[np.minimum(np.searchsorted(keys, aimed), keys.size - 1)] == aimed
        return (target >= 0) & found

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``ms``, ``known`` and ``exact``, each ``(count, entity_capacity)``, for seat ``seat``.

        Worked out on the rows the seat is shown alone; every other row is -1 and False."""
        check_seat(seat)
        f = self._facts
        at = _shown_rows(fields, seat)

        def col(name: str) -> np.ndarray:
            return fields[name][:, seat][at]

        row = col("entity.row").astype(np.int64)
        flags = col("entity.flags").astype(np.int64)
        unit = _is_unit(col("entity.kind"), self._units)
        shown = unit & (row >= 0) & (row < len(f["hit_speed_ms"]))
        h = _per_row(f["hit_speed_ms"], row, shown)
        t = _per_row(f["load_time_ms"], row, shown)
        b = _per_row(f["attack_step_ms"], row, shown)
        d = _per_row(f["attack_start_delay_ms"], row, shown)
        traits = _per_row(f["attack_traits"], row, shown)
        a = col("entity.attack_ms").astype(np.int64)
        load = col("entity.load_ms").astype(np.int64)
        p = col("entity.hit_speed_percent").astype(np.int64)
        step = b * p // 100
        attacking = col("entity.state") == self._attacking
        charged = (flags & self._bit["charged"]) != 0
        known = shown & (h > 0) & (b >= 0) & (step >= 1)
        # A start delay of -1 (the steps differ) leaves an attack that starts from 0 uncounted.
        known &= charged | (a > 0) | attacking | (d >= 0)
        s, hs = np.maximum(step, 1), np.maximum(h, 1)
        under_way = _ceil((a // hs + 1) * hs - a, s)
        k = np.where(attacking, 0, _ceil(np.maximum(d, 0), TICK_MS))
        credited = np.maximum(load - TICK_MS * (k + 1), 0)
        short = k + np.maximum(1, _ceil(hs - t + credited, s))
        j = np.maximum(k + 1, _ceil(load - hs, TICK_MS))
        long = j - 1 + _ceil(hs, s)
        # The flag charged stays set on the tick of the charge's hit, the timer at H by then.
        from_zero = np.where(charged, 1, np.where(t <= h, short, long))
        n = np.where(a > 0, under_way, from_zero)
        ms = np.where(known, TICK_MS * n, NONE)
        bit = self._bit
        inexact = (traits & self._inexact_traits) != 0
        inexact |= col("entity.deploy_ms") > 0
        inexact |= (flags & (bit["underground"] | bit["no_attack"])) != 0
        inexact |= ((flags & bit["king"]) != 0) & ((flags & bit["awake"]) == 0)
        inexact |= (flags & bit["alive"]) == 0
        inexact |= attacking & ~self._target_alive(fields, seat, at, flags)
        inexact |= fields["own.result"][:, seat][at[0]] != self._running
        shape = fields[VISIBLE][:, seat].shape
        return {
            "ms": _scatter(shape, at, ms, NONE, np.int32),
            "known": _scatter(shape, at, known, False, np.bool_),
            "exact": _scatter(shape, at, known & ~inexact, False, np.bool_),
        }


class EnemyHitPoints(Adapter):
    """Per entity row, the hit points the seat can know exactly, and the health bar's fill.

    A character's or tower's hit points are known where ``entity.hp`` shows them (0 or more: every
    row of the seat's own, every tower, an enemy unit whose health bar the screen draws), and where
    it is -1 in one more case, by the row fact ``bar_rule``: the rule is ``damaged``, the row is not
    a trail (``entity.row`` not -1) and the flags do not hold ``underground``. Such a unit shows its
    bar from its first damage on, so without one it is undamaged and its hit points are
    ``entity.max_hp`` exactly. They are not known for the rule ``never``, for ``damaged_rolling``
    (the roll hides the bar whatever the damage) or for a unit underground.

    Outputs, per environment, each ``(entity_capacity,)`` and aligned with the entity table:

    - ``hp``: int32, the hit points where known, -1 elsewhere (a row not shown, a projectile or an
      area effect, which have none, or hit points not known);
    - ``known``: bool, ``hp`` is known;
    - ``fill``: float32, ``hp / max_hp`` where known and ``max_hp`` is above 0, -1 elsewhere.

    It reads seat fields only, never the true hit points. Stateless.
    """

    name = "enemy_hit_points"
    registry_version = "1.1"
    reads = (VISIBLE, "entity.kind", "entity.row", "entity.hp", "entity.max_hp", "entity.flags")

    def __init__(self) -> None:
        self.capacity = 256
        self.outputs = self._outputs()

    def _outputs(self) -> dict[str, tuple[tuple[int, ...], np.dtype]]:
        row = (self.capacity,)
        return {
            "hp": (row, np.dtype(np.int32)),
            "known": (row, np.dtype(np.bool_)),
            "fill": (row, np.dtype(np.float32)),
        }

    def bind(self, registry: Registry) -> None:
        """Binds, then takes the bar rule and the codes it reads by from the registry."""
        super().bind(registry)
        self._rule = _facts(self, registry, ("bar_rule",))["bar_rule"]
        self.capacity = registry.entity_capacity
        self.outputs = self._outputs()
        self._units = _unit_kinds(registry)
        self._damaged = _code(registry, "bar_rule", "damaged")
        self._underground = 1 << _code(registry, "entity", "underground")

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``hp``, ``known`` and ``fill``, each ``(count, entity_capacity)``, for seat ``seat``.

        Worked out on the rows the seat is shown alone; every other row is -1 and False."""
        check_seat(seat)
        at = _shown_rows(fields, seat)

        def col(name: str) -> np.ndarray:
            return fields[name][:, seat][at]

        row = col("entity.row").astype(np.int64)
        hp = col("entity.hp")
        top = col("entity.max_hp")
        unit = _is_unit(col("entity.kind"), self._units)
        listed = (row >= 0) & (row < len(self._rule))
        rule = _per_row(self._rule, row, listed)
        underground = (col("entity.flags") & self._underground) != 0
        unhurt = unit & (hp < 0) & listed & (rule == self._damaged) & ~underground & (top > 0)
        known = unit & ((hp >= 0) | unhurt)
        value = np.where(known, np.where(unhurt, top, hp), NONE)
        with np.errstate(divide="ignore", invalid="ignore"):
            fill = np.where(known & (top > 0), value / np.maximum(top, 1), -1.0)
        shape = fields[VISIBLE][:, seat].shape
        return {
            "hp": _scatter(shape, at, value, NONE, np.int32),
            "known": _scatter(shape, at, known, False, np.bool_),
            "fill": _scatter(shape, at, fill.astype(np.float32), -1.0, np.float32),
        }
