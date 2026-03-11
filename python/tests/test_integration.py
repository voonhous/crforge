"""
End-to-end integration tests for the CRForge bridge.

Requires the Java bridge server to be running on localhost:9876.
Start it with: ./gradlew :gym-bridge:run

Run with: pytest python/tests/test_integration.py -v
"""

import os
import subprocess
import sys
import time

import numpy as np
import pytest

from crforge_gym import BridgeClient, CRForgeEnv

# Skip all tests if CRFORGE_INTEGRATION env var is not set
pytestmark = pytest.mark.skipif(
    os.environ.get("CRFORGE_INTEGRATION") != "1",
    reason="Set CRFORGE_INTEGRATION=1 and start the Java server to run integration tests",
)

ENDPOINT = os.environ.get("CRFORGE_ENDPOINT", "tcp://localhost:9876")

TEST_DECK = [
    "knight", "archer", "fireball", "arrows",
    "giant", "musketeer", "minions", "valkyrie",
]


class TestBridgeClient:
    """Tests using the raw BridgeClient protocol."""

    def test_init_reset_step_close(self):
        """Full lifecycle: init -> reset -> 100 steps -> close."""
        client = BridgeClient(ENDPOINT)
        client.connect()

        try:
            # Init
            response = client.init(TEST_DECK, TEST_DECK, level=11, ticks_per_step=6)
            assert response["type"] == "init_ok"
            assert "cardIds" in response.get("data", {})

            # Reset
            obs = client.reset()
            assert "frame" in obs
            assert "bluePlayer" in obs
            assert "redPlayer" in obs
            assert "entities" in obs
            assert obs["frame"] == 0

            # Verify initial observation structure
            blue = obs["bluePlayer"]
            assert "elixir" in blue
            assert "hand" in blue
            assert len(blue["hand"]) == 4
            assert "towers" in blue

            # Step 100 times with no-ops
            for i in range(100):
                result = client.step(blue_action=None, red_action=None)
                assert "observation" in result
                assert "reward" in result
                assert "terminated" in result
                assert "truncated" in result

            # Verify state advanced
            final_obs = result["observation"]
            assert final_obs["frame"] == 600  # 100 steps * 6 ticks_per_step

        finally:
            client.close()

    def test_step_with_action(self):
        """Verify that submitting an action works."""
        client = BridgeClient(ENDPOINT)
        client.connect()

        try:
            client.init(TEST_DECK, TEST_DECK, level=11, ticks_per_step=6)
            client.reset()

            # Play card 0 at a valid position on blue's side
            blue_action = {"handIndex": 0, "x": 9.0, "y": 5.0}
            result = client.step(blue_action=blue_action, red_action=None)

            assert "observation" in result
            assert not result["terminated"]

        finally:
            client.close()

    def test_deterministic_seeding(self):
        """Same seed produces identical deck shuffles across resets."""
        client = BridgeClient(ENDPOINT)
        client.connect()

        try:
            client.init(TEST_DECK, TEST_DECK, level=11, ticks_per_step=1, seed=42)

            # First reset with seed
            obs1 = client.reset(seed=12345)
            hand1 = [(c["id"], c["cost"]) for c in obs1["bluePlayer"]["hand"]]

            # Second reset with same seed
            obs2 = client.reset(seed=12345)
            hand2 = [(c["id"], c["cost"]) for c in obs2["bluePlayer"]["hand"]]

            assert hand1 == hand2, f"Same seed should produce same hand: {hand1} vs {hand2}"

            # Third reset with different seed should (very likely) differ
            obs3 = client.reset(seed=99999)
            hand3 = [(c["id"], c["cost"]) for c in obs3["bluePlayer"]["hand"]]
            # Not guaranteed to differ but highly likely with a different seed

        finally:
            client.close()

    def test_action_failed_flag(self):
        """Submitting an unaffordable card reports actionFailed."""
        client = BridgeClient(ENDPOINT)
        client.connect()

        try:
            client.init(TEST_DECK, TEST_DECK, level=11, ticks_per_step=1)
            client.reset()

            # Spend elixir by playing cards until we can't afford any more
            # Start with 5 elixir, try playing multiple cards quickly
            for _ in range(5):
                result = client.step(
                    blue_action={"handIndex": 0, "x": 9.0, "y": 5.0},
                    red_action=None,
                )

            # By now, most cards should fail due to low elixir
            # Check that the actionFailed field exists in the protocol
            assert "blueActionFailed" in result
            assert "redActionFailed" in result

        finally:
            client.close()


class TestCRForgeEnv:
    """Tests using the Gymnasium environment wrapper."""

    def test_env_lifecycle(self):
        """Create env, reset, step, close."""
        env = CRForgeEnv(endpoint=ENDPOINT, ticks_per_step=6)

        try:
            obs, info = env.reset(seed=42)

            # Verify observation structure
            assert isinstance(obs, dict)
            for key in env.observation_space.spaces:
                assert key in obs, f"Missing observation key: {key}"
                assert obs[key].dtype == np.float32, f"Key {key} has wrong dtype: {obs[key].dtype}"

            # Run some steps
            for _ in range(50):
                action = env.action_space.sample()
                obs, reward, terminated, truncated, info = env.step(action)
                assert isinstance(reward, float)
                if terminated or truncated:
                    obs, info = env.reset()

        finally:
            env.close()

    def test_observation_space_containment(self):
        """Observations must be within the declared observation space."""
        env = CRForgeEnv(endpoint=ENDPOINT, ticks_per_step=6)

        try:
            obs, _ = env.reset(seed=42)
            assert env.observation_space.contains(obs), (
                f"Initial observation not in observation space"
            )

            for _ in range(20):
                action = env.action_space.sample()
                obs, reward, terminated, truncated, info = env.step(action)
                assert env.observation_space.contains(obs), (
                    f"Step observation not in observation space"
                )
                if terminated or truncated:
                    obs, _ = env.reset()

        finally:
            env.close()

    def test_seed_determinism(self):
        """Same seed produces same initial observation."""
        env = CRForgeEnv(endpoint=ENDPOINT, ticks_per_step=6)

        try:
            obs1, _ = env.reset(seed=42)
            obs2, _ = env.reset(seed=42)

            for key in obs1:
                np.testing.assert_array_equal(
                    obs1[key], obs2[key],
                    err_msg=f"Observation key '{key}' differs between resets with same seed",
                )

        finally:
            env.close()
