"""Per entity row adapters: ``unit_age``, the ticks since the seat was first shown each entity.

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
