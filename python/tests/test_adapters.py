"""The adapter base (``crforge_env.adapters``): binding, the hidden-field check and provenance.

No JVM: the registry is the committed snapshot and the blocks are synthetic numpy arrays.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from crforge_env.adapters import Adapter, HiddenFieldError, provenance, require_fair
from crforge_env.fields import FieldView, Registry, RegistryVersionError, UndeclaredFieldError

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"


class ElixirLead(Adapter):
    """The example: the seat's elixir lead over the enemy as the seat counts the enemy's elixir.

    It reads two plain-named seat fields, so it reads only what the seat is shown.
    """

    name = "elixir_lead"
    registry_version = "1.0"
    reads = ("own.elixir", "enemy.elixir")
    outputs = {"lead": ((), np.dtype(np.int32))}  # noqa: RUF012

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        lead = fields["own.elixir"][:, seat] - fields["enemy.elixir"][:, seat]
        return {"lead": lead.astype(np.int32)}


class TrueElixirLead(ElixirLead):
    """The same lead on the enemy's true elixir: a hidden field, so not a fair input."""

    name = "true_elixir_lead"
    reads = ("own.elixir", "enemy.elixir.true")

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        lead = fields["own.elixir"][:, seat] - fields["enemy.elixir.true"][:, seat]
        return {"lead": lead.astype(np.int32)}


class Peeking(ElixirLead):
    """Declares the fair fields but reads a hidden one: the view refuses it."""

    name = "peeking"

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        return {"lead": fields["enemy.elixir.true"][:, seat]}


@pytest.fixture(scope="module")
def registry() -> Registry:
    return Registry.from_file(SNAPSHOT)


def elixir_block(registry: Registry) -> np.ndarray:
    """Two environments: own elixir per seat, the enemy's elixir as each seat counts it."""
    block = np.zeros((2, registry.ints), dtype=np.int32)
    views = registry.views(block)
    views["own.elixir"][:] = [[50_000, 30_000], [10_000, 100_000]]
    views["enemy.elixir"][:] = [[20_000, 40_000], [90_000, 0]]
    return block


def test_the_example_adapter_reads_its_declared_fields(registry):
    adapter = ElixirLead()
    adapter.bind(registry)
    assert adapter.registry is registry
    assert adapter.hidden_fields == ()
    assert not adapter.hidden
    block = elixir_block(registry)
    fields = registry.view(block, adapter.reads)
    np.testing.assert_array_equal(adapter.observe(fields, 0)["lead"], [30_000, -80_000])
    np.testing.assert_array_equal(adapter.observe(fields, 1)["lead"], [-10_000, 100_000])
    out = adapter.observe(adapter.view(block), 0)["lead"]
    shape, dtype = adapter.outputs["lead"]
    assert out.shape == (block.shape[0], *shape)
    assert out.dtype == dtype
    adapter.reset(np.array([True, False]), 0)  # the default reset does nothing


def test_an_adapter_on_a_hidden_field_says_so(registry):
    adapter = TrueElixirLead()
    adapter.bind(registry)
    assert adapter.hidden_fields == ("enemy.elixir.true",)
    assert adapter.hidden
    block = elixir_block(registry)
    # The true elixir is the other seat's own elixir.
    np.testing.assert_array_equal(
        adapter.observe(adapter.view(block), 0)["lead"], [20_000, -90_000]
    )


def test_reading_an_undeclared_field_is_refused(registry):
    adapter = Peeking()
    adapter.bind(registry)
    assert not adapter.hidden
    with pytest.raises(UndeclaredFieldError, match=r"enemy\.elixir\.true.*not declared"):
        adapter.observe(adapter.view(elixir_block(registry)), 0)


def test_bind_checks_the_version_and_every_read(registry):
    class Newer(ElixirLead):
        registry_version = "1.1"

    with pytest.raises(RegistryVersionError):
        Newer().bind(registry)

    class OtherMajor(ElixirLead):
        registry_version = "2.0"

    with pytest.raises(RegistryVersionError):
        OtherMajor().bind(registry)

    class Missing(ElixirLead):
        name = "missing"
        reads = ("own.elixir", "own.mana", "enemy.mana")

    with pytest.raises(ValueError, match=r"missing.*own\.mana.*enemy\.mana"):
        Missing().bind(registry)


def test_an_unbound_adapter_refuses_the_hidden_question():
    with pytest.raises(RuntimeError, match="bind"):
        _ = ElixirLead().hidden_fields
    with pytest.raises(RuntimeError, match="bind"):
        ElixirLead().view(np.zeros((1, 1), dtype=np.int32))


def test_an_adapter_needs_observe():
    class Empty(Adapter):
        name = "empty"
        registry_version = "1.0"
        reads = ()
        outputs = {}  # noqa: RUF012

    with pytest.raises(TypeError):
        Empty()


def test_require_fair_names_the_adapter_and_its_hidden_fields(registry):
    fair, unfair = ElixirLead(), TrueElixirLead()
    fair.bind(registry)
    unfair.bind(registry)
    require_fair(fair)
    require_fair()
    with pytest.raises(HiddenFieldError) as raised:
        require_fair(fair, unfair)
    message = str(raised.value)
    assert "true_elixir_lead" in message
    assert "enemy.elixir.true" in message
    assert message.count("elixir_lead:") == 1  # the fair adapter is not named
    assert issubclass(HiddenFieldError, ValueError)


def test_provenance_is_json_and_deterministic(registry):
    fair, unfair = ElixirLead(), TrueElixirLead()
    fair.bind(registry)
    unfair.bind(registry)
    record = provenance({"opponent": [unfair], "learner": [fair]}, registry)
    assert record == {
        "registry": "1.0",
        "roles": {
            "learner": {
                "adapters": ["elixir_lead"],
                "reads": ["enemy.elixir", "own.elixir"],
                "hidden": False,
                "hidden_fields": [],
            },
            "opponent": {
                "adapters": ["true_elixir_lead"],
                "reads": ["enemy.elixir.true", "own.elixir"],
                "hidden": True,
                "hidden_fields": ["enemy.elixir.true"],
            },
        },
    }
    assert list(record["roles"]) == ["learner", "opponent"]
    assert json.loads(json.dumps(record)) == record
    again = provenance({"learner": [fair], "opponent": [unfair]}, registry)
    assert json.dumps(again) == json.dumps(record)
    both = provenance({"teacher": [unfair, fair]}, registry)["roles"]["teacher"]
    assert both["adapters"] == ["true_elixir_lead", "elixir_lead"]
    assert both["reads"] == ["enemy.elixir", "enemy.elixir.true", "own.elixir"]
    assert both["hidden_fields"] == ["enemy.elixir.true"]


def test_provenance_refuses_a_read_the_registry_lacks(registry):
    class Missing(ElixirLead):
        name = "missing"
        reads = ("own.mana",)

    with pytest.raises(ValueError, match=r"missing.*own\.mana"):
        provenance({"learner": [Missing()]}, registry)
