"""The field export through the bridge (--tables): ``VectorBattle(fields=True)`` and its view.

The block's seat and public fields are held to ``observations`` through the committed snapshot's
v4 sources, with plain numpy: the field at seat s (and row r) is the v4 value of seat s at the
source's index (of the v4 row r stands as). The Java ``FieldExportTest`` holds every field so; this
holds the view, its shape and a few fields.
"""

import json
from math import prod
from pathlib import Path

import numpy as np
import pytest

from crforge_env import VectorBattle
from crforge_env.bench import draw_actions
from tests.decks import BLUE, RED

pytestmark = pytest.mark.ladder

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"

HEADER_FIELDS = ("game.tick", "own.elixir", "own.hand.card", "tower.hp", "enemy.elixir")
ENTITY_FIELDS = ("entity.id", "entity.x", "entity.y", "entity.hp", "entity.buff")


def _registry() -> dict:
    return json.loads(SNAPSHOT.read_text())


def _field(v: VectorBattle, field: dict, i: int) -> np.ndarray:
    """A field's values in environment ``i``'s block, shaped as the registry gives it."""
    size = prod(field["shape"])
    return v.fields[i, field["offset"] : field["offset"] + size].reshape(field["shape"])


def test_the_field_export_is_off_by_default(install, tables):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        assert v.fields is None


def test_a_synthetic_vector_exports_no_fields(install):
    with pytest.raises(ValueError, match="only a ladder vector exports fields"):
        VectorBattle(1, 1, install=install, engine="synthetic", fields=True)


def test_the_fields_are_a_view_refreshed_in_place_holding_the_v4_values(install, tables):
    registry = _registry()
    by_name = {f["name"]: f for f in registry["fields"]}
    count = 3
    with VectorBattle(count, 1, install=install, tables=tables, fields=True) as v:
        # The snapshot's registry, the row facts' values those of the vector's tables.
        node = v.metadata["fields"]
        assert {k: x for k, x in node.items() if k != "rows"} == {
            k: x for k, x in registry.items() if k != "rows"
        }
        assert {k: x["units"] for k, x in node["rows"].items()} == {
            k: x["units"] for k, x in registry["rows"].items()
        }
        assert v.fields.shape == (count, registry["ints"])
        assert v.fields.dtype == np.int32
        assert not v.fields.flags["OWNDATA"]
        view = v.fields
        schema = v.metadata["schema"]
        header_ints, entity_ints = schema["header_ints"], schema["entity_ints"]
        cap = registry["entity_capacity"]
        tick = by_name["game.tick"]
        v.reset([1, 2, 3], BLUE, RED)
        rng = np.random.default_rng(0)
        for step in range(400):
            if step % 40 == 0:
                for i in range(count):
                    for seat in range(2):
                        o = v.observations[i, seat]
                        for name in HEADER_FIELDS:
                            f = by_name[name]
                            values = _field(v, f, i)
                            if f["tag"] == "seat":
                                values = values[seat]
                            expected = o[f["v4"]["indices"]].reshape(values.shape)
                            np.testing.assert_array_equal(values, expected, err_msg=name)
                        # v4's rows: the first entity.count rows the seat is shown.
                        visible = _field(v, by_name["entity.visible"], i)[seat]
                        rows = np.flatnonzero(visible)[
                            : o[by_name["entity.count"]["v4"]["indices"][0]]
                        ]
                        listed = o[header_ints:].reshape(-1, entity_ints)[: len(rows)]
                        for name in ENTITY_FIELDS:
                            f = by_name[name]
                            values = _field(v, f, i)[seat].reshape(cap, -1)
                            expected = listed[:, f["v4"]["indices"]]
                            np.testing.assert_array_equal(values[rows], expected, err_msg=name)
                            others = np.setdiff1d(np.arange(cap), rows)
                            assert (values[others] == 0).all(), name
            before = view[:, tick["offset"]].copy()
            v.step(draw_actions(v.masks, rng, 0.3))
            assert v.fields is view
            # Refreshed in place: the same memory holds the new decision's clock.
            assert (view[:, tick["offset"]] != before).any()
            np.testing.assert_array_equal(
                view[:, tick["offset"]], v.observations[:, 0, tick["v4"]["indices"][0]]
            )


def test_the_fields_are_read_only(install, tables):
    # A consumer cannot write the block, so no seat entity column can hold a value on a row its
    # seat is not shown; the vector still refreshes it in place.
    with VectorBattle(2, 1, install=install, tables=tables, fields=True) as v:
        v.reset([1, 2], BLUE, RED)
        assert not v.fields.flags.writeable
        with pytest.raises(ValueError, match="read-only"):
            v.fields[0, 0] = 1
        with pytest.raises(ValueError, match="read-only"):
            v.fields[:] = 0
        with pytest.raises(ValueError):  # nor can the flag be turned back on
            v.fields.flags.writeable = True
        with pytest.raises(ValueError):
            v.fields.reshape(-1).flags.writeable = True
        tick = next(f for f in _registry()["fields"] if f["name"] == "game.tick")["offset"]
        before = v.fields[:, tick].copy()
        v.step()
        assert (v.fields[:, tick] > before).all()
