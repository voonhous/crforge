"""
Wrappers for CRForge environments.

EpisodeStatsWrapper tracks per-episode reward, length, and game outcome
(win/loss/draw) and exposes them in the SB3-compatible info["episode"] format.

FlattenedObsWrapper converts the Dict observation space into a single flat
float32 vector, which works with SB3's simpler MlpPolicy and avoids the
complexity of MultiInputPolicy.

ActionMaskedWrapper adds action masking for use with sb3-contrib's
MaskablePPO, preventing the agent from exploring invalid actions (cards
it can't afford, placing on the enemy half of the arena).
"""

import time

import gymnasium as gym
import numpy as np
from gymnasium import spaces


class EpisodeStatsWrapper(gym.Wrapper):
    """Tracks per-episode statistics for SB3 logging.

    On episode termination, adds info["episode"] with:
    - r: total episode reward
    - l: episode length (steps)
    - t: wall-clock time since wrapper creation

    Also adds info["game_outcome"]: "win", "loss", or "draw" based on the
    terminal step reward (win/loss bonus of +/-30 dominates any per-step shaping,
    draw penalty is -2).

    Wrap order: EpisodeStatsWrapper(CRForgeEnv(...))
    Place this innermost, before FlattenedObsWrapper and ActionMaskedWrapper.
    """

    # Terminal step reward threshold for classifying game outcome.
    # Win bonus is +30, loss penalty is -30, draw penalty is -2.
    _WIN_THRESHOLD = 20.0
    _LOSS_THRESHOLD = -20.0

    def __init__(self, env: gym.Env):
        super().__init__(env)
        self._episode_reward = 0.0
        self._episode_length = 0
        self._start_time = time.time()

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        self._episode_reward = 0.0
        self._episode_length = 0
        return obs, info

    def step(self, action):
        obs, reward, terminated, truncated, info = self.env.step(action)
        self._episode_reward += reward
        self._episode_length += 1

        if terminated or truncated:
            info["episode"] = {
                "r": self._episode_reward,
                "l": self._episode_length,
                "t": time.time() - self._start_time,
            }
            # Classify game outcome from terminal step reward
            if reward > self._WIN_THRESHOLD:
                info["game_outcome"] = "win"
            elif reward < self._LOSS_THRESHOLD:
                info["game_outcome"] = "loss"
            else:
                info["game_outcome"] = "draw"

        return obs, reward, terminated, truncated, info


class FlattenedObsWrapper(gym.ObservationWrapper):
    """Flattens the Dict observation space into a single 1D float32 vector.

    This is the recommended wrapper for use with Stable Baselines 3, since
    MlpPolicy expects a flat Box observation space.

    The flattened vector concatenates all observation fields in a fixed order:
    frame, game_time, is_overtime, elixir, crowns, hand_costs, hand_types,
    hand_card_ids, next_card_cost, next_card_type, next_card_id,
    towers (flattened), entities (flattened), num_entities.

    Note: When using binary_obs=True (the default), the env already returns
    a flat observation and this wrapper is not needed.
    """

    # Fixed ordering of observation keys for consistent flattening
    _OBS_KEYS = [
        "frame", "game_time", "is_overtime",
        "elixir", "crowns",
        "hand_costs", "hand_types", "hand_card_ids",
        "next_card_cost", "next_card_type", "next_card_id",
        "towers", "entities", "num_entities",
        "lane_summary",
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
    - zone: own-half zones (0-6) always valid; enemy-half zones (7-9) only
      when an affordable spell is in hand

    For MultiDiscrete([2, 4, 10]), the mask is a flat boolean array of
    length 2 + 4 + 10 = 16.

    Supports both binary and JSON observation modes. In binary mode, reads
    elixir and hand data directly from the flat observation array indices.

    Wrap order: ActionMaskedWrapper(FlattenedObsWrapper(CRForgeEnv(...)))
    or ActionMaskedWrapper(EpisodeStatsWrapper(CRForgeEnv(binary_obs=True)))
    """

    def __init__(self, env: gym.Env):
        super().__init__(env)
        self._last_dict_obs: dict[str, np.ndarray] | None = None

        # Grab the inner CRForgeEnv to access the raw obs
        inner = env
        while hasattr(inner, "env"):
            inner = inner.env
        self._inner_env = inner
        self._binary_mode = getattr(inner, "binary_obs", False)

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        self._capture_obs()
        return obs, info

    def step(self, action):
        obs, reward, terminated, truncated, info = self.env.step(action)
        self._capture_obs()
        return obs, reward, terminated, truncated, info

    def _capture_obs(self):
        """Capture observation data for action masking."""
        if self._binary_mode:
            # In binary mode, read directly from the flat observation array
            flat = self._inner_env._last_obs_flat
            if flat is not None:
                from crforge_gym.env import IDX_BLUE_ELIXIR, IDX_HAND_COSTS_START, IDX_HAND_COSTS_END, IDX_HAND_TYPES_START, IDX_HAND_TYPES_END
                elixir = float(flat[IDX_BLUE_ELIXIR])
                # Hand costs are normalized by /10, convert back to actual cost
                hand_costs_norm = flat[IDX_HAND_COSTS_START:IDX_HAND_COSTS_END]
                hand_costs = hand_costs_norm * 10.0
                # Hand types: 0=TROOP, 1=SPELL, 2=BUILDING
                hand_types_float = flat[IDX_HAND_TYPES_START:IDX_HAND_TYPES_END]
                hand_types = []
                for t in hand_types_float:
                    if t == 1.0:
                        hand_types.append("SPELL")
                    elif t == 2.0:
                        hand_types.append("BUILDING")
                    else:
                        hand_types.append("TROOP")
                self._last_dict_obs = {
                    "elixir": elixir,
                    "hand_costs": hand_costs.astype(np.float32),
                    "hand_types": hand_types,
                }
        else:
            # JSON mode: extract from raw observation dict
            raw = self._inner_env._last_obs_raw
            if raw is not None:
                blue = raw.get("bluePlayer", {})
                hand = blue.get("hand", [])
                elixir = blue.get("elixir", 0.0)

                hand_costs = np.zeros(4, dtype=np.float32)
                hand_types = []
                for i, card in enumerate(hand[:4]):
                    hand_costs[i] = card.get("cost", 99)
                    hand_types.append(card.get("type", "TROOP"))

                self._last_dict_obs = {
                    "elixir": elixir,
                    "hand_costs": hand_costs,
                    "hand_types": hand_types,
                }

    def action_masks(self) -> np.ndarray:
        """Return a flat boolean mask of length 16 for MultiDiscrete([2, 4, 10])."""
        from crforge_gym.env import NUM_ZONES, NUM_OWN_HALF_ZONES

        action_type_mask = np.array([True, True])  # [noop, play]
        hand_mask = np.array([True, True, True, True])
        zone_mask = np.ones(NUM_ZONES, dtype=bool)

        if self._last_dict_obs is not None:
            elixir = self._last_dict_obs["elixir"]
            hand_costs = self._last_dict_obs["hand_costs"]
            hand_types = self._last_dict_obs.get("hand_types", [])

            has_affordable_spell = False
            for i in range(4):
                affordable = hand_costs[i] <= elixir and hand_costs[i] > 0
                hand_mask[i] = affordable
                if affordable and i < len(hand_types) and hand_types[i] == "SPELL":
                    has_affordable_spell = True

            if not np.any(hand_mask):
                action_type_mask[1] = False

            # Enemy-half zones (7-9) only valid when a spell is affordable
            if not has_affordable_spell:
                zone_mask[NUM_OWN_HALF_ZONES:] = False

        return np.concatenate([action_type_mask, hand_mask, zone_mask])
