"""
Observation wrappers for CRForge environments.

FlattenedObsWrapper converts the Dict observation space into a single flat
float32 vector, which works with SB3's simpler MlpPolicy and avoids the
complexity of MultiInputPolicy.
"""

import gymnasium as gym
import numpy as np
from gymnasium import spaces


class FlattenedObsWrapper(gym.ObservationWrapper):
    """Flattens the Dict observation space into a single 1D float32 vector.

    This is the recommended wrapper for use with Stable Baselines 3, since
    MlpPolicy expects a flat Box observation space.

    The flattened vector concatenates all observation fields in a fixed order:
    frame, game_time, is_overtime, elixir, crowns, hand_costs, hand_types,
    hand_card_ids, next_card_cost, next_card_type, next_card_id,
    towers (flattened), entities (flattened), num_entities.
    """

    # Fixed ordering of observation keys for consistent flattening
    _OBS_KEYS = [
        "frame", "game_time", "is_overtime",
        "elixir", "crowns",
        "hand_costs", "hand_types", "hand_card_ids",
        "next_card_cost", "next_card_type", "next_card_id",
        "towers", "entities", "num_entities",
    ]

    def __init__(self, env: gym.Env):
        super().__init__(env)

        # Compute total flat size from the wrapped env's observation space
        total_size = 0
        for key in self._OBS_KEYS:
            space = env.observation_space[key]
            total_size += int(np.prod(space.shape))

        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf, shape=(total_size,), dtype=np.float32
        )

    def observation(self, observation: dict[str, np.ndarray]) -> np.ndarray:
        """Flatten the dict observation into a single vector."""
        parts = []
        for key in self._OBS_KEYS:
            arr = observation[key].astype(np.float32).flatten()
            parts.append(arr)
        return np.concatenate(parts)
