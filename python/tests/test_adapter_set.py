"""``AdapterSet``, the run-level guard (``forbid_hidden``) and ``OtherSeat``, on synthetic blocks.

No JVM: the registry is the committed snapshot and the blocks are numpy arrays.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from crforge_env.adapters import (
    Adapter,
    AdapterSet,
    ElixirLead,
    EnemyCycle,
    HiddenFieldError,
    OtherSeat,
    UnitAge,
    V4Adapter,
    provenance,
)
from crforge_env.fields import FieldView, Registry, UndeclaredFieldError

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"


class TrueElixir(Adapter):
    """The enemy's true elixir: a hidden field."""

    name = "true_elixir"
    registry_version = "1.0"
    reads = ("enemy.elixir.true",)
    outputs = {"elixir": ((), np.dtype(np.int32))}  # noqa: RUF012

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        return {"elixir": fields["enemy.elixir.true"][:, seat].copy()}


class Snooping(Adapter):
    """Declares the seat's elixir only and tries the enemy's count, which another adapter of its
    set reads: a set hands each adapter its own reads only."""

    name = "snooping"
    registry_version = "1.0"
    reads = ("own.elixir",)
    outputs = {"elixir": ((), np.dtype(np.int32))}  # noqa: RUF012

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        return {"elixir": fields["enemy.elixir"][:, seat].copy()}


class Resets(Adapter):
    """Remembers the reset calls it is given."""

    name = "resets"
    registry_version = "1.0"
    reads = ("game.tick",)
    outputs = {}  # noqa: RUF012

    def __init__(self) -> None:
        self.calls: list[tuple[list[bool], int]] = []

    def reset(self, done: np.ndarray, seat: int) -> None:
        self.calls.append((done.tolist(), seat))

    def observe(self, fields: FieldView, seat: int) -> dict[str, np.ndarray]:
        return {}


@pytest.fixture(scope="module")
def registry() -> Registry:
    return Registry.from_file(SNAPSHOT)


def elixir_block(registry: Registry) -> np.ndarray:
    block = np.zeros((2, registry.ints), dtype=np.int32)
    views = registry.views(block)
    views["own.elixir"][:] = [[50_000, 30_000], [10_000, 100_000]]
    views["enemy.elixir"][:] = [[20_000, 40_000], [90_000, 0]]
    return block


def test_a_set_binds_and_observes_its_adapters_as_one(registry):
    lead, cycle, age = ElixirLead(), EnemyCycle(), UnitAge()
    adapters = AdapterSet([lead, cycle, age])
    adapters.bind(registry)
    assert adapters.registry is registry
    assert all(a.registry is registry for a in (lead, cycle, age))
    assert adapters.hidden is False and adapters.hidden_fields == ()
    # The reads: every adapter's, each once, in the adapters' order.
    assert adapters.reads == tuple(dict.fromkeys((*lead.reads, *cycle.reads, *age.reads)))
    expected = {
        f"{a.name}.{name}": spec for a in (lead, cycle, age) for name, spec in a.outputs.items()
    }
    assert adapters.outputs == expected
    block = elixir_block(registry)
    out = adapters.observe(adapters.view(block), 0)
    assert list(out) == list(expected)
    for key, (shape, dtype) in expected.items():
        assert out[key].shape == (2, *shape) and out[key].dtype == dtype, key
    np.testing.assert_array_equal(out["elixir_lead.lead"], [30_000, -80_000])
    np.testing.assert_array_equal(out["enemy_cycle.queue"], np.full((2, 4), -1))


def test_a_set_hands_each_adapter_its_own_reads_only(registry):
    adapters = AdapterSet([ElixirLead(), Snooping()])
    adapters.bind(registry)
    assert "enemy.elixir" in adapters.reads
    with pytest.raises(UndeclaredFieldError, match=r"enemy\.elixir"):
        adapters.observe(adapters.view(elixir_block(registry)), 0)


def test_a_set_resets_every_adapter(registry):
    first, age = Resets(), UnitAge()
    adapters = AdapterSet([first, age])
    adapters.bind(registry)
    adapters.reset(np.array([True, False]), 1)
    assert first.calls == [([True, False], 1)]


def test_a_set_refuses_two_adapters_of_one_name(registry):
    with pytest.raises(ValueError, match=r"twice: elixir_lead"):
        AdapterSet([ElixirLead(), ElixirLead()])


def test_a_set_refuses_outputs_whose_names_clash(registry):
    class Dotted(Adapter):
        name = "elixir_lead.lead"
        registry_version = "1.0"
        reads = ()
        outputs = {"x": ((), np.dtype(np.int32))}  # noqa: RUF012

        def observe(self, fields, seat):
            return {}

    class Plain(Dotted):
        name = "elixir_lead"
        outputs = {"lead.x": ((), np.dtype(np.int32))}  # noqa: RUF012

    with pytest.raises(ValueError, match=r"elixir_lead\.lead\.x"):
        AdapterSet([Dotted(), Plain()]).bind(registry)


def test_a_set_without_the_guard_takes_a_hidden_adapter_and_says_so(registry):
    adapters = AdapterSet([ElixirLead(), TrueElixir()])
    adapters.bind(registry)
    assert adapters.hidden is True
    assert adapters.hidden_fields == ("enemy.elixir.true",)


def test_the_guard_refuses_a_hidden_adapter_at_bind(registry):
    adapters = AdapterSet([ElixirLead(), TrueElixir()], forbid_hidden=True)
    with pytest.raises(HiddenFieldError, match=r"true_elixir: enemy\.elixir\.true"):
        adapters.bind(registry)
    assert adapters.registry is None


def test_the_guard_refuses_a_bound_hidden_adapter_at_construction(registry):
    hidden = TrueElixir()
    hidden.bind(registry)
    with pytest.raises(HiddenFieldError, match="true_elixir"):
        AdapterSet([ElixirLead(), hidden], forbid_hidden=True)


def test_the_guard_takes_the_shared_adapters_and_v4(registry):
    adapters = AdapterSet([V4Adapter(), ElixirLead(), EnemyCycle(), UnitAge()], forbid_hidden=True)
    adapters.bind(registry)
    assert adapters.hidden is False


def test_the_guard_refuses_the_other_seats_observation(registry):
    with pytest.raises(HiddenFieldError, match="v4@other_seat"):
        AdapterSet([V4Adapter(), OtherSeat(V4Adapter())], forbid_hidden=True).bind(registry)


def test_a_set_gives_the_provenance_record_of_one_role(registry):
    lead, cycle = ElixirLead(), EnemyCycle()
    adapters = AdapterSet([lead, cycle])
    with pytest.raises(ValueError, match="not bound"):
        adapters.provenance("learner")
    adapters.bind(registry)
    record = adapters.provenance("learner")
    assert record == provenance({"learner": [lead, cycle]}, registry)
    assert record["roles"]["learner"]["adapters"] == ["elixir_lead", "enemy_cycle"]
    assert json.loads(json.dumps(record)) == record


def test_the_other_seat_reads_the_other_seats_values_and_is_hidden(registry):
    other = OtherSeat(ElixirLead())
    other.bind(registry)
    assert other.name == "elixir_lead@other_seat"
    assert other.reads == ("own.elixir", "enemy.elixir")
    assert other.outputs == ElixirLead().outputs
    # Every seat field it reads is what the other seat is shown, so hidden from this one.
    assert other.hidden is True
    assert other.hidden_fields == ("own.elixir", "enemy.elixir")
    block = elixir_block(registry)
    np.testing.assert_array_equal(other.observe(other.view(block), 0)["lead"], [-10_000, 100_000])


def test_a_critic_on_the_other_seats_observation_is_recorded_hidden(registry):
    v4, critic = V4Adapter(), OtherSeat(V4Adapter())
    v4.bind(registry)
    critic.bind(registry)
    record = provenance({"learner": [v4], "critic": [v4, critic]}, registry)["roles"]
    assert record["learner"]["hidden"] is False
    assert record["critic"]["adapters"] == ["v4", "v4@other_seat"]
    assert record["critic"]["hidden"] is True
    seat_reads = sorted(n for n in v4.reads if registry.field(n).tag == "seat")
    assert record["critic"]["hidden_fields"] == seat_reads
    assert "game.tick" not in record["critic"]["hidden_fields"]  # public: the same for both


def test_the_other_seat_resets_the_other_seats_state(registry):
    inner = Resets()
    other = OtherSeat(inner)
    other.bind(registry)
    other.reset(np.array([True]), 0)
    assert inner.calls == [([True], 1)]
