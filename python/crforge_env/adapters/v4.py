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
        self._unsourced = np.setdiff1d(np.arange(self.entity_ints), entity_dst).astype(np.intp)

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        """``{"observation": (count, SEAT_INTS) int32}``, seat ``seat``'s v4 observation."""
        if seat not in (0, 1):
            raise ValueError(f"seat is 0 or 1, not {seat}")
        count = fields[VISIBLE].shape[0]
        out = np.zeros((count, 1, self.seat_ints), dtype=np.int32)
        return {"observation": self._rebuild(fields, slice(seat, seat + 1), out)[:, 0]}

    def observe_both(self, fields: FieldView, out: np.ndarray | None = None) -> np.ndarray:
        """Both seats' v4 observations, ``(count, 2, SEAT_INTS)`` int32, equal to the vector's
        ``observations``; written into ``out`` (cleared first, C-contiguous) when it is given."""
        shape = (fields[VISIBLE].shape[0], 2, self.seat_ints)
        if out is None:
            out = np.zeros(shape, dtype=np.int32)
        elif out.shape != shape or out.dtype != np.int32 or not out.flags.c_contiguous:
            raise ValueError(f"out is {out.shape} {out.dtype}, {shape} int32 C-contiguous expected")
        else:
            out.fill(0)
        return self._rebuild(fields, slice(None), out)

    def _rebuild(self, fields: FieldView, seats: slice, out: np.ndarray) -> np.ndarray:
        """Writes the observations of the seats ``seats`` cuts from the seat axis into ``out``,
        ``(count, seats, SEAT_INTS)`` and all 0, and returns it."""
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
        # (environment, seat) pair's listed row r goes to its v4 row k.
        shown = (fields[VISIBLE][:, seats] != 0).reshape(pairs, self.capacity)
        rank = np.cumsum(shown, axis=1, dtype=np.int32)
        listed = shown & (rank <= fields[COUNT][:, seats].reshape(pairs, 1))
        pair, row = np.nonzero(listed)
        if row.size == 0:
            return out
        k = rank[pair, row] - 1

        # The table's columns up to the last row listed, stacked column by column (contiguous
        # copies) and turned into rows once, with one row of zeros after them. Each v4 row then
        # takes its table row, or the zeros past the count, in one gather of whole rows.
        span = int(row.max()) + 1
        columns = np.empty((self.entity_ints, pairs * span + 1), dtype=np.int32)
        columns[self._unsourced] = 0
        columns[:, -1] = 0
        stack = columns[:, :-1].reshape(self.entity_ints, count, seat_count, span)
        for name, at in self._entity_src:
            a = fields[name][:, seats, :span]
            stack[at] = a if a.ndim == 3 else np.moveaxis(a, 3, 0)
        table = np.ascontiguousarray(columns.T).view(self._row)[:, 0]
        source = np.full((pairs, self.max_entities), pairs * span, dtype=np.intp)
        source[pair, k] = pair * span + row
        rows = out.reshape(pairs, self.seat_ints)[:, self.header_ints :]
        rows = rows.reshape(pairs, self.max_entities, self.entity_ints).view(self._row)[..., 0]
        rows[...] = table[source]
        return out


def _sizes(schema: Any) -> tuple[int, int, int]:
    """The header, row and row count sizes: the module's, or a given schema's."""
    if schema is None:
        return HEADER_INTS, ENTITY_INTS, MAX_ENTITIES
    node = schema.metadata if hasattr(schema, "metadata") else schema
    if int(node["version"]) != SCHEMA_VERSION:
        raise ValueError(f"the v4 adapter writes schema version 4, not {node['version']}")
    return int(node["header_ints"]), int(node["entity_ints"]), int(node["max_entities"])
