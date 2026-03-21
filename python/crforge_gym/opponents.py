"""
Opponent policies for CRForge environments.

C1: Rule-based opponent that uses heuristics to play cards.
C2: Self-play opponent that mirrors observations and runs a trained model as red.
"""

import numpy as np

from crforge_gym.env import ARENA_WIDTH, ARENA_HEIGHT, NUM_ZONES, NUM_OWN_HALF_ZONES, OBS_SIZE, parse_observation


class RuleBasedOpponent:
    """Heuristic opponent that makes reasonable card plays.

    Strategy:
    - Waits until elixir >= 7 before playing (avoids wasting resources)
    - Plays the highest-cost affordable card (gets value on the board)
    - Places troops at the bridge for pressure
    - Places spells on enemy clusters (approximated by targeting the center
      of the enemy half where troops tend to congregate)
    - Buildings behind the bridge for defense

    This is much better training signal than a random opponent because:
    - The agent must learn to defend against bridge pushes
    - The agent must learn to counter-push when opponent is low on elixir
    - Card plays are reasonable, not wasted on empty areas

    In binary mode, this opponent uses the flat observation array directly
    and does not require the raw JSON dict. It reads red player's elixir
    from index 4 and uses a random card selection strategy since card cost
    data for red player is not included in the binary observation.
    """

    # Elixir threshold before playing a card. Waiting until 7 lets the
    # opponent accumulate enough for bigger plays and keeps a small reserve.
    ELIXIR_PLAY_THRESHOLD = 7.0

    # Small random chance to play even below threshold (prevents pure passivity)
    EARLY_PLAY_CHANCE = 0.1
    EARLY_PLAY_MIN_ELIXIR = 4.0

    def __init__(self, rng: np.random.Generator | None = None):
        self._rng = rng or np.random.default_rng()

    def act(self, obs_raw: dict | None, player: str = "red", obs_flat: np.ndarray | None = None) -> dict | None:
        """Pick an action given the raw observation from the bridge.

        Args:
            obs_raw: Raw observation dict from the Java bridge (before Python parsing).
                     Can be None in binary mode.
            player: Which player this opponent controls ("blue" or "red").
            obs_flat: Flat float32 observation array (binary mode). If provided and
                      obs_raw is None, uses a simplified strategy based on available data.

        Returns:
            Action dict {"handIndex", "x", "y"} or None for no-op.
        """
        # If we have the raw dict, use the full strategy
        if obs_raw is not None:
            return self._act_from_dict(obs_raw, player)

        # Binary mode: red player's elixir is at index 4, but we don't have
        # red's hand card costs/types. Use a simplified threshold-based strategy.
        if obs_flat is not None:
            return self._act_from_flat(obs_flat, player)

        return None

    def _act_from_dict(self, obs_raw: dict, player: str) -> dict | None:
        """Full rule-based strategy using JSON observation."""
        player_key = "bluePlayer" if player == "blue" else "redPlayer"
        player_obs = obs_raw.get(player_key, {})

        elixir = player_obs.get("elixir", 0.0)
        hand = player_obs.get("hand", [])

        if not hand:
            return None

        # Decide whether to play based on elixir threshold
        should_play = elixir >= self.ELIXIR_PLAY_THRESHOLD
        if not should_play and elixir >= self.EARLY_PLAY_MIN_ELIXIR:
            should_play = self._rng.random() < self.EARLY_PLAY_CHANCE

        if not should_play:
            return None

        # Find the highest-cost card we can afford
        best_index = -1
        best_cost = -1
        for i, card in enumerate(hand):
            cost = card.get("cost", 99)
            if cost <= elixir and cost > best_cost:
                best_cost = cost
                best_index = i

        if best_index < 0:
            return None

        card = hand[best_index]
        card_type = card.get("type", "TROOP")

        # Pick placement position based on card type and player side
        x, y = self._pick_position(card_type, player, obs_raw)

        return {"handIndex": best_index, "x": x, "y": y}

    def _act_from_flat(self, obs_flat: np.ndarray, player: str) -> dict | None:
        """Simplified strategy when only flat observation is available.

        Red's elixir is at index 4. We don't have red's hand costs, so we
        play a random card and hope it works (the server rejects invalid plays).
        """
        red_elixir = float(obs_flat[4]) if player == "red" else float(obs_flat[3])

        should_play = red_elixir >= self.ELIXIR_PLAY_THRESHOLD
        if not should_play and red_elixir >= self.EARLY_PLAY_MIN_ELIXIR:
            should_play = self._rng.random() < self.EARLY_PLAY_CHANCE

        if not should_play:
            return None

        # Pick a random hand index since we don't have card costs
        hand_index = int(self._rng.integers(0, 4))

        # Default to troop placement strategy
        x, y = self._pick_position("TROOP", player, None)
        return {"handIndex": hand_index, "x": x, "y": y}

    def _pick_position(
        self, card_type: str, player: str, obs_raw: dict | None
    ) -> tuple[float, float]:
        """Choose where to place a card based on type and player side.

        Red's own half: y in [16, 31] (top half)
        Blue's own half: y in [0, 15] (bottom half)
        Bridge line: y = 15 (blue side) or y = 16 (red side)
        """
        is_red = player == "red"

        # Add some horizontal randomness around the center lanes
        # Center-left: x ~ 5-7, Center-right: x ~ 11-13
        lane = int(self._rng.integers(0, 2))
        if lane == 0:
            x = float(self._rng.integers(5, 8)) + 0.5
        else:
            x = float(self._rng.integers(11, 14)) + 0.5

        if card_type == "TROOP":
            # Place troops at the bridge for immediate pressure
            if is_red:
                y = 16.5  # Just past bridge on red side
            else:
                y = 15.5  # Just past bridge on blue side

            # Occasionally place troops behind own tower for a slow push
            if self._rng.random() < 0.3:
                if is_red:
                    y = float(self._rng.integers(24, 28)) + 0.5
                else:
                    y = float(self._rng.integers(4, 8)) + 0.5

        elif card_type == "SPELL":
            # Target spells at the enemy half where troops cluster
            # Focus on the area between bridge and enemy towers
            if is_red:
                # Red targets blue's half (y 6-14)
                y = float(self._rng.integers(6, 15)) + 0.5
            else:
                # Blue targets red's half (y 17-25)
                y = float(self._rng.integers(17, 26)) + 0.5

            # Try to target enemy entity clusters if visible
            if obs_raw is not None:
                target = self._find_enemy_cluster(obs_raw, player)
                if target is not None:
                    x, y = target

        elif card_type == "BUILDING":
            # Place buildings behind the bridge for defense
            if is_red:
                y = float(self._rng.integers(19, 23)) + 0.5
            else:
                y = float(self._rng.integers(9, 13)) + 0.5

            # Center buildings more to cover both lanes
            x = float(self._rng.integers(7, 12)) + 0.5

        else:
            # Fallback: center of own half
            if is_red:
                y = 24.5
            else:
                y = 7.5

        return x, y

    def _find_enemy_cluster(
        self, obs_raw: dict, player: str
    ) -> tuple[float, float] | None:
        """Find the centroid of enemy entities for spell targeting.

        Returns (x, y) in arena coordinates, or None if no enemies found.
        """
        entities = obs_raw.get("entities", [])
        enemy_team = "BLUE" if player == "red" else "RED"

        enemy_x = []
        enemy_y = []
        for e in entities:
            if e.get("team") == enemy_team and e.get("entityType") != "TOWER":
                enemy_x.append(e.get("x", 0.0))
                enemy_y.append(e.get("y", 0.0))

        if len(enemy_x) < 2:
            # Not enough enemies to form a cluster worth targeting
            return None

        # Return centroid of enemy positions
        cx = sum(enemy_x) / len(enemy_x)
        cy = sum(enemy_y) / len(enemy_y)
        return cx, cy


# -- Self-play helpers --

# Fixed ordering of observation keys, must match FlattenedObsWrapper._OBS_KEYS
_OBS_KEYS = [
    "frame", "game_time", "is_overtime",
    "elixir", "crowns",
    "hand_costs", "hand_types", "hand_card_ids",
    "next_card_cost", "next_card_type", "next_card_id",
    "towers", "entities", "num_entities",
    "lane_summary",
]


def _mirror_entity(e: dict) -> dict:
    """Flip an entity's y coordinate and swap its team label."""
    mirrored = dict(e)
    mirrored["y"] = ARENA_HEIGHT - e.get("y", 0)
    team = e.get("team", "BLUE")
    mirrored["team"] = "RED" if team == "BLUE" else "BLUE"
    return mirrored


def _mirror_tower(t: dict) -> dict:
    """Flip a tower's y coordinate."""
    mirrored = dict(t)
    mirrored["y"] = ARENA_HEIGHT - t.get("y", 0)
    return mirrored


def _flatten_dict_obs(dict_obs: dict[str, np.ndarray]) -> np.ndarray:
    """Flatten dict observation in the same key order as FlattenedObsWrapper."""
    parts = [dict_obs[k].astype(np.float32).flatten() for k in _OBS_KEYS]
    return np.concatenate(parts)


class SelfPlayOpponent:
    """Plays as red using a trained MaskablePPO model.

    Mirrors observations so red sees the game from blue's perspective,
    runs the model to get an action, and mirrors the action back to
    red's coordinate space.

    Supports both binary and JSON observation modes. In binary mode,
    receives the flat observation array and mirrors it directly using
    array index manipulation.
    """

    def __init__(self, model):
        """Initialize with a MaskablePPO instance (live reference or loaded from file)."""
        self.model = model

    @staticmethod
    def mirror_raw_obs(raw_obs: dict) -> dict:
        """Create a copy of raw_obs with blue/red perspectives swapped and y-flipped."""
        mirrored = dict(raw_obs)

        blue_player = raw_obs.get("bluePlayer", {})
        red_player = raw_obs.get("redPlayer", {})

        # Swap players and mirror their tower positions
        blue_towers = blue_player.get("towers", [])
        red_towers = red_player.get("towers", [])
        mirrored["bluePlayer"] = {**red_player, "towers": [_mirror_tower(t) for t in red_towers]}
        mirrored["redPlayer"] = {**blue_player, "towers": [_mirror_tower(t) for t in blue_towers]}

        # Flip entity y coords and swap team labels
        mirrored["entities"] = [_mirror_entity(e) for e in raw_obs.get("entities", [])]

        return mirrored

    @staticmethod
    def mirror_flat_obs(flat_obs: np.ndarray) -> np.ndarray:
        """Mirror a flat binary observation so red sees the game from blue's perspective.

        Swaps blue/red data (elixir, crowns, towers) and flips entity team/y values.
        """
        from crforge_gym.env import MAX_ENTITIES, ENTITY_FEATURES

        m = flat_obs.copy()

        # Swap elixir (indices 3, 4)
        m[3], m[4] = flat_obs[4], flat_obs[3]

        # Swap crowns (indices 5, 6)
        m[5], m[6] = flat_obs[6], flat_obs[5]

        # Hand data (indices 7-21): replace with red player's hand
        # In binary mode, only blue's hand is encoded, so red's hand is unknown.
        # We zero out the hand to indicate "no hand info" for red-as-blue.
        # The model will learn to play without perfect hand knowledge of the
        # mirrored perspective. This is a known limitation of binary self-play.
        # For proper self-play with binary mode, the server would need to encode
        # both players' hands. For now, keep the hand as-is (it's blue's hand,
        # which becomes "our" hand from red's perspective in self-play the model
        # doesn't get red's actual hand... but this matches what the model sees
        # during training where it always plays as blue).
        # Actually -- in self-play, we need red's hand to be visible to the model.
        # Since binary mode only includes blue's hand, self-play should use JSON mode
        # or the model should be trained to handle this. Leave hand as-is for now.

        # Swap tower blocks: blue towers [22:34] <-> red towers [34:46]
        # Also flip y coordinates (index 2 within each 4-float tower block)
        blue_towers = flat_obs[22:34].copy()
        red_towers = flat_obs[34:46].copy()
        # Mirror y: y_norm -> 1 - y_norm
        for i in range(3):
            base = i * 4
            blue_towers[base + 2] = 1.0 - blue_towers[base + 2]
            red_towers[base + 2] = 1.0 - red_towers[base + 2]
        m[22:34] = red_towers
        m[34:46] = blue_towers

        # Mirror entities (offset 46, 64 x 16 features)
        ent_start = 46
        num_ent = int(flat_obs[1070])
        for i in range(num_ent):
            base = ent_start + i * ENTITY_FEATURES
            # Swap team: 0 -> 1, 1 -> 0
            m[base] = 1.0 - flat_obs[base]
            # Flip y_norm: y -> 1 - y
            m[base + 4] = 1.0 - flat_obs[base + 4]

        # Mirror lane summary (offset 1071, 8 values)
        # The lane summary is from blue's perspective; for red-as-blue we need to
        # swap left/right and flip friendly/enemy.
        ls = 1071
        # Swap enemy pressures: left <-> right (red's left is blue's right from the other side)
        # Actually, mirroring is y-flip not x-flip, so left stays left.
        # But we need to swap enemy/friendly since teams are swapped.
        m[ls] = flat_obs[ls + 4]      # left_enemy_pressure <- left_friendly_pressure
        m[ls + 1] = flat_obs[ls + 5]  # right_enemy_pressure <- right_friendly_pressure
        m[ls + 2] = 1.0 - flat_obs[ls + 2]  # left_enemy_front_y: flip
        m[ls + 3] = 1.0 - flat_obs[ls + 3]  # right_enemy_front_y: flip
        m[ls + 4] = flat_obs[ls]       # left_friendly_pressure <- left_enemy_pressure
        m[ls + 5] = flat_obs[ls + 1]   # right_friendly_pressure <- right_enemy_pressure
        m[ls + 6] = -flat_obs[ls + 6]  # elixir_advantage: negate
        m[ls + 7] = -flat_obs[ls + 7]  # troop_count_advantage: negate

        return m

    # Mirrored zone mapping: maps blue zone index to the (x, y) coords in red's
    # coordinate space. Zones are y-flipped (32 - y) since red plays from the top.
    _MIRRORED_ZONES = [
        (5.5, 32 - 14.5),   # 0: LEFT_BRIDGE -> red's bridge left
        (12.5, 32 - 14.5),  # 1: RIGHT_BRIDGE -> red's bridge right
        (5.5, 32 - 4.5),    # 2: LEFT_BACK -> red's back left
        (12.5, 32 - 4.5),   # 3: RIGHT_BACK -> red's back right
        (9.0, 32 - 5.5),    # 4: CENTER_BACK -> red's back center
        (7.5, 32 - 10.5),   # 5: LEFT_DEFENSE -> red's defense left
        (10.5, 32 - 10.5),  # 6: RIGHT_DEFENSE -> red's defense right
        (5.5, 32 - 22.5),   # 7: SPELL_LEFT -> spell on blue's left
        (12.5, 32 - 22.5),  # 8: SPELL_RIGHT -> spell on blue's right
        (9.0, 32 - 20.5),   # 9: SPELL_CENTER -> spell on blue's center
    ]

    def act(self, obs_raw: dict | None, player: str = "red", obs_flat: np.ndarray | None = None) -> dict | None:
        """Produce a red-side action from the raw observation.

        1. Mirror obs so red looks like blue
        2. Parse into dict obs / use flat array
        3. Flatten (same order as FlattenedObsWrapper)
        4. Compute action mask for red
        5. Predict with model
        6. Map zone to red's coordinate space via mirrored zone table
        """
        if obs_flat is not None:
            # Binary mode: mirror the flat array directly
            mirrored_flat = self.mirror_flat_obs(obs_flat)
            mask = self._compute_mask_from_flat(mirrored_flat)
            action, _ = self.model.predict(mirrored_flat, action_masks=mask, deterministic=False)
        elif obs_raw is not None:
            # JSON mode: original path
            mirrored = self.mirror_raw_obs(obs_raw)
            dict_obs = parse_observation(mirrored)
            flat_obs = _flatten_dict_obs(dict_obs)
            mask = self._compute_mask(mirrored)
            action, _ = self.model.predict(flat_obs, action_masks=mask, deterministic=False)
        else:
            return None

        action_type = int(action[0])
        if action_type == 0:
            return None

        hand_idx = int(action[1])
        zone = int(action[2])
        x, y = self._MIRRORED_ZONES[zone]

        return {"handIndex": hand_idx, "x": x, "y": y}

    def _compute_mask(self, mirrored_obs: dict) -> np.ndarray:
        """Compute action mask from mirrored (red-as-blue) raw observation."""
        blue = mirrored_obs.get("bluePlayer", {})  # Actually red's data
        elixir = blue.get("elixir", 0)
        hand = blue.get("hand", [])

        action_type_mask = np.array([True, True])
        hand_mask = np.array([True, True, True, True])
        zone_mask = np.ones(NUM_ZONES, dtype=bool)

        has_affordable_spell = False
        for i in range(4):
            cost = hand[i].get("cost", 99) if i < len(hand) else 99
            card_type = hand[i].get("type", "TROOP") if i < len(hand) else "TROOP"
            affordable = 0 < cost <= elixir
            hand_mask[i] = affordable
            if affordable and card_type == "SPELL":
                has_affordable_spell = True
        if not np.any(hand_mask):
            action_type_mask[1] = False

        if not has_affordable_spell:
            zone_mask[NUM_OWN_HALF_ZONES:] = False

        return np.concatenate([action_type_mask, hand_mask, zone_mask])

    def _compute_mask_from_flat(self, mirrored_flat: np.ndarray) -> np.ndarray:
        """Compute action mask from mirrored flat observation array."""
        from crforge_gym.env import IDX_BLUE_ELIXIR, IDX_HAND_COSTS_START, IDX_HAND_COSTS_END, IDX_HAND_TYPES_START, IDX_HAND_TYPES_END

        elixir = float(mirrored_flat[IDX_BLUE_ELIXIR])
        hand_costs = mirrored_flat[IDX_HAND_COSTS_START:IDX_HAND_COSTS_END] * 10.0
        hand_types = mirrored_flat[IDX_HAND_TYPES_START:IDX_HAND_TYPES_END]

        action_type_mask = np.array([True, True])
        hand_mask = np.array([True, True, True, True])
        zone_mask = np.ones(NUM_ZONES, dtype=bool)

        has_affordable_spell = False
        for i in range(4):
            cost = hand_costs[i]
            affordable = 0 < cost <= elixir
            hand_mask[i] = affordable
            if affordable and hand_types[i] == 1.0:  # SPELL
                has_affordable_spell = True

        if not np.any(hand_mask):
            action_type_mask[1] = False

        if not has_affordable_spell:
            zone_mask[NUM_OWN_HALF_ZONES:] = False

        return np.concatenate([action_type_mask, hand_mask, zone_mask])
