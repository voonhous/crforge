"""
Gymnasium environment for CRForge.

Action space:
  MultiDiscrete([2, 4, 10])
  - action_type: 0 = no-op, 1 = play card
  - hand_index: 0-3 (which card slot)
  - zone: 0-9 (strategic placement zone)

Placement zones (blue's perspective):
  Own half (troops/buildings):
    0: LEFT_BRIDGE   (5.5, 14.5)  - left lane, immediate pressure
    1: RIGHT_BRIDGE  (12.5, 14.5) - right lane, immediate pressure
    2: LEFT_BACK     (5.5, 4.5)   - left lane, slow push from back
    3: RIGHT_BACK    (12.5, 4.5)  - right lane, slow push from back
    4: CENTER_BACK   (9.0, 5.5)   - behind king tower, splits both lanes
    5: LEFT_DEFENSE  (7.5, 10.5)  - left side reactive defense
    6: RIGHT_DEFENSE (10.5, 10.5) - right side reactive defense
  Enemy half (spells only):
    7: SPELL_LEFT    (5.5, 22.5)  - spell on enemy left lane
    8: SPELL_RIGHT   (12.5, 22.5) - spell on enemy right lane
    9: SPELL_CENTER  (9.0, 20.5)  - spell on enemy center

Observation space:
  Binary mode (default): flat Box(shape=(1079,)) float32 vector.
  JSON mode: Dict with structured game state arrays (all float32 for SB3 compatibility).

Default ticks_per_step=6 gives ~5 decisions/second (~900 steps per 3-min game).
Use ticks_per_step=1 for fine-grained control (30 decisions/sec, ~5400 steps/game).
"""

from typing import Any

import gymnasium as gym
import numpy as np
from gymnasium import spaces

from crforge_gym.bridge import BridgeClient

# Arena dimensions
ARENA_WIDTH = 18
ARENA_HEIGHT = 32

# Max entities the observation can hold (padded/truncated)
MAX_ENTITIES = 64

# Number of features per entity in the observation vector
# team, entity_type, movement_type, x_norm, y_norm, hp_fraction, shield_fraction,
# attack_cooldown_readiness, is_attacking, has_target,
# stunned, slowed, raged, frozen, poisoned,
# lifetime_fraction
ENTITY_FEATURES = 16

# Penalty applied when the agent submits an action that fails (not enough elixir, etc.)
INVALID_ACTION_PENALTY = -0.01

# Max card index value (upper bound for observation space, can grow with new cards)
MAX_CARD_INDEX = 200

# Total size of the flat observation vector
OBS_SIZE = 1079

# Strategic placement zones: (x, y) in arena coordinates.
# Zones 0-6 are on blue's own half (troops/buildings).
# Zones 7-9 are on the enemy half (spells only).
PLACEMENT_ZONES = [
    (5.5, 14.5),   # 0: LEFT_BRIDGE
    (12.5, 14.5),  # 1: RIGHT_BRIDGE
    (5.5, 4.5),    # 2: LEFT_BACK
    (12.5, 4.5),   # 3: RIGHT_BACK
    (9.0, 5.5),    # 4: CENTER_BACK
    (7.5, 10.5),   # 5: LEFT_DEFENSE
    (10.5, 10.5),  # 6: RIGHT_DEFENSE
    (5.5, 22.5),   # 7: SPELL_LEFT
    (12.5, 22.5),  # 8: SPELL_RIGHT
    (9.0, 20.5),   # 9: SPELL_CENTER
]
NUM_ZONES = len(PLACEMENT_ZONES)
# Zones on own half (valid for troops/buildings/spells)
NUM_OWN_HALF_ZONES = 7

# Lane boundary: x < LANE_SPLIT is left lane, x >= LANE_SPLIT is right lane
LANE_SPLIT = 9.0

# Number of lane summary features
# [left_enemy_pressure, right_enemy_pressure, left_enemy_front_y, right_enemy_front_y,
#  left_friendly_pressure, right_friendly_pressure, elixir_advantage, troop_count_advantage]
LANE_SUMMARY_FEATURES = 8

# Max total HP for normalizing lane pressure (approximate -- a big push is ~5000 HP)
_MAX_LANE_HP = 5000.0

# --- Flat observation index constants ---
# These match the binary encoding layout from BinaryObservationEncoder.java.
# Used by ActionMaskedWrapper and opponents to extract values directly from the flat array.
IDX_BLUE_ELIXIR = 3
IDX_HAND_COSTS_START = 7
IDX_HAND_COSTS_END = 11
IDX_HAND_TYPES_START = 11
IDX_HAND_TYPES_END = 15


def _build_observation_space() -> spaces.Dict:
    """Defines the observation space for the environment.

    All fields use float32 for compatibility with Stable Baselines 3.
    Spatial coordinates are normalized to [0, 1] range.
    """
    return spaces.Dict(
        {
            # Global game state
            "frame": spaces.Box(low=0.0, high=600000.0, shape=(1,), dtype=np.float32),
            "game_time": spaces.Box(low=0.0, high=600.0, shape=(1,), dtype=np.float32),
            "is_overtime": spaces.Box(low=0.0, high=1.0, shape=(1,), dtype=np.float32),
            # Per-player state (index 0 = controlled player, index 1 = opponent)
            "elixir": spaces.Box(low=0.0, high=10.0, shape=(2,), dtype=np.float32),
            "crowns": spaces.Box(low=0.0, high=3.0, shape=(2,), dtype=np.float32),
            # Hand: 4 slots -- cost normalized to [0, 1] (cost/10), type as float
            "hand_costs": spaces.Box(low=0.0, high=1.0, shape=(4,), dtype=np.float32),
            "hand_types": spaces.Box(low=0.0, high=2.0, shape=(4,), dtype=np.float32),
            # A1: Card identity -- 0-based index into card vocabulary
            "hand_card_ids": spaces.Box(low=-1.0, high=float(MAX_CARD_INDEX), shape=(4,), dtype=np.float32),
            # Next card
            "next_card_cost": spaces.Box(low=0.0, high=1.0, shape=(1,), dtype=np.float32),
            "next_card_type": spaces.Box(low=0.0, high=2.0, shape=(1,), dtype=np.float32),
            "next_card_id": spaces.Box(low=-1.0, high=float(MAX_CARD_INDEX), shape=(1,), dtype=np.float32),
            # Towers: 6 towers (3 per team) x [hp_fraction, x_norm, y_norm, alive]
            "towers": spaces.Box(low=0.0, high=1.0, shape=(6, 4), dtype=np.float32),
            # Entities: MAX_ENTITIES x ENTITY_FEATURES
            # Features: team, entity_type, movement_type, x_norm, y_norm, hp_fraction, shield_fraction,
            #   attack_cooldown_readiness, is_attacking, has_target,
            #   stunned, slowed, raged, frozen, poisoned, lifetime_fraction
            "entities": spaces.Box(low=0.0, high=5.0, shape=(MAX_ENTITIES, ENTITY_FEATURES), dtype=np.float32),
            "num_entities": spaces.Box(low=0.0, high=float(MAX_ENTITIES), shape=(1,), dtype=np.float32),
            # Pre-computed lane summary: aggregated spatial features the MLP can
            # use directly for reactive placement decisions.
            "lane_summary": spaces.Box(low=-1.0, high=1.0, shape=(LANE_SUMMARY_FEATURES,), dtype=np.float32),
        }
    )


_CARD_TYPE_MAP = {"TROOP": 0, "SPELL": 1, "BUILDING": 2}
_TEAM_MAP = {"BLUE": 0, "RED": 1}
_ENTITY_TYPE_MAP = {"TROOP": 0, "BUILDING": 1, "TOWER": 2, "PROJECTILE": 3, "SPELL": 4}
_MOVEMENT_TYPE_MAP = {"GROUND": 0, "AIR": 1, "BUILDING": 2}


def _tower_to_array(tower: dict) -> np.ndarray:
    """Convert a tower dict to a normalized [hp_fraction, x_norm, y_norm, alive] array."""
    max_hp = tower.get("maxHp", 1)
    if max_hp == 0:
        max_hp = 1
    return np.array(
        [
            tower.get("hp", 0) / max_hp,
            tower.get("x", 0.0) / ARENA_WIDTH,
            tower.get("y", 0.0) / ARENA_HEIGHT,
            1.0 if tower.get("alive", False) else 0.0,
        ],
        dtype=np.float32,
    )


def parse_observation(obs_raw: dict) -> dict[str, np.ndarray]:
    """Convert raw JSON observation to numpy arrays matching the observation space.

    All values are float32. Spatial coordinates are normalized to [0, 1].
    This is a module-level function so it can be reused by SelfPlayOpponent.
    """
    blue = obs_raw.get("bluePlayer", {})
    red = obs_raw.get("redPlayer", {})

    # Global
    frame = np.array([obs_raw.get("frame", 0)], dtype=np.float32)
    game_time = np.array([obs_raw.get("gameTimeSeconds", 0.0)], dtype=np.float32)
    is_overtime = np.array([1.0 if obs_raw.get("isOvertime", False) else 0.0], dtype=np.float32)

    # Player state (index 0 = blue/controlled, index 1 = red/opponent)
    elixir = np.array(
        [blue.get("elixir", 0.0), red.get("elixir", 0.0)], dtype=np.float32
    )
    crowns = np.array(
        [blue.get("crowns", 0), red.get("crowns", 0)], dtype=np.float32
    )

    # Hand (blue player's hand) -- costs normalized to [0, 1] by dividing by 10
    hand = blue.get("hand", [])
    hand_costs = np.zeros(4, dtype=np.float32)
    hand_types = np.zeros(4, dtype=np.float32)
    hand_card_ids = np.full(4, -1.0, dtype=np.float32)
    for i, card in enumerate(hand[:4]):
        hand_costs[i] = card.get("cost", 0) / 10.0
        hand_types[i] = float(_CARD_TYPE_MAP.get(card.get("type", "TROOP"), 0))
        hand_card_ids[i] = float(card.get("cardIndex", -1))

    next_card = blue.get("nextCard")
    if next_card:
        next_card_cost = np.array([next_card.get("cost", 0) / 10.0], dtype=np.float32)
        next_card_type = np.array(
            [float(_CARD_TYPE_MAP.get(next_card.get("type", "TROOP"), 0))], dtype=np.float32
        )
        next_card_id = np.array([float(next_card.get("cardIndex", -1))], dtype=np.float32)
    else:
        next_card_cost = np.zeros(1, dtype=np.float32)
        next_card_type = np.zeros(1, dtype=np.float32)
        next_card_id = np.full(1, -1.0, dtype=np.float32)

    # Towers: [hp_fraction, x_norm, y_norm, alive]
    # Order: blue crown, blue princess L, blue princess R, red crown, red princess L, red princess R
    towers_array = np.zeros((6, 4), dtype=np.float32)
    blue_towers = blue.get("towers", [])
    red_towers = red.get("towers", [])
    for i, tower in enumerate(blue_towers[:3]):
        towers_array[i] = _tower_to_array(tower)
    for i, tower in enumerate(red_towers[:3]):
        towers_array[3 + i] = _tower_to_array(tower)

    # Entities -- spatial coords normalized to [0, 1], with combat state and effects
    entities_raw = obs_raw.get("entities", [])
    entities_array = np.zeros((MAX_ENTITIES, ENTITY_FEATURES), dtype=np.float32)
    num_entities = min(len(entities_raw), MAX_ENTITIES)
    for i in range(num_entities):
        e = entities_raw[i]
        max_hp = e.get("maxHp", 1)
        if max_hp == 0:
            max_hp = 1
        entities_array[i] = [
            float(_TEAM_MAP.get(e.get("team", "BLUE"), 0)),
            float(_ENTITY_TYPE_MAP.get(e.get("entityType", "TROOP"), 0)),
            float(_MOVEMENT_TYPE_MAP.get(e.get("movementType", "GROUND"), 0)),
            e.get("x", 0.0) / ARENA_WIDTH,
            e.get("y", 0.0) / ARENA_HEIGHT,
            e.get("hp", 0) / max_hp,
            e.get("shield", 0) / max_hp,
            # Combat state
            e.get("attackCooldownFraction", 0.0),
            1.0 if e.get("isAttacking", False) else 0.0,
            1.0 if e.get("hasTarget", False) else 0.0,
            # Status effects
            1.0 if e.get("stunned", False) else 0.0,
            1.0 if e.get("slowed", False) else 0.0,
            1.0 if e.get("raged", False) else 0.0,
            1.0 if e.get("frozen", False) else 0.0,
            1.0 if e.get("poisoned", False) else 0.0,
            # Building lifetime
            e.get("lifetimeFraction", 0.0),
        ]

    # Lane summary: pre-computed spatial features for reactive play.
    # The raw entity list is in arbitrary order, making it very hard for an MLP
    # to extract "enemy is pushing left lane." These features make it explicit.
    left_enemy_hp = 0.0
    right_enemy_hp = 0.0
    left_friendly_hp = 0.0
    right_friendly_hp = 0.0
    left_enemy_front_y = ARENA_HEIGHT  # closest to blue base = lowest y
    right_enemy_front_y = ARENA_HEIGHT
    friendly_count = 0
    enemy_count = 0

    for e in entities_raw:
        if e.get("entityType") == "TOWER":
            continue
        team = e.get("team", "BLUE")
        x = e.get("x", 0.0)
        y = e.get("y", 0.0)
        hp = e.get("hp", 0)
        is_left = x < LANE_SPLIT

        if team == "RED":  # enemy from blue's perspective
            enemy_count += 1
            if is_left:
                left_enemy_hp += hp
                left_enemy_front_y = min(left_enemy_front_y, y)
            else:
                right_enemy_hp += hp
                right_enemy_front_y = min(right_enemy_front_y, y)
        else:
            friendly_count += 1
            if is_left:
                left_friendly_hp += hp
            else:
                right_friendly_hp += hp

    blue_elixir = blue.get("elixir", 0.0)
    red_elixir = red.get("elixir", 0.0)

    lane_summary = np.array([
        min(left_enemy_hp / _MAX_LANE_HP, 1.0),
        min(right_enemy_hp / _MAX_LANE_HP, 1.0),
        1.0 - left_enemy_front_y / ARENA_HEIGHT,   # 0 = no enemy, 1 = at blue base
        1.0 - right_enemy_front_y / ARENA_HEIGHT,
        min(left_friendly_hp / _MAX_LANE_HP, 1.0),
        min(right_friendly_hp / _MAX_LANE_HP, 1.0),
        (blue_elixir - red_elixir) / 10.0,         # [-1, 1] elixir advantage
        (friendly_count - enemy_count) / MAX_ENTITIES,  # troop count advantage
    ], dtype=np.float32)

    return {
        "frame": frame,
        "game_time": game_time,
        "is_overtime": is_overtime,
        "elixir": elixir,
        "crowns": crowns,
        "hand_costs": hand_costs,
        "hand_types": hand_types,
        "hand_card_ids": hand_card_ids,
        "next_card_cost": next_card_cost,
        "next_card_type": next_card_type,
        "next_card_id": next_card_id,
        "towers": towers_array,
        "entities": entities_array,
        "num_entities": np.array([num_entities], dtype=np.float32),
        "lane_summary": lane_summary,
    }


class CRForgeEnv(gym.Env):
    """
    Gymnasium environment for CRForge Clash Royale simulation.

    The agent controls the blue player. The red player can be:
    - "random": plays random valid cards at random positions
    - "noop": does nothing
    - "rule_based": plays cards using heuristic rules (prefers high-value plays)
    - A callable that receives an observation dict and returns an action dict

    Args:
        endpoint: ZMQ endpoint for the bridge server (ignored when backend="jpype")
        blue_deck: list of 8 card IDs for the blue player
        red_deck: list of 8 card IDs for the red player
        level: card/tower level (1-15, default 11)
        ticks_per_step: how many simulation ticks per env.step() call (default 6 = ~5 decisions/sec)
        opponent: opponent policy ("random", "noop", "rule_based", or callable)
        invalid_action_penalty: reward penalty for submitting an action that fails (default -0.01)
        binary_obs: use binary observation protocol (default True, much faster)
        backend: "zmq" (default) for ZMQ bridge, "jpype" for in-process JVM via JPype
    """

    metadata = {"render_modes": []}

    def __init__(
        self,
        endpoint: str = "tcp://localhost:9876",
        blue_deck: list[str] | None = None,
        red_deck: list[str] | None = None,
        level: int = 11,
        ticks_per_step: int = 6,
        opponent: str | Any = "random",
        invalid_action_penalty: float = INVALID_ACTION_PENALTY,
        binary_obs: bool = True,
        backend: str = "zmq",
    ):
        super().__init__()

        self.endpoint = endpoint
        self.blue_deck = blue_deck or self._default_deck()
        self.red_deck = red_deck or self._default_deck()
        self.level = level
        self.ticks_per_step = ticks_per_step
        self.opponent = opponent
        self.invalid_action_penalty = invalid_action_penalty
        self.backend = backend

        # JPype backend forces binary observations (no JSON path)
        if backend == "jpype":
            self.binary_obs = True
        else:
            self.binary_obs = binary_obs

        self.action_space = spaces.MultiDiscrete([2, 4, NUM_ZONES])

        if self.binary_obs:
            # Binary mode: flat observation vector, no wrapper needed
            self.observation_space = spaces.Box(
                low=-np.inf, high=np.inf, shape=(OBS_SIZE,), dtype=np.float32
            )
        else:
            self.observation_space = _build_observation_space()

        if backend == "jpype":
            from crforge_gym.jpype_bridge import InProcessBridge
            self._client = InProcessBridge()
        else:
            self._client = BridgeClient(endpoint, binary_obs=binary_obs)
        self._connected = False
        self._rng = np.random.default_rng()
        # In JSON mode, stores the raw dict observation for opponents/wrappers
        self._last_obs_raw = None
        # In binary mode, stores the flat float32 array
        self._last_obs_flat = None
        self._init_seed = None

        # Lazily initialized rule-based opponent
        self._rule_based_opponent = None

    def _default_deck(self) -> list[str]:
        return [
            "knight", "archer", "fireball", "arrows",
            "giant", "musketeer", "minions", "valkyrie",
        ]

    def _ensure_connected(self) -> None:
        if not self._connected:
            self._client.connect()
            self._client.init(
                self.blue_deck,
                self.red_deck,
                level=self.level,
                ticks_per_step=self.ticks_per_step,
                seed=self._init_seed,
            )
            self._connected = True

    def reset(
        self,
        *,
        seed: int | None = None,
        options: dict | None = None,
    ) -> tuple[np.ndarray | dict[str, np.ndarray], dict]:
        super().reset(seed=seed)
        self._rng = np.random.default_rng(seed)

        # Forward seed to Java server for deterministic deck shuffling
        if seed is not None:
            self._init_seed = seed

        self._ensure_connected()
        result = self._client.reset(seed=seed)

        if self.binary_obs:
            self._last_obs_flat = result
            self._last_obs_raw = None
            return result, {}
        else:
            self._last_obs_raw = result
            self._last_obs_flat = None
            return self._parse_observation(result), {}

    def step(
        self, action: np.ndarray
    ) -> tuple[np.ndarray | dict[str, np.ndarray], float, bool, bool, dict]:
        # Decode action
        blue_action = self._decode_action(action)

        # Get opponent action
        red_action = self._get_opponent_action()

        if self.binary_obs:
            (obs, blue_reward, _red_reward, terminated, truncated,
             blue_action_failed, _red_action_failed) = self._client.step(
                blue_action=blue_action,
                red_action=red_action,
            )
            self._last_obs_flat = obs
            self._last_obs_raw = None

            reward = blue_reward
            if blue_action_failed:
                reward += self.invalid_action_penalty

            info = {}
            if blue_action_failed:
                info["action_failed"] = True

            return obs, reward, terminated, truncated, info
        else:
            # JSON mode (original path)
            result = self._client.step(
                blue_action=blue_action,
                red_action=red_action,
            )

            obs_raw = result.get("observation", {})
            reward_data = result.get("reward", {})
            terminated = result.get("terminated", False)
            truncated = result.get("truncated", False)

            self._last_obs_raw = obs_raw
            self._last_obs_flat = None
            obs = self._parse_observation(obs_raw)

            reward = float(reward_data.get("blue", 0.0))

            blue_action_failed = result.get("blueActionFailed", False)
            if blue_action_failed:
                reward += self.invalid_action_penalty

            info = {}
            if blue_action_failed:
                info["action_failed"] = True

            return obs, reward, terminated, truncated, info

    def close(self) -> None:
        if self._connected:
            self._client.close()
            self._connected = False

    def _decode_action(self, action: np.ndarray) -> dict | None:
        """Convert MultiDiscrete action to bridge StepAction dict."""
        action_type = int(action[0])
        if action_type == 0:
            return None  # no-op

        hand_index = int(action[1])
        zone = int(action[2])
        x, y = PLACEMENT_ZONES[zone]

        return {"handIndex": hand_index, "x": x, "y": y}

    def _get_opponent_action(self) -> dict | None:
        """Get the opponent's action based on the configured policy."""
        if self.opponent == "noop":
            return None
        elif self.opponent == "random":
            return self._random_action()
        elif self.opponent == "rule_based":
            return self._rule_based_action()
        elif hasattr(self.opponent, "act"):
            return self.opponent.act(self._last_obs_raw, obs_flat=self._last_obs_flat)
        elif callable(self.opponent):
            return self.opponent(self._last_obs_raw)
        return None

    def _random_action(self) -> dict | None:
        """Generate a random action for the opponent (red player)."""
        # 50% chance of no-op
        if self._rng.random() < 0.5:
            return None

        hand_index = int(self._rng.integers(0, 4))

        # Red places on their own half (y >= 16 in arena coords)
        tile_x = int(self._rng.integers(0, ARENA_WIDTH))
        tile_y = int(self._rng.integers(16, ARENA_HEIGHT))

        return {
            "handIndex": hand_index,
            "x": tile_x + 0.5,
            "y": tile_y + 0.5,
        }

    def _rule_based_action(self) -> dict | None:
        """Rule-based opponent using heuristic strategy (C1)."""
        from crforge_gym.opponents import RuleBasedOpponent

        if self._rule_based_opponent is None:
            self._rule_based_opponent = RuleBasedOpponent(rng=self._rng)
        return self._rule_based_opponent.act(self._last_obs_raw, player="red")

    def _parse_observation(self, obs_raw: dict) -> dict[str, np.ndarray]:
        return parse_observation(obs_raw)
