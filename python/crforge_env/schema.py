"""The observation, mask, action and info layout, read from the env module at start.

The layout is never typed twice: the sizes and field names come from the vector's metadata
(``VectorEnv.metadataJson()``), and the few codes the metadata does not list (the result, entity
kind and winner codes, the deck size, the arena's size) from the Java ``Schema`` class itself. The
package reads one schema version; an env module that writes another is refused at start.

The pure Python action helpers repeat the Java formulas so a policy can encode a play without a
JVM call; the tests hold them to the Java ones.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

import numpy as np

from ._jvm import java_class

SUPPORTED_VERSION = 3
"""The schema version this package reads."""

INT32_MIN = int(np.iinfo(np.int32).min)
INT32_MAX = int(np.iinfo(np.int32).max)

# The header and entity fields that hold a vocabulary id (-1 for none), and the enumerations whose
# range the schema fixes; every other field is a count, an amount or a coordinate.
_ID_HEADER_FIELDS = (
    "hand_card",
    "next_card",
    "deck",
    "enemy_seen",
    "pending_card",
    "ability_champion",
    "enemy_play_card",
    "enemy_play_cast",
    "queue",
)
_ID_ENTITY_FIELDS = ("row",)


@dataclass(frozen=True)
class Codes:
    """The codes the metadata does not list, read from the Java ``Schema`` class."""

    results: dict[str, int]
    kinds: dict[str, int]
    winner_none: int
    winner_draw: int
    deck_size: int
    width_units: int
    height_units: int
    wait: int
    slot_evolution: int
    slot_hero: int
    form_evolved: int
    form_hero: int
    no_state: int
    no_target: int

    @staticmethod
    def from_java() -> Codes:
        s = java_class("org.crforge.env.Schema")
        return Codes(
            results={
                "running": int(s.RUNNING),
                "won": int(s.WON),
                "lost": int(s.LOST),
                "draw": int(s.DRAW),
            },
            kinds={
                "character": int(s.KIND_CHARACTER),
                "tower": int(s.KIND_TOWER),
                "projectile": int(s.KIND_PROJECTILE),
                "area": int(s.KIND_AREA),
            },
            winner_none=int(s.WINNER_NONE),
            winner_draw=int(s.WINNER_DRAW),
            deck_size=int(s.DECK_SIZE),
            width_units=int(s.WIDTH_UNITS),
            height_units=int(s.HEIGHT_UNITS),
            wait=int(s.WAIT),
            slot_evolution=int(s.SLOT_EVOLUTION),
            slot_hero=int(s.SLOT_HERO),
            form_evolved=int(s.FORM_EVOLVED),
            form_hero=int(s.FORM_HERO),
            no_state=int(s.NO_STATE),
            no_target=int(s.NO_TARGET),
        )


class Schema:
    """One schema version's layout, from the vector's metadata (its ``schema`` object).

    - ``header[name]``: the indices of a header field in a seat's observation, in order (a field of
      several values, such as ``hand_card``, has one index per value)
    - ``entity[name]``: a field's index in an entity row (each has a name of its own: the buff
      slots are ``buff_0``, ``buff_ms_0``, ...)
    - ``state_names``: the names of the state numbers an entity row's ``state`` holds, a category
      (``no_state``, -1, for a projectile or an area effect)
    - ``scales``: the amounts' scales by name (``elixir_scale``, ``tile_units``, ``width_units``,
      ``height_units``, ``ms_per_tick``, ``percent``, ``max_state``)
    - ``info[name]``: a field's index in an environment's info record
    - ``flags[name]``: an entity flag's bit value
    - ``refusals[code]``: a refusal code's name
    - ``faults``: the fault kinds' names by the info record's number (``none`` first)
    """

    def __init__(self, metadata: Mapping[str, Any], codes: Codes):
        s = metadata["schema"]
        self.version = int(s["version"])
        if self.version != SUPPORTED_VERSION:
            raise RuntimeError(
                f"the env module writes schema version {self.version}, this package reads version "
                f"{SUPPORTED_VERSION}: install the package and the env module of one commit"
            )
        self.metadata = dict(s)
        self.seat_ints = int(s["seat_ints"])
        self.header_ints = int(s["header_ints"])
        self.entity_ints = int(s["entity_ints"])
        self.max_entities = int(s["max_entities"])
        self.actions = int(s["actions"])
        self.info_ints = int(s["info_ints"])
        self.cols = int(s["cols"])
        self.rows = int(s["rows"])
        self.tile_units = int(s["tile_units"])
        self.hand_slots = int(s["hand_slots"])
        self.ability_slots = int(s["ability_slots"])
        self.first_ability = int(s["first_ability"])
        self.ability_ints = int(s["ability_ints"])
        self.pending_rows = int(s["pending_rows"])
        self.enemy_play_rows = int(s["enemy_play_rows"])
        self.enemy_play_ints = int(s["enemy_play_ints"])
        self.queue_slots = int(s["queue_slots"])
        self.buff_slots = int(s["buff_slots"])
        self.state_names = tuple(s["state_names"])
        self.scales = {name: int(value) for name, value in s["scales"].items()}
        self.play_delay_ticks = int(s["play_delay_ticks"])
        self.header_fields = tuple(s["header_fields"])
        self.entity_fields = tuple(s["entity_fields"])
        self.info_fields = tuple(s["info_fields"])
        self.flag_names = tuple(s["flag_names"])
        self.fault_names = tuple(s["fault_names"])
        for name, fields, size in (
            ("header", self.header_fields, self.header_ints),
            ("entity", self.entity_fields, self.entity_ints),
            ("info", self.info_fields, self.info_ints),
        ):
            if len(fields) != size:
                raise RuntimeError(f"the metadata lists {len(fields)} {name} fields for {size}")
        if self.seat_ints != self.header_ints + self.max_entities * self.entity_ints:
            raise RuntimeError("the metadata's seat size is not its header and entity rows")
        if self.first_ability != 1 + self.hand_slots * self.cols * self.rows:
            raise RuntimeError("the metadata's first ability is not after the wait and every play")
        if self.actions != self.first_ability + self.ability_slots:
            raise RuntimeError("the metadata's action count is not the plays and the abilities")
        self.header: dict[str, tuple[int, ...]] = {}
        for index, field in enumerate(self.header_fields):
            if field != "reserved":
                self.header[field] = (*self.header.get(field, ()), index)
        self.entity = {f: i for i, f in enumerate(self.entity_fields) if f != "reserved"}
        self.info = {f: i for i, f in enumerate(self.info_fields)}
        self.flags = {f: 1 << bit for bit, f in enumerate(self.flag_names)}
        self.refusals = {code: n for code, n in enumerate(s["refusal_names"]) if n is not None}
        self.refusal_codes = {n: code for code, n in self.refusals.items()}
        self.results = dict(codes.results)
        self.kinds = dict(codes.kinds)
        self.winner_none = codes.winner_none
        self.winner_draw = codes.winner_draw
        self.deck_size = codes.deck_size
        self.width_units = codes.width_units
        self.height_units = codes.height_units
        self.wait = codes.wait
        self.slot_evolution = codes.slot_evolution
        self.slot_hero = codes.slot_hero
        self.form_evolved = codes.form_evolved
        self.form_hero = codes.form_hero
        self.no_state = codes.no_state
        self.no_target = codes.no_target

    # -- actions -------------------------------------------------------------------------------

    def play(self, slot: int, col: int, row: int) -> int:
        """The action that plays a hand slot's card at the centre of a tile of the seat's frame
        (``col`` 0 at the seat's left, ``row`` 0 at its own back line)."""
        if not (0 <= slot < self.hand_slots and 0 <= col < self.cols and 0 <= row < self.rows):
            raise ValueError(f"no action for slot {slot} at tile {col}, {row}")
        return 1 + slot * (self.cols * self.rows) + row * self.cols + col

    def decode(self, action: int) -> tuple[int, int, int]:
        """A play action's ``(slot, col, row)``."""
        if not (0 < action < self.first_ability):
            raise ValueError(f"no play action {action}")
        slot, rest = divmod(action - 1, self.cols * self.rows)
        row, col = divmod(rest, self.cols)
        return slot, col, row

    def ability(self, slot: int) -> int:
        """The action that taps the ability of one of the king's champion slots (0 or 1)."""
        if not 0 <= slot < self.ability_slots:
            raise ValueError(f"no ability action for champion slot {slot}")
        return self.first_ability + slot

    def is_ability(self, action: int) -> bool:
        """Whether an action is an ability action."""
        return self.first_ability <= action < self.actions

    def ability_slot(self, action: int) -> int:
        """The champion slot an ability action names."""
        if not self.is_ability(action):
            raise ValueError(f"no ability action {action}")
        return action - self.first_ability

    # -- reading -------------------------------------------------------------------------------

    def field(self, observation: np.ndarray, name: str) -> np.ndarray | int:
        """A header field of a seat's observation (or of a batch, on the last axis): an int for a
        field of one value, an array for a field of several."""
        indices = self.header[name]
        if len(indices) == 1:
            value = observation[..., indices[0]]
            return int(value) if np.ndim(value) == 0 else value
        return observation[..., list(indices)]

    def entities(self, observation: np.ndarray) -> np.ndarray:
        """A seat observation's entity rows that were written, ``(entity_count, entity_ints)``."""
        rows = observation[self.header_ints :].reshape(self.max_entities, self.entity_ints)
        return rows[: int(observation[self.header["entity_count"][0]])]

    def columns(self, observation: np.ndarray) -> dict[str, np.ndarray]:
        """A seat observation's written entity rows as one column per field, by field name."""
        rows = self.entities(observation)
        return {name: rows[:, index] for name, index in self.entity.items()}

    def observation_bounds(
        self, vocabulary_size: int, buff_count: int | None = None
    ) -> tuple[np.ndarray, np.ndarray]:
        """The lowest and highest value of each integer of a seat's observation, as int32 arrays.

        A vocabulary id field (the hand, the next card, the deck, the enemy's cards seen and
        played, the queue, the waiting commands' cards, a champion slot's champion, an entity's
        row) is between -1 (none) and the last id; an entity's buff slot between -1 and the last
        buff id when ``buff_count`` is given; an entity's state between -1 and the last state; the
        result, the seat, the overtime and pending marks, the deck's slot flags, the hand's and the
        enemy plays' forms, an entity's kind, side and flags are between 0 and their largest code;
        every other field (ticks, elixir, hit points, coordinates, codes of the match) is any
        int32. A cleared observation (all zeros) is within them.
        """
        low = np.full(self.seat_ints, INT32_MIN, dtype=np.int32)
        high = np.full(self.seat_ints, INT32_MAX, dtype=np.int32)

        def bound(indices, lo: int, hi: int) -> None:
            low[list(indices)] = lo
            high[list(indices)] = hi

        for name in _ID_HEADER_FIELDS:
            bound(self.header[name], -1, vocabulary_size - 1)
        bound(self.header["result"], 0, max(self.results.values()))
        bound(self.header["seat"], 0, 1)
        bound(self.header["overtime"], 0, 1)
        bound(self.header["hand_pending"], 0, 1)
        bound(self.header["deck_slot"], 0, self.slot_evolution | self.slot_hero)
        forms = max(self.form_evolved, self.form_hero)
        bound(self.header["hand_form"], 0, forms)
        bound(self.header["enemy_play_form"], 0, forms)
        rows = np.arange(self.max_entities) * self.entity_ints + self.header_ints
        for name in _ID_ENTITY_FIELDS:
            bound(rows + self.entity[name], -1, vocabulary_size - 1)
        if buff_count is not None:
            for b in range(self.buff_slots):
                bound(rows + self.entity[f"buff_{b}"], -1, buff_count - 1)
        bound(rows + self.entity["state"], self.no_state, len(self.state_names) - 1)
        bound(rows + self.entity["kind"], 0, max(self.kinds.values()))
        bound(rows + self.entity["side"], 0, 2)
        bound(rows + self.entity["flags"], 0, (1 << len(self.flag_names)) - 1)
        return low, high
