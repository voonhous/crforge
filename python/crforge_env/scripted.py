"""A scripted opponent: a few readable rules on what the seat's player sees.

:class:`ScriptedBot` is a policy of the same shape as :func:`random_opponent`
(``bot(observation, mask, rng) -> action``), so it plays the other seat of a
:class:`ClashGymEnv` or either seat in :func:`crforge_env.evaluate`. It reads the seat's
observation and mask only: its own elixir, hand and waiting plays, the enemy princess towers' hit
points and the enemy characters on the field. Nothing hidden from the player is read.

Each decision, in order:

1. **Defend.** An enemy character on the seat's half of the field (past the river) and no play of
   the seat's own waiting: play the cheapest open troop or building card in the lane of the
   deepest such character, on the defence row just behind the princess towers.
2. **Attack.** The elixir (less what the seat's waiting plays hold back) at the attack threshold,
   9 elixir by default: play the cheapest open troop card at the bridge of the lane whose enemy
   princess tower has fewer hit points (``rng`` breaks a tie), on the last row of the seat's half.
3. **Wait** otherwise.

A card is open when the mask opens a tile of its hand slot (the mask carries the elixir, the
start cooldown and a play of the card already waiting). The chosen tile is played when the mask
opens it, else the nearest tile of that slot the mask opens; the bot never takes an action the
mask closed. Spells are never played.

The rules are deterministic: the same observations, masks and generator states give the same
actions, and ``rng`` is drawn from only to break a tie of the two lanes.
"""

from __future__ import annotations

from collections.abc import Mapping

import numpy as np

from .schema import Schema
from .vector import VectorBattle


class ScriptedBot:
    """The scripted policy; call :meth:`bind` with a vector before the first decision.

    ``card_types`` maps a hand card's vocabulary id to ``troop``, ``building`` or ``spell``; left
    None, :meth:`bind` reads it from the vector's tables (:meth:`VectorBattle.card_types`). A
    synthetic vector has no tables, so it needs them given. A :class:`ClashGymEnv` binds its
    opponent itself, and :func:`crforge_env.evaluate` binds both policies.

    The geometry is the seat's frame of the standard arena, in tiles of the action grid:
    ``lane_cols`` the left and right lanes' columns (the bridges and princess towers),
    ``bridge_row`` the last row of the seat's half before the river and ``defend_row`` the row
    just behind the princess towers. ``attack_elixir`` is in ten-thousandths, as the observation
    holds the elixir.
    """

    ELIXIR = 10_000
    """One elixir in the observation's unit (ten-thousandths)."""

    ATTACK_TYPES = ("troop",)
    DEFEND_TYPES = ("troop", "building")

    def __init__(
        self,
        card_types: Mapping[int, str] | None = None,
        *,
        attack_elixir: int = 9 * ELIXIR,
        lane_cols: tuple[int, int] = (3, 14),
        bridge_row: int = 14,
        defend_row: int = 4,
    ):
        self.card_types = None if card_types is None else dict(card_types)
        self.attack_elixir = attack_elixir
        self.lane_cols = lane_cols
        self.bridge_row = bridge_row
        self.defend_row = defend_row
        self.schema: Schema | None = None

    def bind(self, vector: VectorBattle) -> ScriptedBot:
        """Reads the layout (and, unless given, the card types) from the vector the bot plays in;
        returns the bot."""
        if self.card_types is None:
            types = vector.card_types()
            if not types:
                raise ValueError(
                    "the vector has no tables to read the card types from: give card_types"
                )
            self.card_types = types
        self.schema = vector.schema
        return self

    def __call__(self, observation: np.ndarray, mask: np.ndarray, rng: np.random.Generator) -> int:
        s = self.schema
        if s is None:
            raise RuntimeError("the bot is not bound to a vector: call bind(vector) first")
        waiting = s.field(observation, "pending_count") > 0
        threat = self._deepest_threat(observation)
        if threat is not None and not waiting:
            lane = 0 if threat < s.width_units // 2 else 1
            return self._play(observation, mask, self.DEFEND_TYPES, lane, self.defend_row)
        elixir = s.field(observation, "elixir") - s.field(observation, "elixir_reserved")
        if elixir >= self.attack_elixir:
            left = s.field(observation, "enemy_left_hp")
            right = s.field(observation, "enemy_right_hp")
            lane = int(rng.integers(2)) if left == right else (0 if left < right else 1)
            return self._play(observation, mask, self.ATTACK_TYPES, lane, self.bridge_row)
        return s.wait

    def _deepest_threat(self, observation: np.ndarray) -> int | None:
        """The x of the enemy character deepest into the seat's half, or None when none crossed
        the river."""
        s = self.schema
        rows = s.entities(observation)
        enemy = (
            (rows[:, s.entity["kind"]] == s.kinds["character"])
            & (rows[:, s.entity["side"]] == 1)
            & ((rows[:, s.entity["flags"]] & s.flags["alive"]) != 0)
            & (rows[:, s.entity["y"]] < s.height_units // 2)
        )
        if not enemy.any():
            return None
        crossed = rows[enemy]
        return int(crossed[np.argmin(crossed[:, s.entity["y"]]), s.entity["x"]])

    def _play(
        self, observation: np.ndarray, mask: np.ndarray, types: tuple[str, ...], lane: int, row: int
    ) -> int:
        """The cheapest open card of the given types at the lane's tile on ``row``; else a wait."""
        s = self.schema
        hand = s.field(observation, "hand_card")
        costs = s.field(observation, "hand_cost")
        tiles = s.cols * s.rows
        candidates = []
        for slot in range(s.hand_slots):
            if self.card_types.get(int(hand[slot])) not in types:
                continue
            if not mask[1 + slot * tiles : 1 + (slot + 1) * tiles].any():
                continue
            candidates.append((int(costs[slot]), slot))
        if not candidates:
            return s.wait
        _, slot = min(candidates)
        return self._open_tile(mask, slot, self.lane_cols[lane], row)

    def _open_tile(self, mask: np.ndarray, slot: int, col: int, row: int) -> int:
        """The play of ``slot`` at tile ``(col, row)`` when the mask opens it, else at the open
        tile of the slot nearest to it (the lowest action of the nearest)."""
        s = self.schema
        action = s.play(slot, col, row)
        if mask[action]:
            return action
        tiles = s.cols * s.rows
        first = 1 + slot * tiles
        open_tiles = np.flatnonzero(mask[first : first + tiles])
        rows, cols = np.divmod(open_tiles, s.cols)
        nearest = np.argmin((cols - col) ** 2 + (rows - row) ** 2)
        return int(first + open_tiles[nearest])
