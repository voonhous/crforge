"""
Opponent policies for CRForge environments.

C1: Rule-based opponent that uses heuristics to play cards.
"""

import numpy as np

from crforge_gym.env import ARENA_WIDTH, ARENA_HEIGHT


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
    """

    # Elixir threshold before playing a card. Waiting until 7 lets the
    # opponent accumulate enough for bigger plays and keeps a small reserve.
    ELIXIR_PLAY_THRESHOLD = 7.0

    # Small random chance to play even below threshold (prevents pure passivity)
    EARLY_PLAY_CHANCE = 0.1
    EARLY_PLAY_MIN_ELIXIR = 4.0

    def __init__(self, rng: np.random.Generator | None = None):
        self._rng = rng or np.random.default_rng()

    def act(self, obs_raw: dict | None, player: str = "red") -> dict | None:
        """Pick an action given the raw observation from the bridge.

        Args:
            obs_raw: Raw observation dict from the Java bridge (before Python parsing).
            player: Which player this opponent controls ("blue" or "red").

        Returns:
            Action dict {"handIndex", "x", "y"} or None for no-op.
        """
        if obs_raw is None:
            return None

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

    def _pick_position(
        self, card_type: str, player: str, obs_raw: dict
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
