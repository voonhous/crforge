"""The v4 adapter rebuilds each seat's v4 observation bit for bit from the exported fields.

The blocks are made by the inverse (``tests/fieldgen.py``) from v4 observations: random ones in
v4's shape (no JVM), and real ones of a Ladder rollout and of the synthetic engine. On the fields
the vector exports itself, the adapter is held to its observations directly (here briefly, and at
every decision by the migration gate, ``tests/test_v4_gate.py``).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from crforge_env import VectorBattle, bench
from crforge_env.adapters import v4
from crforge_env.adapters.base import Adapter
from crforge_env.adapters.v4 import V4Adapter
from crforge_env.bench import draw_actions
from crforge_env.fields import Registry
from tests import fieldgen
from tests.decks import BLUE, RED

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"
NODE = json.loads(SNAPSHOT.read_text())
LAYOUT = fieldgen.Layout(NODE, v4.HEADER_INTS, v4.ENTITY_INTS, v4.MAX_ENTITIES)


@pytest.fixture(scope="module")
def registry():
    return Registry.from_file(SNAPSHOT)


@pytest.fixture(scope="module")
def adapter(registry):
    a = V4Adapter()
    a.bind(registry)
    return a


def rebuild(adapter, registry, block):
    view = registry.view(block, adapter.reads)
    both = adapter.observe_both(view)
    seats = [adapter.observe(view, seat)["observation"] for seat in (0, 1)]
    return both, seats


def assert_rebuilt(adapter, registry, observations, block):
    both, seats = rebuild(adapter, registry, block)
    assert both.dtype == np.int32 and both.shape == observations.shape
    np.testing.assert_array_equal(both, observations)
    for seat in (0, 1):
        assert seats[seat].dtype == np.int32
        np.testing.assert_array_equal(seats[seat], observations[:, seat])


def test_it_reads_the_v4_sources_and_the_row_cut(adapter):
    assert isinstance(adapter, Adapter)
    sourced = {f["name"] for f in NODE["fields"] if f["v4"] is not None}
    assert set(adapter.reads) == sourced | {"entity.visible", "entity.count"}
    assert len(adapter.reads) == len(set(adapter.reads))
    assert adapter.hidden_fields == () and not adapter.hidden
    assert adapter.outputs == {"observation": ((v4.SEAT_INTS,), np.dtype(np.int32))}
    assert v4.SEAT_INTS == LAYOUT.seat_ints


@pytest.mark.parametrize("seed", range(24))
def test_random_observations_are_rebuilt_bit_for_bit(adapter, registry, seed):
    rng = np.random.default_rng(seed)
    observations = fieldgen.synthetic_observations(LAYOUT, rng, 6)
    block = fieldgen.fields_from_observations(LAYOUT, observations, rng)
    assert_rebuilt(adapter, registry, observations, block)


@pytest.mark.parametrize(
    ("rows", "overflow"),
    [
        (0, 0),
        (1, 0),
        (v4.MAX_ENTITIES - 1, 0),
        (v4.MAX_ENTITIES, 0),
        (v4.MAX_ENTITIES, 3),
        (v4.MAX_ENTITIES, 500),
    ],
)
def test_the_row_count_the_cap_and_an_overflow_are_rebuilt(adapter, registry, rows, overflow):
    # An overflow at the cap leaves rows the seat is shown past the cut, which v4 does not list.
    rng = np.random.default_rng(rows * 1000 + overflow)
    observations = fieldgen.synthetic_observations(LAYOUT, rng, 5, rows=rows, overflow=overflow)
    block = fieldgen.fields_from_observations(LAYOUT, observations, rng)
    if overflow:
        shown = LAYOUT.view(block, "entity.visible").sum(axis=2)
        assert (shown >= rows).all() and (shown > rows).any()
    assert_rebuilt(adapter, registry, observations, block)


@pytest.mark.parametrize(("rows", "table_rows"), [(1, 1), (40, 52), (v4.MAX_ENTITIES, 210)])
def test_rows_packed_at_the_top_of_the_table_are_rebuilt(adapter, registry, rows, table_rows):
    # As a battle fills it: the entities in the first rows, the rest of the table empty.
    rng = np.random.default_rng(rows + table_rows)
    observations = fieldgen.synthetic_observations(LAYOUT, rng, 5, rows=rows, overflow=4)
    block = fieldgen.fields_from_observations(LAYOUT, observations, rng, table_rows=table_rows)
    assert not LAYOUT.view(block, "entity.visible")[:, :, table_rows:].any()
    assert_rebuilt(adapter, registry, observations, block)


@pytest.mark.parametrize("seed", range(6))
def test_only_the_listed_rows_and_no_hidden_field_are_read(adapter, registry, seed):
    rng = np.random.default_rng(100 + seed)
    observations = fieldgen.synthetic_observations(LAYOUT, rng, 4)
    block = fieldgen.fields_from_observations(LAYOUT, observations, rng, junk_unlisted=True)
    assert_rebuilt(adapter, registry, observations, block)
    # New junk in every hidden field changes nothing.
    for f in NODE["fields"]:
        if f["tag"] == "hidden" and f["alias_of"] is None:
            v = LAYOUT.view(block, f["name"])
            v[...] = rng.integers(-(2**31), 2**31, size=v.shape, dtype=np.int64)
    assert_rebuilt(adapter, registry, observations, block)


def test_an_out_array_is_cleared_and_filled(adapter, registry):
    rng = np.random.default_rng(7)
    observations = fieldgen.synthetic_observations(LAYOUT, rng, 3)
    block = fieldgen.fields_from_observations(LAYOUT, observations, rng)
    out = np.full(observations.shape, -5, dtype=np.int32)
    view = registry.view(block, adapter.reads)
    assert adapter.observe_both(view, out=out) is out
    np.testing.assert_array_equal(out, observations)
    with pytest.raises(ValueError):
        adapter.observe_both(view, out=np.zeros((3, 2, 7), dtype=np.int32))


def test_a_registry_of_another_major_version_is_refused():
    node = dict(NODE, version="2.0")
    with pytest.raises(ValueError):
        V4Adapter().bind(Registry.from_metadata(node))


def test_the_sizes_come_from_a_version_4_schema_alone():
    node = {"version": 4, "header_ints": 224, "entity_ints": 34, "max_entities": 200}
    assert V4Adapter(node).seat_ints == v4.SEAT_INTS
    with pytest.raises(ValueError):
        V4Adapter(dict(node, version=5))


# -- against the env module --------------------------------------------------------------------


def rollout(vector, steps, every, rng):
    """The observations of a random rollout, every ``every`` decisions."""
    for step in range(steps):
        vector.step(draw_actions(vector.masks, rng, 0.5))
        if step % every == 0:
            yield vector.observations.copy()


@pytest.mark.ladder
def test_ladder_observations_are_rebuilt_from_their_fields(install, tables):
    with VectorBattle(4, 1, install=install, tables=tables) as v:
        assert v.metadata["fields"] == NODE
        registry = Registry.from_metadata(v.metadata["fields"])
        adapter = V4Adapter(v.schema)
        adapter.bind(registry)
        assert adapter.seat_ints == v4.SEAT_INTS
        v.reset([11, 12, 13, 14], BLUE, RED)
        rng = np.random.default_rng(3)
        most = 0
        for observations in rollout(v, 600, 15, rng):
            most = max(most, int(observations[:, :, LAYOUT.count_index].max()))
            block = fieldgen.fields_from_observations(LAYOUT, observations, rng)
            assert_rebuilt(adapter, registry, observations, block)
        assert most > 6  # Units and projectiles, not the towers alone.


def test_synthetic_engine_observations_at_the_cap_are_rebuilt(install):
    with VectorBattle(3, 1, install=install, engine="synthetic", synthetic_entities=200) as v:
        registry = Registry.from_metadata(v.metadata["fields"])
        adapter = V4Adapter(v.schema)
        adapter.bind(registry)
        v.reset([1, 2, 3], BLUE, RED)
        rng = np.random.default_rng(5)
        for observations in rollout(v, 20, 4, rng):
            assert (observations[:, :, LAYOUT.count_index] == v4.MAX_ENTITIES).all()
            block = fieldgen.fields_from_observations(LAYOUT, observations, rng)
            assert_rebuilt(adapter, registry, observations, block)


@pytest.mark.ladder
def test_the_exported_fields_rebuild_the_ladder_observations(install, tables):
    # The migration gate (tests/test_v4_gate.py) holds every decision so; this is its short form.
    with VectorBattle(4, 1, install=install, tables=tables, fields=True) as v:
        registry = Registry.from_metadata(v.metadata["fields"])
        adapter = V4Adapter(v.schema)
        adapter.bind(registry)
        overflow = registry.field("entity.export_overflow").offset
        v.reset([21, 22, 23, 24], BLUE, RED)
        rng = np.random.default_rng(9)
        for step in range(600):
            v.step(draw_actions(v.masks, rng, 0.5))
            if step % 5 == 0:
                assert not v.fields[:, overflow].any(), f"step {step}: the export dropped entities"
                both = adapter.observe_both(registry.view(v.fields, adapter.reads))
                np.testing.assert_array_equal(both, v.observations)


@pytest.mark.ladder
def test_the_benchmark_times_the_v4_rebuild(install, tables):
    rows = bench.run("ladder", [2], 1, 10, 1, 0.3, str(install), str(tables), None, adapter="v4")
    assert rows[0].fields and rows[0].adapter == "v4"
    assert 0 < rows[0].adapter_us_per_call < rows[0].us_per_call
    assert "+ fields + v4" in bench.format_row(rows[0])
    with pytest.raises(ValueError, match="adapters"):
        bench.run("ladder", [2], 1, 10, 1, 0.3, str(install), str(tables), None, adapter="v5")
