"""The ``v4`` adapter: a seat's schema version 4 observation, rebuilt from the exported fields.

The registry gives every seat and public field the place its values hold in a seat's v4
observation (its ``v4`` source: a header index per element, or an entity row index per element of
a row). The adapter reads those fields alone, plus ``entity.visible`` and ``entity.count`` to find
the rows: v4's entity rows for seat s are the first ``entity.count[s]`` rows of the exported table
whose ``entity.visible[s]`` is 1, in row order, the k-th of them v4's row k. Seat fields are already
in the seat's frame, so nothing is mirrored here. Every header int no source names is reserved and
0, as is every row past the count, so the rebuild is exact while ``entity.export_overflow`` is 0.

No index of the observation is written here by hand: the header indices, the row columns and the
places of the values all come from the registry at :meth:`V4Adapter.bind`. Only the layout's sizes
(the header, a row, the rows kept) are the schema's; a :class:`~crforge_env.schema.Schema` read from
a vector's metadata may be given to take them from there instead.
"""

from __future__ import annotations

import weakref
from math import prod
from typing import Any

import numpy as np

from ..fields import FieldView, Registry
from .base import Adapter

SCHEMA_VERSION = 4
"""The observation layout this adapter writes."""

HEADER_INTS = 224
"""The ints of a v4 seat observation's header."""

ENTITY_INTS = 34
"""The ints of one v4 entity row."""

MAX_ENTITIES = 200
"""The entity rows a v4 seat observation holds."""

SEAT_INTS = HEADER_INTS + MAX_ENTITIES * ENTITY_INTS
"""The ints of one v4 seat observation."""

VISIBLE = "entity.visible"
COUNT = "entity.count"

_WRITERS: dict[int, weakref.ReferenceType] = {}
"""Per out array's memory (the address of its first int), the adapter whose observe_both wrote it
last: an adapter given an array back writes only what changed only if no other adapter wrote that
memory since, through the same array or another view of it."""


class V4Adapter(Adapter):
    """Rebuilds each seat's v4 observation, bit for bit, from the exported fields.

    ``schema`` is optional: a :class:`~crforge_env.schema.Schema` (or the metadata's ``schema``
    node) whose sizes replace the module's constants; it must be version 4.
    """

    name = "v4"
    registry_version = "1.0"

    def __init__(self, schema: Any = None):
        sizes = _sizes(schema)
        self.header_ints, self.entity_ints, self.max_entities = sizes
        self.seat_ints = self.header_ints + self.max_entities * self.entity_ints
        self.outputs = {"observation": ((self.seat_ints,), np.dtype(np.int32))}
        self.reads: tuple[str, ...] = ()
        # The out array observe_both last wrote, and the entity rows it filled per (environment,
        # seat) pair: given the same array again, only the header, those rows and the rows listed
        # now are written, not the whole array.
        self._out: np.ndarray | None = None
        self._filled: np.ndarray | None = None

    def bind(self, registry: Registry) -> None:
        """Reads the registry's v4 sources into the gather and scatter plans, then binds."""
        reads = [name for name, f in registry.fields.items() if f.v4 is not None]
        for name in (VISIBLE, COUNT):
            if name not in reads:
                reads.append(name)
        self.reads = tuple(reads)
        super().bind(registry)
        capacity = registry.entity_capacity
        header_src: list[tuple[str, bool, int]] = []
        header_dst: list[int] = []
        entity_src: list[tuple[str, Any]] = []
        entity_dst: list[int] = []
        for name in self.reads:
            f = registry.field(name)
            if f.v4 is None:
                continue
            if f.tag not in ("seat", "public") or f.alias_of is not None:
                raise ValueError(f"{name}: a v4 source on a {f.tag} field")
            seat = f.tag == "seat"
            indices = [int(i) for i in f.v4["indices"]]
            inner = f.shape[1:] if seat else f.shape
            if f.v4["region"] == "header":
                if len(indices) != prod(inner) or not all(
                    0 <= i < self.header_ints for i in indices
                ):
                    raise ValueError(f"{name}: header source {indices} for shape {f.shape}")
                header_src.append((name, seat, len(indices)))
                header_dst.extend(indices)
            elif f.v4["region"] == "entity":
                if not seat or inner[:1] != (capacity,):
                    raise ValueError(f"{name}: an entity source on shape {f.shape}")
                if len(indices) != prod(inner[1:]) or not all(
                    0 <= i < self.entity_ints for i in indices
                ):
                    raise ValueError(f"{name}: entity source {indices} for shape {f.shape}")
                entity_src.append((name, indices[0] if len(indices) == 1 else np.asarray(indices)))
                entity_dst.extend(indices)
            else:
                raise ValueError(f"{name}: unknown v4 region {f.v4['region']!r}")
        for region, dst in (("header", header_dst), ("entity", entity_dst)):
            if len(set(dst)) != len(dst):
                raise ValueError(f"two fields name one v4 {region} index")
        for name, shape in ((VISIBLE, (2, capacity)), (COUNT, (2,))):
            if registry.field(name).shape != shape:
                raise ValueError(f"{name}: shape {registry.field(name).shape}, {shape} expected")
        self.capacity = capacity
        self._header_src = tuple(header_src)
        self._header_dst = np.asarray(header_dst, dtype=np.intp)
        self._entity_src = tuple(entity_src)
        self._row = np.dtype((np.void, 4 * self.entity_ints))
        self._forget()

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``{"observation": (count, SEAT_INTS) int32}``, seat ``seat``'s v4 observation."""
        if seat not in (0, 1):
            raise ValueError(f"seat is 0 or 1, not {seat}")
        count = fields[VISIBLE].shape[0]
        out = np.zeros((count, 1, self.seat_ints), dtype=np.int32)
        self._rebuild(fields, slice(seat, seat + 1), out, None)
        return {"observation": out[:, 0]}

    def observe_both(self, fields: FieldView, out: np.ndarray | None = None) -> np.ndarray:
        """Both seats' v4 observations, ``(count, 2, SEAT_INTS)`` int32, equal to the vector's
        ``observations``; written into ``out`` (C-contiguous) when it is given.

        An ``out`` given is cleared first, unless it is the array this adapter's previous call
        wrote and no other v4 adapter has written its memory since: then only what may have
        changed is written (the header, the rows that call filled and the rows listed now), since
        the rest is still 0. Passing the same array every step is therefore the fast way, and
        nothing else may write it between the calls: an array changed in between (scaled in
        place, say) must be given as a new array, which is cleared.
        """
        shape = (fields[VISIBLE].shape[0], 2, self.seat_ints)
        filled = None
        if out is None:
            out = np.zeros(shape, dtype=np.int32)
        elif out.shape != shape or out.dtype != np.int32 or not out.flags.c_contiguous:
            raise ValueError(f"out is {out.shape} {out.dtype}, {shape} int32 C-contiguous expected")
        elif out is self._out and _last_writer(out) is self:
            filled = self._filled
        else:
            out.fill(0)
        # Forgotten first, so that a call that raises halfway leaves the array to be cleared.
        self._forget()
        listed = self._rebuild(fields, slice(None), out, filled)
        self._out, self._filled = out, listed
        _WRITERS[out.ctypes.data] = weakref.ref(self)
        return out

    def _forget(self) -> None:
        """Drops the array last written, and its entry in the writers if it is still this one."""
        if self._out is not None and _last_writer(self._out) is self:
            del _WRITERS[self._out.ctypes.data]
        self._out, self._filled = None, None

    def _rebuild(
        self, fields: FieldView, seats: slice, out: np.ndarray, filled: np.ndarray | None
    ) -> np.ndarray:
        """Writes the observations of the seats ``seats`` cuts from the seat axis into ``out``,
        ``(count, seats, SEAT_INTS)``, and returns how many entity rows each (environment, seat)
        pair now lists, ``(pairs,)``. ``out`` is all 0 except, when ``filled`` is given, the
        header ints the sources name and each pair's first ``filled`` entity rows."""
        count, pairs = out.shape[0], out.shape[0] * out.shape[1]
        seat_count = out.shape[1]

        # The header: every source field's values side by side, then one scatter.
        parts = []
        for name, seat, n in self._header_src:
            a = fields[name]
            if seat:
                parts.append(a[:, seats].reshape(count, seat_count, n))
            else:
                parts.append(np.broadcast_to(a.reshape(count, 1, n), (count, seat_count, n)))
        out[:, :, self._header_dst] = np.concatenate(parts, axis=2)

        # The rows v4 lists: the shown rows, ranked in row order, up to each seat's count. Each
        # (environment, seat) pair's listed row r goes to its v4 row k. The shown rows come in
        # pair order, so a row's rank is its place less the place of its pair's first.
        pair, row = np.nonzero(fields[VISIBLE][:, seats].reshape(pairs, self.capacity))
        shown = np.bincount(pair, minlength=pairs)
        k = np.arange(pair.size) - (np.cumsum(shown) - shown)[pair]
        limit = fields[COUNT][:, seats].reshape(pairs)
        keep = k < limit[pair]
        pair, row, k = pair[keep], row[keep], k[keep]
        listed = np.bincount(pair, minlength=pairs)

        # The listed rows alone, built column by column (one gather per source field) into a
        # table of whole rows, the columns no source names 0, then put in place in one scatter.
        env, seat = np.divmod(pair, seat_count)
        table = np.zeros((pair.size, self.entity_ints), dtype=np.int32)
        for name, at in self._entity_src:
            table[:, at] = fields[name][:, seats][env, seat, row]
        ints = out.reshape(pairs, self.seat_ints)[:, self.header_ints :]
        ints = ints.reshape(pairs, self.max_entities, self.entity_ints)
        ints.view(self._row)[pair, k, 0] = table.view(self._row)[:, 0]

        # The rows the out array held past the count now: cleared, so every row past it is 0.
        if filled is not None:
            stale = np.maximum(filled - listed, 0)
            if stale.any():
                at = np.repeat(np.arange(pairs), stale)
                first = (np.cumsum(stale) - stale)[at]
                ints[at, np.arange(at.size) - first + listed[at]] = 0
        return listed


def _last_writer(out: np.ndarray) -> V4Adapter | None:
    """The adapter that last wrote ``out``'s memory, None if none still alive did."""
    ref = _WRITERS.get(out.ctypes.data)
    return None if ref is None else ref()


def _sizes(schema: Any) -> tuple[int, int, int]:
    """The header, row and row count sizes: the module's, or a given schema's."""
    if schema is None:
        return HEADER_INTS, ENTITY_INTS, MAX_ENTITIES
    node = schema.metadata if hasattr(schema, "metadata") else schema
    if int(node["version"]) != SCHEMA_VERSION:
        raise ValueError(f"the v4 adapter writes schema version 4, not {node['version']}")
    return int(node["header_ints"]), int(node["entity_ints"]), int(node["max_entities"])
