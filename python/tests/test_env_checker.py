"""
Gymnasium API compliance test using the built-in env_checker.

Requires the Java bridge server to be running on localhost:9876.
Start it with: ./gradlew :gym-bridge:run

Run with: pytest python/tests/test_env_checker.py -v
"""

import os

import pytest
from gymnasium.utils.env_checker import check_env

from crforge_gym import CRForgeEnv

pytestmark = pytest.mark.skipif(
    os.environ.get("CRFORGE_INTEGRATION") != "1",
    reason="Set CRFORGE_INTEGRATION=1 and start the Java server to run integration tests",
)

ENDPOINT = os.environ.get("CRFORGE_ENDPOINT", "tcp://localhost:9876")


def test_gymnasium_env_checker():
    """Run Gymnasium's built-in env_checker to validate API compliance.

    This catches dtype mismatches, shape errors, observation space violations,
    and other common issues that break training with SB3.
    """
    env = CRForgeEnv(endpoint=ENDPOINT, ticks_per_step=6)
    try:
        check_env(env.unwrapped, skip_render_check=True)
    finally:
        env.close()
