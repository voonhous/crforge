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


def by_unit_names(registry):
    return {
        f["units"].partition(":")[2]
        for f in registry["fields"]
        if f["units"].startswith(("enum:", "flags:"))
    }
