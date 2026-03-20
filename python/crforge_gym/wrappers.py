"""
Wrappers for CRForge environments.

FlattenedObsWrapper converts the Dict observation space into a single flat
float32 vector, which works with SB3's simpler MlpPolicy and avoids the
complexity of MultiInputPolicy.

ActionMaskedWrapper adds action masking for use with sb3-contrib's
MaskablePPO, preventing the agent from exploring invalid actions (cards
it can't afford, placing on the enemy half of the arena).
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


class ActionMaskedWrapper(gym.Wrapper):
    """Adds action masking for use with sb3-contrib's MaskablePPO.

    Computes masks from the observation to prevent the agent from exploring
    invalid actions:
    - action_type: noop always valid; play only if at least one card is affordable
    - hand_index: only affordable cards are unmasked
    - tile_x: all columns valid
    - tile_y: all positions valid (spells need enemy half; Java validates troops)

    For MultiDiscrete([2, 4, 18, 32]), the mask is a flat boolean array of
    length 2 + 4 + 18 + 32 = 56.

    Wrap order: ActionMaskedWrapper(FlattenedObsWrapper(CRForgeEnv(...)))
    """

    def __init__(self, env: gym.Env):
        super().__init__(env)
        # Cache the last raw Dict observation for mask computation.
        # We intercept the inner env's observation before flattening.
        self._last_dict_obs: dict[str, np.ndarray] | None = None

        # Grab the inner CRForgeEnv to access the raw obs
        inner = env
        while hasattr(inner, "env"):
            inner = inner.env
        self._inner_env = inner

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        self._capture_dict_obs()
        return obs, info

    def step(self, action):
        obs, reward, terminated, truncated, info = self.env.step(action)
        self._capture_dict_obs()
        return obs, reward, terminated, truncated, info

    def _capture_dict_obs(self):
        """Capture the raw dict observation from the inner CRForgeEnv."""
        raw = self._inner_env._last_obs_raw
        if raw is not None:
            blue = raw.get("bluePlayer", {})
            hand = blue.get("hand", [])
            elixir = blue.get("elixir", 0.0)

            hand_costs = np.zeros(4, dtype=np.float32)
            for i, card in enumerate(hand[:4]):
                hand_costs[i] = card.get("cost", 99)

            self._last_dict_obs = {
                "elixir": elixir,
                "hand_costs": hand_costs,
            }

    def action_masks(self) -> np.ndarray:
        """Return a flat boolean mask of length 56 for MultiDiscrete([2, 4, 18, 32])."""
        # Defaults: everything valid
        action_type_mask = np.array([True, True])  # [noop, play]
        hand_mask = np.array([True, True, True, True])
        tile_x_mask = np.ones(18, dtype=bool)
        # All y-positions valid -- spells must target enemy half.
        # Java-side validation rejects invalid troop placements; the
        # invalid-action penalty teaches the agent to avoid them.
        tile_y_mask = np.ones(32, dtype=bool)

        if self._last_dict_obs is not None:
            elixir = self._last_dict_obs["elixir"]
            hand_costs = self._last_dict_obs["hand_costs"]

            # Mask unaffordable cards
            for i in range(4):
                hand_mask[i] = hand_costs[i] <= elixir and hand_costs[i] > 0

            # If no card is affordable, only noop is valid
            if not np.any(hand_mask):
                action_type_mask[1] = False

        return np.concatenate([action_type_mask, hand_mask, tile_x_mask, tile_y_mask])
