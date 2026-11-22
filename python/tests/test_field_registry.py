"""The Python field registry (``crforge_env.fields``): parsed, validated and read as views.

No JVM: the registry comes from the committed snapshot or from small hand-written nodes, and the
blocks are synthetic numpy arrays.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import numpy as np
import pytest

from crforge_env.fields import (
    Field,
    FieldView,
    Registry,
    RegistryVersionError,
    UndeclaredFieldError,
)

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"


@pytest.fixture(scope="module")
def registry() -> Registry:
    return Registry.from_file(SNAPSHOT)


def block_of(registry: Registry, count: int = 3) -> np.ndarray:
    """A block whose every int is distinct, so a view in the wrong place shows."""
    return np.arange(count * registry.ints, dtype=np.int32).reshape(count, registry.ints)


def small_node(**changes) -> dict:
    """A registry of five fields in 9 ints: public, seat, seat table, hidden and an alias."""
    node = {
        "version": "1.0",
        "ints": 9,
        "entity_capacity": 2,
        "enums": {"side": {"0": "side_0", "1": "side_1", "2": "neither"}},
        "fields": [
            field_node("game.tick", 0, [], "public", "ticks"),
            field_node("own.elixir", 1, [2], "seat", "1/10000 elixir"),
            field_node("entity.side", 3, [2, 2], "seat", "enum:side"),
            field_node("entity.hp.true", 7, [2], "hidden", "hit points"),
            field_node(
                "enemy.elixir.true", 1, [2], "hidden", "1/10000 elixir", alias_of="own.elixir"
            ),
        ],
    }
    node.update(changes)
    return node


def field_node(name, offset, shape, tag, units, alias_of=None, frame="none") -> dict:
    return {
        "name": name,
        "offset": offset,
        "shape": shape,
        "tag": tag,
        "units": units,
        "frame": frame,
        "alias_of": alias_of,
        "v4": None,
    }


# Parsing.


def test_the_snapshot_parses_into_fields_and_enums_with_int_keys(registry):
    raw = json.loads(SNAPSHOT.read_text())
    assert registry.version == raw["version"]
    assert registry.ints == raw["ints"]
    assert registry.entity_capacity == raw["entity_capacity"]
    assert list(registry.fields) == [f["name"] for f in raw["fields"]]
    for name, labels in raw["enums"].items():
        assert registry.enums[name] == {int(code): label for code, label in labels.items()}
        assert all(isinstance(code, int) for code in registry.enums[name])
    elixir = registry.field("own.elixir")
    assert isinstance(elixir, Field)
    assert elixir.shape == (2,)
    assert elixir.tag == "seat"
    assert elixir.alias_of is None
    assert elixir.size == 2
    alias = registry.field("enemy.elixir.true")
    assert alias.alias_of == "own.elixir"
    assert alias.tag == "hidden"
    assert alias.size == 0
    assert registry.field("entity.buff").size == 2 * raw["entity_capacity"] * 3
    assert registry.field("entity.x").v4["region"] == "entity"
    stored = sum(f.size for f in registry.fields.values())
    assert stored == registry.ints


def test_metadata_and_file_give_the_same_registry(registry):
    node = json.loads(SNAPSHOT.read_text())
    again = Registry.from_metadata(node)
    assert again.fields == registry.fields
    assert again.enums == registry.enums
    assert (again.version, again.ints, again.entity_capacity) == (
        registry.version,
        registry.ints,
        registry.entity_capacity,
    )


def test_an_unknown_field_is_a_key_error(registry):
    with pytest.raises(KeyError, match=r"no\.such\.field"):
        registry.field("no.such.field")


# Validation on load.


def test_overlapping_fields_are_refused():
    node = small_node()
    node["fields"][2]["offset"] = 2  # entity.side over own.elixir's second int
    with pytest.raises(ValueError, match=r"own\.elixir.*entity\.side|entity\.side.*own\.elixir"):
        Registry.from_metadata(node)


def test_a_field_past_the_block_is_refused():
    node = small_node(ints=8)
    with pytest.raises(ValueError, match=r"entity\.hp\.true"):
        Registry.from_metadata(node)
    node = small_node()
    node["fields"][0]["offset"] = -1
    with pytest.raises(ValueError, match=r"game\.tick"):
        Registry.from_metadata(node)


def test_an_unknown_tag_is_refused():
    node = small_node()
    node["fields"][3]["tag"] = "secret"
    with pytest.raises(ValueError, match=r"entity\.hp\.true.*secret"):
        Registry.from_metadata(node)


def test_a_repeated_name_is_refused():
    node = small_node()
    node["fields"].append(copy.deepcopy(node["fields"][0]))
    node["fields"][-1]["offset"] = 8
    node["fields"][3]["shape"] = [1]
    with pytest.raises(ValueError, match=r"game\.tick"):
        Registry.from_metadata(node)


def test_a_seat_field_needs_a_leading_seat_axis():
    node = small_node()
    node["fields"][1]["shape"] = [1]
    with pytest.raises(ValueError, match=r"own\.elixir"):
        Registry.from_metadata(node)


def test_an_alias_must_name_a_stored_seat_field_of_its_shape():
    unknown = small_node()
    unknown["fields"][4]["alias_of"] = "own.mana"
    with pytest.raises(ValueError, match=r"enemy\.elixir\.true.*own\.mana"):
        Registry.from_metadata(unknown)

    public = small_node()
    public["fields"][4]["alias_of"] = "game.tick"
    public["fields"][4]["offset"] = 0
    public["fields"][4]["shape"] = []
    with pytest.raises(ValueError, match=r"enemy\.elixir\.true.*seat"):
        Registry.from_metadata(public)

    shape = small_node()
    shape["fields"][4]["shape"] = [2, 1]
    with pytest.raises(ValueError, match=r"enemy\.elixir\.true.*shape"):
        Registry.from_metadata(shape)

    chained = small_node()
    chained["fields"].append(
        field_node("again.true", 1, [2], "hidden", "1/10000 elixir", alias_of="enemy.elixir.true")
    )
    with pytest.raises(ValueError, match=r"again\.true"):
        Registry.from_metadata(chained)

    placed = small_node()
    placed["fields"][4]["offset"] = 3
    with pytest.raises(ValueError, match=r"enemy\.elixir\.true.*offset"):
        Registry.from_metadata(placed)

    # An alias reads the other seat's values: it is never a plain-named seat field.
    shown = small_node()
    shown["fields"][4]["tag"] = "seat"
    with pytest.raises(ValueError, match=r"enemy\.elixir\.true.*hidden"):
        Registry.from_metadata(shown)


def test_a_unit_naming_a_missing_enum_is_refused():
    node = small_node(enums={})
    with pytest.raises(ValueError, match=r"entity\.side.*side"):
        Registry.from_metadata(node)


def test_a_malformed_version_is_refused():
    with pytest.raises(ValueError, match="version"):
        Registry.from_metadata(small_node(version="one"))


# The version rule.


def test_check_takes_the_same_major_and_a_minor_at_least_the_one_required():
    registry = Registry.from_metadata(small_node(version="1.3"))
    registry.check("1.0")
    registry.check("1.3")
    for required in ("1.4", "2.0", "0.9"):
        with pytest.raises(RegistryVersionError, match=r"1\.3"):
            registry.check(required)
    assert issubclass(RegistryVersionError, ValueError)
    with pytest.raises(ValueError, match=r"M\.m"):
        registry.check("1")


# Views.


def test_views_are_zero_copy_reshapes_of_the_block(registry):
    block = block_of(registry)
    views = registry.views(block)
    assert list(views) == list(registry.fields)
    for name, field in registry.fields.items():
        view = views[name]
        assert view.shape == (block.shape[0], *field.shape), name
        assert np.shares_memory(view, block), name
        if field.alias_of is None:
            expected = block[:, field.offset : field.offset + field.size].reshape(view.shape)
            np.testing.assert_array_equal(view, expected, err_msg=name)


def test_an_alias_is_its_target_flipped_on_the_seat_axis(registry):
    block = block_of(registry)
    views = registry.views(block)
    for name, field in registry.fields.items():
        if field.alias_of is None:
            continue
        target = views[field.alias_of]
        np.testing.assert_array_equal(views[name], target[:, ::-1], err_msg=name)
    block[1, registry.field("own.elixir").offset + 0] = 70_000
    assert views["enemy.elixir.true"][1, 1] == 70_000


def test_views_follow_the_block_in_place(registry):
    block = block_of(registry, count=2)
    views = registry.views(block)
    entity_x = registry.field("entity.x")
    block[1, entity_x.offset + registry.entity_capacity + 5] = -1234  # seat 1, row 5
    assert views["entity.x"][1, 1, 5] == -1234
    block[0, registry.field("game.tick").offset] = 99
    assert views["game.tick"][0] == 99
    assert views["game.tick"].shape == (2,)


def test_views_need_a_c_contiguous_int32_block_of_the_registry_width(registry):
    good = block_of(registry)
    with pytest.raises(ValueError, match="int32"):
        registry.views(good.astype(np.int64))
    with pytest.raises(ValueError, match="int32"):
        registry.views(good.astype(np.dtype(">i4") if np.little_endian else np.dtype("<i4")))
    with pytest.raises(ValueError, match="shape"):
        registry.views(good[:, :-1].copy())
    with pytest.raises(ValueError, match="shape"):
        registry.views(good[0])
    with pytest.raises(ValueError, match="contiguous"):
        registry.views(np.asfortranarray(good))
    wide = np.zeros((3, registry.ints + 1), dtype=np.int32)
    with pytest.raises(ValueError, match="contiguous"):
        registry.views(wide[:, 1:])
    with pytest.raises(ValueError, match="numpy"):
        registry.views(good.tolist())


# FieldView.


def test_a_field_view_holds_the_declared_names_only(registry):
    block = block_of(registry)
    view = registry.view(block, ["own.elixir", "enemy.elixir"])
    assert isinstance(view, FieldView)
    assert list(view) == ["own.elixir", "enemy.elixir"]
    assert len(view) == 2
    assert "own.elixir" in view
    assert "entity.hp.true" not in view
    np.testing.assert_array_equal(view["own.elixir"], registry.views(block)["own.elixir"])
    assert np.shares_memory(view["own.elixir"], block)
    for name in ("entity.hp.true", "own.side", "no.such.field"):
        with pytest.raises(UndeclaredFieldError) as raised:
            view[name]
        assert name in str(raised.value)
        assert "not declared" in str(raised.value)
        with pytest.raises(UndeclaredFieldError):
            view.get(name)
    assert issubclass(UndeclaredFieldError, KeyError)


def test_a_field_view_is_read_only_and_follows_the_block(registry):
    block = block_of(registry)
    view = registry.view(block, ["own.elixir", "enemy.elixir.true"])
    for name in view:
        assert not view[name].flags.writeable, name
        with pytest.raises(ValueError):
            view[name][0, 0] = 1
        with pytest.raises(ValueError):  # nor can the flag be turned back on
            view[name].flags.writeable = True
    assert block.flags.writeable
    block[2, registry.field("own.elixir").offset + 1] = 55_000
    assert view["own.elixir"][2, 1] == 55_000
    assert view["enemy.elixir.true"][2, 0] == 55_000


def test_a_view_of_an_unknown_field_is_a_key_error(registry):
    with pytest.raises(KeyError, match=r"no\.such\.field"):
        registry.view(block_of(registry), ["own.elixir", "no.such.field"])
