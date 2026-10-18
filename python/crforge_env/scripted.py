"""A scripted opponent: a few readable rules on what the seat's player sees.

:class:`ScriptedBot` is a policy of the same shape as :func:`random_opponent`
(``bot(observation, mask, rng) -> action``), so it plays the other seat of a
:class:`ClashGymEnv` or either seat in :func:`crforge_env.evaluate`. It reads the seat's
observation and mask only: its own elixir, hand and waiting plays, the enemy towers and the
characters of both sides on the field. Nothing hidden from the player is read.

The deck's win conditions are its attack cards: a troop whose unit attacks buildings only, and a
building whose range reaches an enemy princess tower from the bridge (a siege building), both read
from the tables (:class:`UnitFacts`). A win condition troop with ``tank_hitpoints`` or more is a
tank. A deck without a win condition attacks with its cheapest troop, as the bot did before it
knew them.

Each decision, in order (the first four only with no play of its own waiting):

1. **Finish a tower.** A spell in the hand whose tower damage takes an enemy tower's hit points:
   cast it on that tower. Such a spell, while it can finish a tower, is kept for it and never cast
   on characters.
2. **Spell a group.** The best cast of an open spell on the enemy characters it covers (a circle
   around the tile's centre, or a rolling spell's strip forwards from it): its worth is what it
   takes from each character it hits as a share of that character's maximum hit points or of the
   spell's damage, whichever is larger (1 for a whole kill of a character as sturdy as the spell's
   damage or more), summed. A spell with damage is cast when it hits ``spell_targets`` or more
   characters and its worth is at least ``spell_worth``; a spell whose damage the bot cannot read
   when it covers ``area_targets`` or more.
3. **Defend.** An enemy character on the seat's half of the field (past the river): play the
   cheapest open troop or building card that is not a win condition in the lane of the deepest
   such character, a troop on the defence row just behind the princess towers, a building in the
   middle of the seat's half on the lane's side, where it pulls the enemy troops off their lane.
4. **Support.** A win condition of its own on the field with no troop of its own beside it
   (within ``support_radius``) and the elixir at ``support_elixir``: the dearest open troop that
   is not a win condition, ``support_gap`` rows behind the foremost such unit (at the bridge once
   it crossed the river).
5. **Attack.** The elixir (less what the seat's waiting plays hold back) at the attack threshold,
   9 elixir by default, in the lane whose enemy princess tower has fewer hit points (``rng``
   breaks a tie): the cheapest open win condition, a tank on ``back_row``, the back line of its
   lane (it walks to the bridge while the elixir for its support comes in), any other at the
   bridge, on the last row of the seat's half; with none open, the cheapest open troop at the
   bridge.
6. **Wait** otherwise.

A card is open when the mask opens a tile of its hand slot (the mask carries the elixir, the
start cooldown and a play of the card already waiting). A troop or building is played on its tile
when the mask opens it, else on the nearest tile of that slot the mask opens; a spell only on the
tile it was aimed at. The bot never takes an action the mask closed.

The rules are deterministic: the same observations, masks and generator states give the same
actions, and ``rng`` is drawn from only to break a tie of the two lanes.
"""

from __future__ import annotations

from collections.abc import Mapping

import numpy as np

from .schema import Schema
from .vector import SpellFacts, UnitFacts, VectorBattle


class ScriptedBot:
    """The scripted policy; call :meth:`bind` with a vector before the first decision.

    ``card_types`` maps a hand card's vocabulary id to ``troop``, ``building`` or ``spell``,
    ``spell_facts`` a spell card's id to its :class:`SpellFacts` (a spell with none is never
    cast) and ``unit_facts`` a troop or building card's id to its :class:`UnitFacts` (a card with
    none is no win condition); left None, :meth:`bind` reads them from the vector's tables
    (:meth:`VectorBattle.card_types`, :meth:`VectorBattle.spell_facts`,
    :meth:`VectorBattle.unit_facts`). A synthetic vector has no tables, so it needs the card types
    given. A :class:`ClashGymEnv` binds its opponent itself, and
    :func:`crforge_env.evaluate` binds both policies.

    The geometry is the seat's frame of the standard arena, in tiles of the action grid:
    ``lane_cols`` the left and right lanes' columns (the bridges and princess towers),
    ``bridge_row`` the last row of the seat's half before the river, ``defend_row`` the row just
    behind the princess towers, ``building_cols`` and ``building_row`` the tiles in the middle of
    the seat's half a defending building takes for a threat in the left and the right lane,
    ``tower_row`` the row of the enemy princess towers: a building whose range is at least the
    distance from the bridge to that row is a siege building; ``back_row`` the row a tank is
    played on. ``attack_elixir`` and ``support_elixir`` are in ten-thousandths, as the observation
    holds the elixir, ``support_radius`` in game units and ``tank_hitpoints`` in hit points at the
    vector's card level. The spell and support thresholds are described in the module's rules.
    """

    ELIXIR = 10_000
    """One elixir in the observation's unit (ten-thousandths)."""

    ATTACK_TYPES = ("troop",)
    DEFEND_TYPES = ("troop", "building")

    def __init__(
        self,
        card_types: Mapping[int, str] | None = None,
        spell_facts: Mapping[int, SpellFacts] | None = None,
        unit_facts: Mapping[int, UnitFacts] | None = None,
        *,
        attack_elixir: int = 9 * ELIXIR,
        lane_cols: tuple[int, int] = (3, 14),
        bridge_row: int = 14,
        defend_row: int = 4,
        building_cols: tuple[int, int] = (8, 9),
        building_row: int = 10,
        tower_row: int = 25,
        back_row: int = 1,
        spell_targets: int = 2,
        spell_worth: float = 1.5,
        area_targets: int = 3,
        support_elixir: int = 5 * ELIXIR,
        support_gap: int = 2,
        support_radius: int = 4000,
        tank_hitpoints: int = 2500,
    ):
        self.card_types = None if card_types is None else dict(card_types)
        self.spell_facts = None if spell_facts is None else dict(spell_facts)
        self.unit_facts = None if unit_facts is None else dict(unit_facts)
        self.attack_elixir = attack_elixir
        self.lane_cols = lane_cols
        self.bridge_row = bridge_row
        self.defend_row = defend_row
        self.building_cols = building_cols
        self.building_row = building_row
        self.tower_row = tower_row
        self.back_row = back_row
        self.spell_targets = spell_targets
        self.spell_worth = spell_worth
        self.area_targets = area_targets
        self.support_elixir = support_elixir
        self.support_gap = support_gap
        self.support_radius = support_radius
        self.tank_hitpoints = tank_hitpoints
        self.schema: Schema | None = None
        self.siege_cards: frozenset[int] = frozenset()
        """the building cards whose range reaches an enemy princess tower from the bridge"""
        self.attack_cards: frozenset[int] = frozenset()
        """the deck cards the bot attacks with: its building-targeting troops and siege buildings"""
        self.tank_cards: frozenset[int] = frozenset()
        """the win condition troops of ``tank_hitpoints`` or more, played on the back line"""

    def bind(self, vector: VectorBattle) -> ScriptedBot:
        """Reads the layout (and, unless given, the card types, spell facts and unit facts) from the
        vector the bot plays in; returns the bot."""
        if self.card_types is None:
            types = vector.card_types()
            if not types:
                raise ValueError(
                    "the vector has no tables to read the card types from: give card_types"
                )
            self.card_types = types
        if self.spell_facts is None:
            self.spell_facts = vector.spell_facts()
        if self.unit_facts is None:
            self.unit_facts = vector.unit_facts()
        self.schema = schema = vector.schema
        # The offsets the rules read, looked up once: a decision is a few dozen numpy reads.
        self._spells = {
            card: fact
            for card, fact in self.spell_facts.items()
            if self.card_types.get(card) == "spell"
        }
        # The win conditions: the troops that attack buildings only, and the buildings that reach an
        # enemy princess tower from the centre of a bridge tile (the tower's centre, on its lane).
        reach = (self.tower_row - self.bridge_row) * schema.tile_units
        types = self.card_types
        self.siege_cards = frozenset(
            card
            for card, fact in self.unit_facts.items()
            if types.get(card) == "building" and fact.building and fact.range >= reach
        )
        self.attack_cards = self.siege_cards | frozenset(
            card
            for card, fact in self.unit_facts.items()
            if types.get(card) == "troop" and fact.buildings_only
        )
        self.tank_cards = frozenset(
            card
            for card in self.attack_cards - self.siege_cards
            if self.unit_facts[card].hitpoints >= self.tank_hitpoints
        )
        # The units of the win conditions, as the entity rows of the seat's own characters name
        # them: the support rule looks for them on the field, by vocabulary id in one lookup (the
        # last entry, False, stands for the -1 of an entity without a row).
        units = sorted({self.unit_facts[card].unit for card in self.attack_cards})
        self._attack_unit = np.zeros(max([vector.vocabulary_size, *units]) + 2, dtype=bool)
        self._attack_unit[units] = True
        self._hand_card = list(schema.header["hand_card"])
        self._hand_cost = list(schema.header["hand_cost"])
        self._enemy_towers_hp = [
            schema.header[name][0] for name in ("enemy_king_hp", "enemy_left_hp", "enemy_right_hp")
        ]
        return self

    def __call__(self, observation: np.ndarray, mask: np.ndarray, rng: np.random.Generator) -> int:
        s = self.schema
        if s is None:
            raise RuntimeError("the bot is not bound to a vector: call bind(vector) first")
        waiting = s.field(observation, "pending_count") > 0
        if not waiting:
            rows = s.entities(observation)
            e = s.entity
            # A hidden building and a trail underground are shown but out of a spell's reach.
            enemy = rows[
                (rows[:, e["kind"]] == s.kinds["character"])
                & (rows[:, e["side"]] == 1)
                & ((rows[:, e["flags"]] & s.flags["alive"]) != 0)
                & ((rows[:, e["flags"]] & (s.flags["hidden"] | s.flags["underground"])) == 0)
            ]
            spells = self._hand_spells(observation)
            if spells:
                action, kept = self._finish(observation, rows, spells, mask)
                if action is not None:
                    return action
                action = self._cast(
                    enemy, [spell for spell in spells if spell[0] not in kept], mask
                )
                if action is not None:
                    return action
            threat = self._deepest_threat(enemy)
            if threat is not None:
                lane = 0 if threat < s.width_units // 2 else 1
                building = (self.building_cols[lane], self.building_row)
                return self._play(
                    observation,
                    mask,
                    self.DEFEND_TYPES,
                    (self.lane_cols[lane], self.defend_row),
                    building,
                    exclude=self.attack_cards,
                )
            if self.attack_cards:
                action = self._support(observation, rows, mask)
                if action is not None:
                    return action
        elixir = s.field(observation, "elixir") - s.field(observation, "elixir_reserved")
        if elixir >= self.attack_elixir:
            left = s.field(observation, "enemy_left_hp")
            right = s.field(observation, "enemy_right_hp")
            lane = int(rng.integers(2)) if left == right else (0 if left < right else 1)
            tile = (self.lane_cols[lane], self.bridge_row)
            if self.attack_cards:
                slot = self._pick(observation, mask, self.DEFEND_TYPES, only=self.attack_cards)
                if slot is not None:
                    card = int(observation[self._hand_card[slot]])
                    row = self.back_row if card in self.tank_cards else self.bridge_row
                    return self._open_tile(mask, slot, tile[0], row)
            return self._play(observation, mask, self.ATTACK_TYPES, tile, tile)
        return s.wait

    # -- spells --------------------------------------------------------------------------------

    def _hand_spells(self, observation: np.ndarray):
        """The hand's spells the bot knows how to cast, as ``(slot, cost, facts)``, cheapest first;
        the slots the mask closes too, as a closed one may be kept for a tower."""
        hand = observation[self._hand_card].tolist()
        spells = []
        for slot, card in enumerate(hand):
            fact = self._spells.get(card)
            if fact is not None:
                spells.append((int(observation[self._hand_cost[slot]]), slot, fact))
        spells.sort(key=lambda spell: spell[:2])
        return [(slot, cost, fact) for cost, slot, fact in spells]

    def _tile(self, x: int, y: int) -> tuple[int, int]:
        """The action grid's tile holding a point of the seat's frame."""
        s = self.schema
        return (
            min(max(x // s.tile_units, 0), s.cols - 1),
            min(max(y // s.tile_units, 0), s.rows - 1),
        )

    def _finish(self, observation: np.ndarray, rows: np.ndarray, spells, mask: np.ndarray):
        """A cast that takes an enemy tower, or None, and the slots of the spells kept for one: a
        spell (not a rolling one) whose tower damage is at least an enemy tower's hit points."""
        s = self.schema
        e = s.entity
        reach = max((f.tower_damage for _, _, f in spells if not f.roll), default=0)
        standing = [hp for hp in observation[self._enemy_towers_hp].tolist() if hp > 0]
        if reach <= 0 or not standing or reach < min(standing):
            return None, ()  # no spell in the hand takes a tower: the common case, read cheaply
        towers = rows[
            (rows[:, e["kind"]] == s.kinds["tower"])
            & (rows[:, e["side"]] == 1)
            & ((rows[:, e["flags"]] & s.flags["alive"]) != 0)
            & (rows[:, e["hp"]] > 0)
        ]
        kept = set()
        for slot, _, fact in spells:
            if fact.roll or fact.tower_damage <= 0:
                continue
            for tower in towers[np.argsort(towers[:, e["hp"]], kind="stable")]:
                if fact.tower_damage < tower[e["hp"]] + tower[e["shield"]]:
                    continue
                kept.add(slot)
                action = s.play(slot, *self._tile(int(tower[e["x"]]), int(tower[e["y"]])))
                if mask[action]:
                    return action, kept
        return None, kept

    def _cast(self, enemy: np.ndarray, spells, mask: np.ndarray) -> int | None:
        """The best cast of a spell on the enemy characters (their entity rows), or None when no
        spell is worth it."""
        s = self.schema
        e = s.entity
        if not spells or len(enemy) < min(self.spell_targets, self.area_targets):
            return None
        x = enemy[:, e["x"]].astype(np.int64)
        y = enemy[:, e["y"]].astype(np.int64)
        # An enemy whose health bar the screen does not draw is taken as unhurt, with no shield.
        shown = enemy[:, e["hp"]] != s.unknown
        shield = np.where(shown, enemy[:, e["shield"]], 0)
        hp = np.where(shown, enemy[:, e["hp"]], enemy[:, e["max_hp"]])
        health = (hp + shield).astype(np.float64)
        whole = np.maximum(enemy[:, e["max_hp"]] + shield, 1).astype(np.float64)
        air = (enemy[:, e["flags"]] & s.flags["air"]) != 0
        tiles = s.tile_units
        tiles_of_slot = s.cols * s.rows
        best = None
        for slot, _, fact in spells:
            first = 1 + slot * tiles_of_slot
            if not mask[first : first + tiles_of_slot].any():
                continue  # not affordable, or its play already waiting
            hit = np.where(air, fact.hits_air, fact.hits_ground)
            n_hit = int(hit.sum())
            if fact.damage > 0:
                need = self.spell_worth
                # A hit counts the smaller of its share of the character and of the spell's
                # damage: a whole kill of a sturdy character, but only a part of one for a weak
                # character a big spell kills, so a big spell is not spent on a few cheap ones.
                taken = np.minimum(health, fact.damage)
                share = taken / np.maximum(whole, fact.damage)
                # The spell's best cast is worth no more than all it could hit together.
                if n_hit < self.spell_targets or share[hit].sum() < need:
                    continue
            elif n_hit < self.area_targets:
                continue
            # Every tile holding a character the spell hits is a candidate, at the tile's centre.
            cols = np.minimum(np.maximum(x[hit] // tiles, 0), s.cols - 1)
            tile_rows = np.minimum(np.maximum(y[hit] // tiles, 0), s.rows - 1)
            candidates = np.unique(tile_rows * s.cols + cols)
            cx = (candidates % s.cols) * tiles + tiles // 2
            cy = (candidates // s.cols) * tiles + tiles // 2
            dx = x[None, :] - cx[:, None]
            dy = y[None, :] - cy[:, None]
            if fact.roll:
                covered = (np.abs(dx) <= fact.radius) & (dy >= -fact.radius) & (dy <= fact.roll)
            else:
                covered = dx * dx + dy * dy <= fact.radius * fact.radius
            covered &= hit[None, :]
            count = covered.sum(axis=1)
            if fact.damage > 0:
                worths = np.where(covered, share[None, :], 0.0)
                if fact.max_targets:
                    worths = -np.sort(-worths, axis=1)[:, : fact.max_targets]
                    count = np.minimum(count, fact.max_targets)
                worth = worths.sum(axis=1)
                good = (count >= self.spell_targets) & (worth >= need)
                margin = worth - need
            else:
                good = count >= self.area_targets
                margin = (count - self.area_targets).astype(np.float64)
            for k in np.flatnonzero(good):
                action = s.play(slot, int(candidates[k] % s.cols), int(candidates[k] // s.cols))
                if not mask[action]:
                    continue
                # The best margin; a tie keeps the cheaper spell and then the lower tile.
                if best is None or margin[k] > best[0]:
                    best = (float(margin[k]), action)
        return None if best is None else best[1]

    # -- troops and buildings ------------------------------------------------------------------

    def _deepest_threat(self, enemy: np.ndarray) -> int | None:
        """The x of the enemy character (of their entity rows) deepest into the seat's half, or
        None when none crossed the river."""
        s = self.schema
        if len(enemy) == 0:
            return None
        y = enemy[:, s.entity["y"]]
        deepest = int(np.argmin(y))
        if y[deepest] >= s.height_units // 2:
            return None
        return int(enemy[deepest, s.entity["x"]])

    def _support(self, observation: np.ndarray, rows: np.ndarray, mask: np.ndarray) -> int | None:
        """A support play behind the foremost win condition of the seat's own on the field, or
        None: none on the field, a troop of its own already beside it, short of the support elixir
        or no support card open."""
        s = self.schema
        e = s.entity
        elixir = s.field(observation, "elixir") - s.field(observation, "elixir_reserved")
        if elixir < self.support_elixir:
            return None
        own = rows[
            (rows[:, e["kind"]] == s.kinds["character"])
            & (rows[:, e["side"]] == 0)
            & ((rows[:, e["flags"]] & s.flags["alive"]) != 0)
        ]
        leads = self._attack_unit[own[:, e["row"]]]
        if not leads.any():
            return None
        lead = own[leads][np.argmax(own[leads][:, e["y"]])]
        x, y = int(lead[e["x"]]), int(lead[e["y"]])
        escort = own[~leads & ((own[:, e["flags"]] & s.flags["building"]) == 0)]
        if len(escort):
            dx = escort[:, e["x"]].astype(np.int64) - x
            dy = escort[:, e["y"]].astype(np.int64) - y
            if (dx * dx + dy * dy <= self.support_radius * self.support_radius).any():
                return None
        slot = self._pick(
            observation, mask, self.ATTACK_TYPES, exclude=self.attack_cards, dearest=True
        )
        if slot is None:
            return None
        col, row = self._tile(x, y)
        return self._open_tile(
            mask, slot, col, max(min(row - self.support_gap, self.bridge_row), 0)
        )

    def _pick(
        self,
        observation: np.ndarray,
        mask: np.ndarray,
        types: tuple[str, ...],
        *,
        only: frozenset[int] | None = None,
        exclude: frozenset[int] = frozenset(),
        dearest: bool = False,
    ) -> int | None:
        """The hand slot of the cheapest (or dearest) open card of the given types, of ``only``
        when given and never of ``exclude``; a tie keeps the lower slot. None when none is open."""
        s = self.schema
        hand = observation[self._hand_card].tolist()
        costs = observation[self._hand_cost].tolist()
        tiles = s.cols * s.rows
        best = None
        for slot, card in enumerate(hand):
            if self.card_types.get(card) not in types or card in exclude:
                continue
            if only is not None and card not in only:
                continue
            if not mask[1 + slot * tiles : 1 + (slot + 1) * tiles].any():
                continue
            key = -costs[slot] if dearest else costs[slot]
            if best is None or key < best[0]:
                best = (key, slot)
        return None if best is None else best[1]

    def _play(
        self,
        observation: np.ndarray,
        mask: np.ndarray,
        types: tuple[str, ...],
        tile: tuple[int, int],
        building_tile: tuple[int, int],
        *,
        exclude: frozenset[int] = frozenset(),
    ) -> int:
        """The cheapest open card of the given types (never of ``exclude``), a troop at ``tile``
        and a building at ``building_tile`` (each as ``(col, row)``); else a wait."""
        slot = self._pick(observation, mask, types, exclude=exclude)
        if slot is None:
            return self.schema.wait
        card = int(observation[self._hand_card[slot]])
        col, row = building_tile if self.card_types.get(card) == "building" else tile
        return self._open_tile(mask, slot, col, row)

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
