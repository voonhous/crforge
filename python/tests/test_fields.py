"""The field registry the environment publishes in its metadata (``fields``), held to its snapshot.

The snapshot ``data/fields_v1.json`` is the registry's metadata as committed: a change of any
field's name, offset, shape, tag, units or frame shows in its diff. The Java ``FieldsTest`` holds
the registry to the same file.
"""

import json
import re
from pathlib import Path

import pytest

from crforge_env import VectorBattle

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"
SNAPSHOT_1_0 = Path(__file__).parent / "data" / "fields_v1_0.json"
"""Registry 1.0 as it was committed: every later 1.x registry keeps all of it."""


@pytest.fixture(scope="module")
def vector(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        yield v


def test_the_metadata_publishes_the_committed_registry(vector):
    assert vector.metadata["fields"] == json.loads(SNAPSHOT.read_text())


def test_the_registry_lays_out_one_block_of_tagged_fields():
    registry = json.loads(SNAPSHOT.read_text())
    assert re.fullmatch(r"\d+\.\d+", registry["version"])
    by_name = {f["name"]: f for f in registry["fields"]}
    assert len(by_name) == len(registry["fields"])
    stored = sorted(
        (f for f in registry["fields"] if f["alias_of"] is None), key=lambda f: f["offset"]
    )
    offset = 0
    for f in stored:
        assert f["offset"] == offset, f["name"]
        size = 1
        for n in f["shape"]:
            size *= n
        offset += size
    assert offset == registry["ints"]
    for f in registry["fields"]:
        assert f["tag"] in ("public", "seat", "hidden")
        assert f["frame"] in ("game", "seat", "enemy_seat", "none")
        if f["tag"] == "seat":
            assert f["shape"][0] == 2, f["name"]
        if f["alias_of"] is not None:
            assert by_name[f["alias_of"]]["tag"] == "seat"
            assert f["tag"] == "hidden"
    assert by_name["entity.visible"]["shape"] == [2, registry["entity_capacity"]]
    assert by_name["enemy.elixir.true"]["alias_of"] == "own.elixir"


def test_the_v4_sources_and_the_enums_need_no_jvm():
    registry = json.loads(SNAPSHOT.read_text())
    enums = registry["enums"]
    seen = {"header": set(), "entity": set()}
    for f in registry["fields"]:
        kind, _, name = f["units"].partition(":")
        if kind in ("enum", "flags"):
            assert enums.get(name), f["units"]
        v4 = f["v4"]
        if v4 is None:
            continue
        assert f["tag"] in ("seat", "public"), f["name"]
        skip = (1 if f["tag"] == "seat" else 0) + (1 if v4["region"] == "entity" else 0)
        elements = 1
        for n in f["shape"][skip:]:
            elements *= n
        assert len(v4["indices"]) == elements, f["name"]
        for i in v4["indices"]:
            assert i not in seen[v4["region"]], (f["name"], i)
            seen[v4["region"]].add(i)
    # Every int of a v4 entity row has a source.
    assert seen["entity"] == set(range(len(seen["entity"])))
    assert by_unit_names(registry) == set(enums)
    # One shape for every name: an object from a code value (or bit index) to a label, the defined
    # codes only, ascending.
    for name, labels in enums.items():
        assert isinstance(labels, dict), name
        codes = [int(k) for k in labels]
        assert codes == sorted(codes), name
        assert all(isinstance(v, str) for v in labels.values()), name
    assert enums["entity"]["0"] == "alive"
    assert len(enums["ability_refusal"]) == 11


def test_every_1_0_field_is_kept_and_the_fields_added_follow_its_block():
    before = json.loads(SNAPSHOT_1_0.read_text())
    now = json.loads(SNAPSHOT.read_text())
    assert before["version"] == "1.0"
    major, minor = (int(n) for n in now["version"].split("."))
    assert major == 1 and minor >= 1
    # The 1.0 list is the first part of the list, field for field.
    assert now["fields"][: len(before["fields"])] == before["fields"]
    for f in now["fields"][len(before["fields"]) :]:
        assert f["alias_of"] is None, f["name"]
        assert f["offset"] >= before["ints"], f["name"]
    for name, labels in before["enums"].items():
        assert now["enums"][name] == labels, name
    # The own plays mirror the enemy's: the same shapes, units and frames, and no v4 source.
    by_name = {f["name"]: f for f in now["fields"]}
    for name, f in by_name.items():
        if name.startswith("enemy.plays.") and f["tag"] == "seat":
            own = by_name["own.plays." + name.removeprefix("enemy.plays.")]
            assert (own["shape"], own["units"], own["frame"], own["tag"]) == (
                f["shape"],
                f["units"],
                f["frame"],
                "seat",
            )
            assert own["v4"] is None
    # The row facts, each with its units; no values without the tables.
    assert list(now["rows"]) == [
        "bar_rule",
        "hit_speed_ms",
        "load_time_ms",
        "attack_step_ms",
        "attack_start_delay_ms",
        "attack_traits",
    ]
    assert all(entry["values"] == [] for entry in now["rows"].values())


def by_unit_names(registry):
    units = [f["units"] for f in registry["fields"]]
    units += [entry["units"] for entry in registry.get("rows", {}).values()]
    return {u.partition(":")[2] for u in units if u.startswith(("enum:", "flags:"))}
