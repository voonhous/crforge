"""The provenance records an evaluation keeps: what each role of it read, on synthetic battles.

A policy handed the v4 observation is recorded as the ``v4`` adapter's reads, unless it names the
adapters it reads (``adapters``); the random and wait opponents read no field.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from crforge_env import evaluate, random_opponent, wait_opponent
from crforge_env.adapters import ElixirLead, OtherSeat, V4Adapter
from crforge_env.evaluation import policy_adapters
from crforge_env.fields import Registry
from tests.decks import BLUE

SNAPSHOT = Path(__file__).parent / "data" / "fields_v1.json"
ROLES = {"policy", "opponent"}


def run(policy, opponent, install):
    return evaluate(
        policy,
        opponent,
        deck=BLUE,
        episodes=2,
        envs=2,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=3,
    )


def v4_reads(registry: Registry) -> list[str]:
    v4 = V4Adapter()
    v4.bind(registry)
    return sorted(v4.reads)


def plain_policy(observation: np.ndarray, mask: np.ndarray, rng: np.random.Generator) -> int:
    """A policy on the observation it is handed, with no adapters named."""
    return 0


class Named:
    """A policy that names the adapters it reads."""

    def __init__(self, *adapters):
        self.adapters = adapters

    def __call__(self, observation, mask, rng):
        return 0


def test_a_policy_on_the_observation_is_recorded_as_the_v4_reads(install):
    result = run(plain_policy, random_opponent, install)
    record = result.provenance
    assert json.loads(json.dumps(record)) == record
    registry = Registry.from_file(SNAPSHOT)
    assert record["registry"] == registry.version
    roles = record["roles"]
    assert set(roles) == ROLES
    assert roles["policy"] == {
        "adapters": ["v4"],
        "reads": v4_reads(registry),
        "hidden": False,
        "hidden_fields": [],
    }
    # The random opponent reads its mask alone, no field.
    assert roles["opponent"] == {"adapters": [], "reads": [], "hidden": False, "hidden_fields": []}


def test_the_wait_opponent_reads_nothing(install):
    roles = run(wait_opponent, wait_opponent, install).provenance["roles"]
    assert roles["policy"]["reads"] == roles["opponent"]["reads"] == []


def test_a_policy_naming_its_adapters_is_recorded_with_them(install):
    roles = run(Named(ElixirLead()), Named(V4Adapter(), OtherSeat(V4Adapter())), install)
    roles = roles.provenance["roles"]
    assert roles["policy"]["adapters"] == ["elixir_lead"]
    assert roles["policy"]["reads"] == ["enemy.elixir", "own.elixir"]
    assert roles["policy"]["hidden"] is False
    # One that reads the other seat's observation is recorded as hidden.
    assert roles["opponent"]["adapters"] == ["v4", "v4@other_seat"]
    assert roles["opponent"]["hidden"] is True
    assert "own.elixir" in roles["opponent"]["hidden_fields"]


def test_policy_adapters_binds_only_adapters_not_bound():
    registry = Registry.from_file(SNAPSHOT)
    lead = ElixirLead()
    lead.bind(registry)
    other = V4Adapter()
    adapters = policy_adapters(Named(lead, other), registry)
    assert adapters == [lead, other]
    assert lead.registry is registry and other.registry is registry
    (v4,) = policy_adapters(plain_policy, registry)
    assert isinstance(v4, V4Adapter) and v4.registry is registry
    assert policy_adapters(random_opponent, registry) == []
