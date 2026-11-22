"""Field blocks made from v4 seat observations: the inverse of the v4 adapter, for its tests.

``fields_from_observations`` takes a pair of v4 seat observations per environment (a vector's
``observations``, or ``synthetic_observations``) and writes the exported block they stand for,
by the registry's own rules: every seat and public field from the observation through its ``v4``
source; the rows each seat's observation lists scattered, in order, over the 256 rows of the
entity table with ``entity.visible`` 1, between rows it does not see; rows past the cut shown but
not listed when the observation counts an overflow; and every hidden field random junk the adapter
must not read. It works on the registry's metadata node (the snapshot JSON), so it needs no
registry class and no JVM.
"""

from __future__ import annotations

from collections.abc import Mapping
from math import prod
from typing import Any

import numpy as np

VISIBLE = "entity.visible"
COUNT = "entity.count"
OVERFLOW = "entity.overflow"

_I32 = (int(np.iinfo(np.int32).min), int(np.iinfo(np.int32).max) + 1)


class Layout:
    """The registry node's fields by name, with the v4 sizes the observations are laid out in."""

    def __init__(self, node: Mapping[str, Any], header_ints: int, entity_ints: int, rows: int):
        self.node = node
        self.ints = int(node["ints"])
        self.capacity = int(node["entity_capacity"])
        self.header_ints = header_ints
        self.entity_ints = entity_ints
        self.rows = rows
        self.seat_ints = header_ints + rows * entity_ints
        self.fields = {f["name"]: f for f in node["fields"]}
        self.sourced = [f for f in node["fields"] if f["v4"] is not None]
        self.header = [f for f in self.sourced if f["v4"]["region"] == "header"]
        self.entity = [f for f in self.sourced if f["v4"]["region"] == "entity"]
        self.count_index = self.fields[COUNT]["v4"]["indices"][0]
        self.overflow_index = self.fields[OVERFLOW]["v4"]["indices"][0]

    def view(self, block: np.ndarray, name: str) -> np.ndarray:
        f = self.fields[name]
        if f["alias_of"] is not None:
            raise ValueError(f"{name} is an alias")
        size = prod(f["shape"])
        return block[:, f["offset"] : f["offset"] + size].reshape(block.shape[0], *f["shape"])


def synthetic_observations(
    layout: Layout,
    rng: np.random.Generator,
    count: int,
    rows: np.ndarray | None = None,
    overflow: np.ndarray | None = None,
) -> np.ndarray:
    """``(count, 2, seat_ints)`` v4 observations of random values in v4's shape: every header int a
    source names random (a public field the same in both seats), the reserved ints 0, ``rows`` (or
    a random count per seat, often 0 or the cap) rows of random values, the rows past them 0, and
    an overflow counted only at the cap."""
    obs = np.zeros((count, 2, layout.seat_ints), dtype=np.int32)
    for f in layout.header:
        idx = f["v4"]["indices"]
        values = _values(rng, (count, 2, len(idx)))
        if f["tag"] == "public":
            values[:, 1] = values[:, 0]
        obs[:, :, idx] = values
    if rows is None:
        pick = rng.integers(0, 4, size=(count, 2))
        rows = np.where(
            pick == 0,
            0,
            np.where(pick == 1, layout.rows, rng.integers(1, layout.rows, size=(count, 2))),
        )
    rows = np.broadcast_to(np.asarray(rows), (count, 2))
    if overflow is None:
        overflow = rng.integers(1, 120, size=(count, 2)) * (rng.random((count, 2)) < 0.5)
    overflow = np.where(rows == layout.rows, np.broadcast_to(overflow, (count, 2)), 0)
    obs[:, :, layout.count_index] = rows
    obs[:, :, layout.overflow_index] = overflow
    table = obs[:, :, layout.header_ints :].reshape(count, 2, layout.rows, layout.entity_ints)
    columns = sorted({i for f in layout.entity for i in f["v4"]["indices"]})
    written = np.arange(layout.rows)[None, None, :] < rows[:, :, None]
    for c in columns:
        table[:, :, :, c] = np.where(written, _values(rng, written.shape), 0)
    return obs


def fields_from_observations(
    layout: Layout,
    observations: np.ndarray,
    rng: np.random.Generator,
    *,
    junk_unlisted: bool = False,
    table_rows: int | None = None,
) -> np.ndarray:
    """The ``(count, ints)`` int32 block a pair of v4 seat observations per environment stands for.

    Each seat's listed rows go to random rows of the table, in order, between rows it is not
    shown (and rows the other seat alone is shown); an observation that counts an overflow also
    gets rows shown past its cut. Hidden fields are junk. With ``junk_unlisted`` the seat entity
    columns of every row a seat's observation does not list are junk too, not 0 as the export
    writes them: the adapter must cut the rows by ``entity.visible`` and ``entity.count`` alone.
    ``table_rows`` keeps the shown rows within the table's first rows, as a battle of that many
    entities fills it (the whole table by default).
    """
    count = observations.shape[0]
    assert observations.shape == (count, 2, layout.seat_ints), observations.shape
    block = rng.integers(*_I32, size=(count, layout.ints), dtype=np.int64).astype(np.int32)
    for f in layout.header:
        v = layout.view(block, f["name"])
        idx = f["v4"]["indices"]
        if f["tag"] == "seat":
            v[...] = observations[:, :, idx].reshape(v.shape)
        else:
            assert (observations[:, 0, idx] == observations[:, 1, idx]).all(), f["name"]
            v[...] = observations[:, 0, idx].reshape(v.shape)
    visible = layout.view(block, VISIBLE)
    visible[...] = 0
    columns = [(layout.view(block, f["name"]), f["v4"]["indices"]) for f in layout.entity]
    if not junk_unlisted:
        for v, _ in columns:
            v[...] = 0
    table = observations[:, :, layout.header_ints :].reshape(
        count, 2, layout.rows, layout.entity_ints
    )
    capacity = layout.capacity if table_rows is None else table_rows
    for e in range(count):
        for s in range(2):
            n = int(observations[e, s, layout.count_index])
            over = int(observations[e, s, layout.overflow_index])
            assert 0 <= n <= layout.rows and (over == 0 or n == layout.rows), (n, over)
            room = min(over, capacity - n)
            extra = int(rng.integers(0, room + 1)) if room > 0 else 0
            shown = np.sort(rng.choice(capacity, n + extra, replace=False))
            visible[e, s, shown] = 1
            listed = shown[:n]
            for v, idx in columns:
                values = table[e, s, :n][:, idx]
                v[e, s, listed] = values[:, 0] if v.ndim == 3 else values
    return block


def _values(rng: np.random.Generator, shape) -> np.ndarray:
    """Random values as a v4 observation holds them: mostly small codes, ids and amounts, -1 for
    none, and now and then any int32."""
    small = rng.integers(-1, 20_000, size=shape)
    wide = rng.integers(*_I32, size=shape, dtype=np.int64)
    return np.where(rng.random(shape) < 0.1, wide, small).astype(np.int32)
