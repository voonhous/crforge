"""The scripted bot and the evaluation helper.

The synthetic tests give the bot hand-written card types and costs (synthetic rows): nothing here
hard-codes game data. The Ladder tests read the card types from the tables and the decks are card
row names of the tables, as in the other Ladder tests.
"""

import numpy as np
import pytest

from crforge_env import (
    ClashGymEnv,
    ScriptedBot,
    SpellFacts,
    UnitFacts,
    VectorBattle,
    evaluate,
    random_opponent,
    wait_opponent,
)
from tests.decks import BLUE, RED

SYNTHETIC_IDS = 512
"""The synthetic environment draws its ids below this (``SyntheticEnv.VOCABULARY_SIZE``)."""

# Synthetic card types: even ids troops, ids 1 mod 4 spells, ids 3 mod 4 buildings.
SYNTHETIC_TYPES = {
    i: ("troop" if i % 2 == 0 else "spell" if i % 4 == 1 else "building")
    for i in range(SYNTHETIC_IDS)
}

TROOP, CHEAP_TROOP, SPELL, BUILDING = 10, 12, 21, 23
"""Hand-written cards of the crafted observations, typed by SYNTHETIC_TYPES."""

BIG_SPELL, AREA_SPELL, GROUND_SPELL, ROLLING_SPELL = 25, 29, 33, 37
"""More hand-written spells (ids 1 mod 4) of the crafted observations."""

SPELL_FACTS = {
    SPELL: SpellFacts(radius=1500, damage=200, tower_damage=50),
    BIG_SPELL: SpellFacts(radius=1500, damage=900, tower_damage=300),
    AREA_SPELL: SpellFacts(radius=1500),
    GROUND_SPELL: SpellFacts(radius=1500, damage=200, tower_damage=50, hits_air=False),
    ROLLING_SPELL: SpellFacts(radius=1000, damage=200, tower_damage=50, hits_air=False, roll=6000),
}
"""Hand-written spell facts of the synthetic spells: synthetic rows, no game data. Every other
spell id has none, so the bot never casts it."""

WIN_CONDITION, CHEAP_WIN_CONDITION, TANK, SIEGE, SHORT_BUILDING = 14, 16, 18, 27, 31
"""Hand-written troops (even ids) that attack buildings only, one of them a tank, a building
(3 mod 4) whose range reaches the enemy princess tower from the bridge and one whose range does
not."""

TANK_HITPOINTS = 3000
"""The tank threshold the tests give the bot."""

TROOP_UNIT, CHEAP_TROOP_UNIT, WIN_CONDITION_UNIT = 110, 112, 114
"""The vocabulary ids of the units the hand-written cards place (synthetic rows)."""

UNIT_FACTS = {
    TROOP: UnitFacts(unit=TROOP_UNIT, range=1200),
    CHEAP_TROOP: UnitFacts(unit=CHEAP_TROOP_UNIT, range=1200),
    WIN_CONDITION: UnitFacts(
        unit=WIN_CONDITION_UNIT, range=1200, buildings_only=True, hitpoints=TANK_HITPOINTS - 1
    ),
    CHEAP_WIN_CONDITION: UnitFacts(unit=116, range=800, buildings_only=True, hitpoints=500),
    TANK: UnitFacts(unit=118, range=1200, buildings_only=True, hitpoints=TANK_HITPOINTS),
    BUILDING: UnitFacts(unit=123, range=5500, building=True),
    SIEGE: UnitFacts(unit=127, range=12_000, building=True),
    SHORT_BUILDING: UnitFacts(unit=131, range=6000, building=True),
}
"""Hand-written unit facts of the synthetic troops and buildings: synthetic rows, no game data."""


@pytest.fixture(scope="module")
def synthetic(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as vector:
        yield vector


def crafted(
    schema,
    *,
    elixir,
    hand,
    costs,
    enemies=(),
    pending=0,
    enemy_left=1,
    enemy_right=1,
    towers=(),
    own=(),
):
    """A seat observation written by hand: the elixir in ten-thousandths, the hand's card ids and
    costs, the enemy characters as ``(x, y)`` or ``(x, y, hp, max_hp, air)`` in the seat's frame
    (100 of 100 hit points on the ground by default), the enemy princess towers' hit points,
    enemy tower entities as ``(x, y, hp)`` and the seat's own characters as ``(x, y, row)``, the
    row the vocabulary id of the unit."""
    o = np.zeros(schema.seat_ints, dtype=np.int32)

    def put(name, values):
        o[list(schema.header[name])] = values

    put("elixir", elixir)
    put("hand_card", hand)
    put("hand_cost", costs)
    put("pending_count", pending)
    put("enemy_left_hp", enemy_left)
    put("enemy_right_hp", enemy_right)
    put("entity_count", len(enemies) + len(towers) + len(own))
    rows = [
        ("character", 1, 0, *enemy)
        if len(enemy) == 5
        else ("character", 1, 0, *enemy, 100, 100, False)
        for enemy in enemies
    ]
    rows += [("tower", 1, 0, x, y, hp, hp, False) for x, y, hp in towers]
    rows += [("character", 0, row, x, y, 100, 100, False) for x, y, row in own]
    for k, (kind, side, row, x, y, hp, max_hp, air) in enumerate(rows):
        at = schema.header_ints + k * schema.entity_ints
        o[at + schema.entity["kind"]] = schema.kinds[kind]
        o[at + schema.entity["side"]] = side
        o[at + schema.entity["row"]] = row
        o[at + schema.entity["x"]] = x
        o[at + schema.entity["y"]] = y
        o[at + schema.entity["hp"]] = hp
        o[at + schema.entity["max_hp"]] = max_hp
        flags = schema.flags["alive"] | (schema.flags["air"] if air else 0)
        o[at + schema.entity["flags"]] = flags
    return o


def open_mask(schema, slots=None):
    """A mask with the wait and every tile of the given hand slots (all slots by default) open."""
    mask = np.zeros(schema.actions, dtype=np.uint8)
    mask[schema.wait] = 1
    tiles = schema.cols * schema.rows
    for slot in range(schema.hand_slots) if slots is None else slots:
        mask[1 + slot * tiles : 1 + (slot + 1) * tiles] = 1
    return mask


def bound_bot(vector, **kwargs):
    kwargs.setdefault("spell_facts", SPELL_FACTS)
    kwargs.setdefault("tank_hitpoints", TANK_HITPOINTS)
    bot = ScriptedBot(card_types=SYNTHETIC_TYPES, **kwargs)
    bot.bind(vector)
    return bot


# -- the rules, on crafted observations ----------------------------------------------------------


def test_with_full_elixir_it_plays_the_cheapest_troop_at_the_bridge_of_the_weaker_lane(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    full = 10 * bot.ELIXIR
    hand = [SPELL, TROOP, CHEAP_TROOP, BUILDING]
    # The spell is the cheapest and the building is as cheap as the cheap troop: troops only.
    o = crafted(s, elixir=full, hand=hand, costs=[1, 4, 2, 2], enemy_left=500, enemy_right=900)
    slot, col, row = s.decode(bot(o, open_mask(s), rng))
    assert slot == 2
    assert (col, row) == (bot.lane_cols[0], bot.bridge_row)
    # The right princess tower weaker: the right lane.
    o = crafted(s, elixir=full, hand=hand, costs=[1, 4, 2, 2], enemy_left=900, enemy_right=500)
    assert s.decode(bot(o, open_mask(s), rng)) == (2, bot.lane_cols[1], bot.bridge_row)


def test_it_waits_below_the_attack_elixir_with_no_threat(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    o = crafted(s, elixir=bot.attack_elixir - 1, hand=[TROOP] * 4, costs=[3] * 4)
    assert bot(o, open_mask(s), np.random.default_rng(0)) == s.wait


def test_it_defends_the_threatened_lane_with_a_troop_or_building(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    own_half = s.height_units // 2 - 2000
    enemy_half = s.height_units // 2 + 2000
    hand = [SPELL, TROOP, BUILDING, TROOP]
    costs = [1, 3, 2, 4]
    elixir = 3 * bot.ELIXIR
    # An enemy on its own half is no threat: wait.
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, enemies=[(3000, enemy_half)])
    assert bot(o, open_mask(s), rng) == s.wait
    # Across the river on the right: the cheapest troop or building, here the building, in the
    # middle of the seat's half on the right lane's side, where it pulls the enemy off its lane.
    right = s.width_units - 3000
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, enemies=[(right, own_half)])
    assert s.decode(bot(o, open_mask(s), rng)) == (2, bot.building_cols[1], bot.building_row)
    # The deepest of two threats picks the lane.
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, enemies=[(right, own_half), (3000, 4000)])
    assert s.decode(bot(o, open_mask(s), rng)) == (2, bot.building_cols[0], bot.building_row)
    # A troop the cheapest: the lane's defence tile, just behind the princess towers.
    o = crafted(s, elixir=elixir, hand=hand, costs=[1, 3, 4, 2], enemies=[(right, own_half)])
    assert s.decode(bot(o, open_mask(s), rng)) == (3, bot.lane_cols[1], bot.defend_row)
    # A play of its own still waiting: no second defence.
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, enemies=[(right, own_half)], pending=1)
    assert bot(o, open_mask(s), rng) == s.wait


def test_it_never_plays_a_slot_the_mask_closed_and_moves_to_the_nearest_open_tile(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    full = 10 * bot.ELIXIR
    o = crafted(s, elixir=full, hand=[TROOP, CHEAP_TROOP, TROOP, TROOP], costs=[4, 2, 3, 5])
    # The cheapest troop's slot closed (not affordable, waiting, ...): the next cheapest.
    assert s.decode(bot(o, open_mask(s, slots=[0, 2, 3]), rng))[0] == 2
    # Only one tile of slot 3 open: that tile.
    mask = open_mask(s, slots=[])
    mask[s.play(3, 0, 0)] = 1
    assert bot(o, mask, rng) == s.play(3, 0, 0)
    # Nothing open but the wait.
    assert bot(o, open_mask(s, slots=[]), rng) == s.wait


# A row of three enemy characters on the enemy's half, one tile apart, the middle one at the centre
# of tile (5, 20): no threat to defend, and below the attack elixir the bot would otherwise wait.
ROW_OF_THREE = [(4500, 20500), (5500, 20500), (6500, 20500)]


def spell_hand(spell=SPELL):
    """A hand of the spell in slot 1 and troops, the spell the cheapest card."""
    return dict(
        hand=[TROOP, spell, TROOP, TROOP], costs=[3, 2, 3, 3], elixir=5 * ScriptedBot.ELIXIR
    )


def test_a_group_of_enemies_draws_a_spell_on_its_centre(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    o = crafted(s, enemies=ROW_OF_THREE, **spell_hand())
    # From the middle tile the spell's circle covers all three; from an end tile two.
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 20)
    # Without the spell's facts the bot never casts it: it waits.
    blind = bound_bot(synthetic, spell_facts={})
    assert blind(o, open_mask(s), rng) == s.wait


def test_a_lone_weak_unit_or_a_scratch_on_a_group_draws_no_spell(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    # One character, however weak: below the spell's targets.
    o = crafted(s, enemies=[(5500, 20500, 20, 100, False)], **spell_hand())
    assert bot(o, open_mask(s), rng) == s.wait
    # Three characters the spell only scratches (200 of 1000 hit points each): not worth it.
    sturdy = [(x, y, 1000, 1000, False) for x, y in ROW_OF_THREE]
    o = crafted(s, enemies=sturdy, **spell_hand())
    assert bot(o, open_mask(s), rng) == s.wait
    # Three nearly dead: the spell takes what is left of each, a share of its whole (0.15 each),
    # still not worth it; twelve of them are.
    weak = [(x, y, 150, 1000, False) for x, y in ROW_OF_THREE]
    o = crafted(s, enemies=weak, **spell_hand())
    assert bot(o, open_mask(s), rng) == s.wait
    o = crafted(s, enemies=weak * 4, **spell_hand())
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 20)
    # A big spell on three weak characters: each kill counts only what it takes of the spell's
    # damage (100 of 900), so the big spell is kept where the small one is cast.
    o = crafted(s, enemies=ROW_OF_THREE, **spell_hand(BIG_SPELL))
    assert bot(o, open_mask(s), rng) == s.wait
    o = crafted(s, enemies=ROW_OF_THREE, **spell_hand(SPELL))
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 20)


def test_an_unaffordable_spell_or_a_closed_tile_is_not_cast(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    o = crafted(s, enemies=ROW_OF_THREE, **spell_hand())
    # The mask closes the spell's slot (short of elixir, or its play already waiting): no cast.
    assert bot(o, open_mask(s, slots=[0, 2, 3]), rng) == s.wait
    # The slot open but not at the group's tiles: no cast elsewhere.
    mask = open_mask(s)
    for col, row in ((4, 20), (5, 20), (6, 20)):
        mask[s.play(1, col, row)] = 0
    assert bot(o, mask, rng) == s.wait
    # A play of its own waiting: no second cast.
    o = crafted(s, enemies=ROW_OF_THREE, pending=1, **spell_hand())
    assert bot(o, open_mask(s), rng) == s.wait


def test_a_spell_that_takes_a_tower_is_cast_on_it_and_kept_for_it(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    tower = (14500, 25500)
    # The tower has fewer hit points than the spell's tower damage: the spell goes on the tower,
    # before the group.
    o = crafted(s, enemies=ROW_OF_THREE, towers=[(*tower, 50)], **spell_hand())
    assert bot(o, open_mask(s), rng) == s.play(1, 14, 25)
    # With more hit points the group comes first.
    o = crafted(s, enemies=ROW_OF_THREE, towers=[(*tower, 51)], **spell_hand())
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 20)
    # The tower spell not affordable yet: it is kept, and the other spell takes the group.
    hand = dict(hand=[TROOP, SPELL, BIG_SPELL, TROOP], costs=[3, 2, 6, 3], elixir=3 * bot.ELIXIR)
    weak = [(x, y, 150, 150, False) for x, y in ROW_OF_THREE]
    o = crafted(s, enemies=weak, towers=[(*tower, 300)], **hand)
    assert bot(o, open_mask(s, slots=[0, 1, 3]), rng) == s.play(1, 5, 20)
    # The spell that could take it is never spent on the group.
    o = crafted(s, enemies=weak, towers=[(*tower, 50)], **hand)
    mask = open_mask(s, slots=[0, 3])
    mask[s.play(1, 5, 20)] = 1
    assert bot(o, mask, rng) == s.wait


def test_a_spell_without_damage_needs_more_targets(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    o = crafted(s, enemies=ROW_OF_THREE[:2], **spell_hand(AREA_SPELL))
    assert bot(o, open_mask(s), rng) == s.wait
    o = crafted(s, enemies=ROW_OF_THREE, **spell_hand(AREA_SPELL))
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 20)


def test_a_ground_spell_counts_no_air_target_and_a_rolling_spell_hits_ahead(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic)
    rng = np.random.default_rng(0)
    flying = [(x, y, 100, 100, True) for x, y in ROW_OF_THREE]
    o = crafted(s, enemies=flying, **spell_hand(GROUND_SPELL))
    assert bot(o, open_mask(s), rng) == s.wait
    o = crafted(s, enemies=flying, **spell_hand(SPELL))
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 20)
    # A column of three, two tiles apart along the length: a rolling spell cast on the nearest
    # rolls over all three; one cast further on misses those behind it.
    column = [(5500, 18500), (5500, 20500), (5500, 22500)]
    o = crafted(s, enemies=column, **spell_hand(ROLLING_SPELL))
    assert bot(o, open_mask(s), rng) == s.play(1, 5, 18)


# -- the win condition ---------------------------------------------------------------------------


def test_a_deck_with_a_building_targeting_troop_attacks_with_it(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic, unit_facts=UNIT_FACTS)
    rng = np.random.default_rng(0)
    full = 10 * bot.ELIXIR
    # A cheaper troop in the hand, but the win condition goes to the bridge of the weaker lane.
    hand = [CHEAP_TROOP, WIN_CONDITION, SPELL, TROOP]
    o = crafted(s, elixir=full, hand=hand, costs=[2, 5, 1, 3], enemy_left=500, enemy_right=900)
    assert s.decode(bot(o, open_mask(s), rng)) == (1, bot.lane_cols[0], bot.bridge_row)
    # Two win conditions: the cheaper.
    hand = [WIN_CONDITION, CHEAP_TROOP, CHEAP_WIN_CONDITION, TROOP]
    o = crafted(s, elixir=full, hand=hand, costs=[5, 2, 4, 3], enemy_left=900, enemy_right=500)
    assert s.decode(bot(o, open_mask(s), rng)) == (2, bot.lane_cols[1], bot.bridge_row)
    # The win condition closed by the mask: the cheapest troop, as without one.
    o = crafted(s, elixir=full, hand=hand, costs=[5, 2, 4, 3], enemy_left=900, enemy_right=500)
    assert s.decode(bot(o, open_mask(s, slots=[1, 3]), rng))[0] == 1


def test_a_deck_without_a_win_condition_keeps_the_cheapest_troop_attack(synthetic):
    s = synthetic.schema
    rng = np.random.default_rng(0)
    full = 10 * ScriptedBot.ELIXIR
    hand = [TROOP, CHEAP_TROOP, SPELL, SHORT_BUILDING]
    bot = bound_bot(synthetic, unit_facts=UNIT_FACTS)
    o = crafted(s, elixir=full, hand=hand, costs=[3, 2, 1, 2], enemy_left=500, enemy_right=900)
    assert s.decode(bot(o, open_mask(s), rng)) == (1, bot.lane_cols[0], bot.bridge_row)
    # With no unit facts a building-targeting troop is a troop like any other: the cheapest.
    blind = bound_bot(synthetic, unit_facts={})
    hand = [WIN_CONDITION, CHEAP_TROOP, SPELL, TROOP]
    o = crafted(s, elixir=full, hand=hand, costs=[5, 2, 1, 3], enemy_left=500, enemy_right=900)
    assert s.decode(blind(o, open_mask(s), rng))[0] == 1


def test_a_siege_building_that_reaches_the_tower_goes_to_the_bridge(synthetic):
    s = synthetic.schema
    rng = np.random.default_rng(0)
    full = 10 * ScriptedBot.ELIXIR
    bot = bound_bot(synthetic, unit_facts=UNIT_FACTS)
    assert bot.siege_cards == {SIEGE}
    assert bot.attack_cards == {WIN_CONDITION, CHEAP_WIN_CONDITION, TANK, SIEGE}
    assert bot.tank_cards == {TANK}
    hand = [CHEAP_TROOP, SIEGE, SHORT_BUILDING, SPELL]
    o = crafted(s, elixir=full, hand=hand, costs=[2, 6, 3, 1], enemy_left=900, enemy_right=500)
    assert s.decode(bot(o, open_mask(s), rng)) == (1, bot.lane_cols[1], bot.bridge_row)
    # Its reach is from the bridge to the enemy princess towers' row: towers further away, and
    # the building is no siege building; the cheapest troop attacks.
    far = bound_bot(synthetic, unit_facts=UNIT_FACTS, tower_row=bot.bridge_row + 13)
    assert far.siege_cards == set()
    assert s.decode(far(o, open_mask(s), rng)) == (0, bot.lane_cols[1], bot.bridge_row)


def test_a_tank_goes_to_the_back_line_of_its_lane(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic, unit_facts=UNIT_FACTS)
    rng = np.random.default_rng(0)
    full = 10 * bot.ELIXIR
    hand = [CHEAP_TROOP, TANK, SPELL, TROOP]
    o = crafted(s, elixir=full, hand=hand, costs=[2, 5, 1, 3], enemy_left=900, enemy_right=500)
    assert s.decode(bot(o, open_mask(s), rng)) == (1, bot.lane_cols[1], bot.back_row)
    # A win condition short of the tank's hit points goes to the bridge.
    hand = [CHEAP_TROOP, WIN_CONDITION, SPELL, TROOP]
    o = crafted(s, elixir=full, hand=hand, costs=[2, 5, 1, 3], enemy_left=900, enemy_right=500)
    assert s.decode(bot(o, open_mask(s), rng)) == (1, bot.lane_cols[1], bot.bridge_row)


def test_it_keeps_its_win_conditions_out_of_defence(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic, unit_facts=UNIT_FACTS)
    rng = np.random.default_rng(0)
    threat = [(3000, s.height_units // 2 - 2000)]
    elixir = 6 * bot.ELIXIR
    hand = [CHEAP_WIN_CONDITION, TROOP, SIEGE, SPELL]
    o = crafted(s, elixir=elixir, hand=hand, costs=[2, 3, 2, 1], enemies=threat)
    assert s.decode(bot(o, open_mask(s), rng)) == (1, bot.lane_cols[0], bot.defend_row)
    # Only its win conditions open: no defence, it waits.
    assert bot(o, open_mask(s, slots=[0, 2]), rng) == s.wait


def test_it_supports_its_win_condition_behind_it(synthetic):
    s = synthetic.schema
    bot = bound_bot(synthetic, unit_facts=UNIT_FACTS)
    rng = np.random.default_rng(0)
    elixir = bot.support_elixir
    hand = [TROOP, CHEAP_TROOP, WIN_CONDITION, SPELL]
    costs = [3, 2, 5, 1]
    gap = bot.support_gap
    # Its win condition walking up its own half on the left: the dearest other troop behind it.
    lead = (3500, 12500, WIN_CONDITION_UNIT)
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, own=[lead])
    assert s.decode(bot(o, open_mask(s), rng)) == (0, 3, 12 - gap)
    # The dearest closed by the mask (short of elixir): the next.
    assert s.decode(bot(o, open_mask(s, slots=[1, 2, 3]), rng)) == (1, 3, 12 - gap)
    # Past the river: at the bridge, as the seat cannot play beyond it.
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, own=[(14500, 20500, WIN_CONDITION_UNIT)])
    assert s.decode(bot(o, open_mask(s), rng)) == (0, 14, bot.bridge_row)
    # A troop of its own already beside it: no second support.
    escort = (3500, 11000, TROOP_UNIT)
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, own=[lead, escort])
    assert bot(o, open_mask(s), rng) == s.wait
    # Short of the support elixir, or no win condition of its own on the field: it waits.
    o = crafted(s, elixir=elixir - 1, hand=hand, costs=costs, own=[lead])
    assert bot(o, open_mask(s), rng) == s.wait
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, own=[(3500, 12500, TROOP_UNIT)])
    assert bot(o, open_mask(s), rng) == s.wait


def test_an_unbound_bot_refuses_to_play(synthetic):
    with pytest.raises(RuntimeError, match="bind"):
        ScriptedBot(card_types=SYNTHETIC_TYPES)(
            np.zeros(synthetic.schema.seat_ints, dtype=np.int32),
            open_mask(synthetic.schema),
            np.random.default_rng(0),
        )


def test_a_synthetic_vector_has_no_card_types(synthetic):
    assert synthetic.card_types() == {}
    with pytest.raises(ValueError, match="card_types"):
        ScriptedBot().bind(synthetic)


# -- on synthetic environments ---------------------------------------------------------------------


EVERY_SPELL_FACTS = {
    i: SpellFacts(radius=40_000) for i, kind in SYNTHETIC_TYPES.items() if kind == "spell"
}
"""Hand-written facts for every synthetic spell id, a spell without damage that covers the whole
field, so a synthetic battle's random hands and fields draw casts."""


EVERY_UNIT_FACTS = {
    i: UnitFacts(unit=i, range=12_000 if kind == "building" else 1000, buildings_only=i % 8 == 0)
    for i, kind in SYNTHETIC_TYPES.items()
    if kind != "spell"
}
"""Hand-written facts for every synthetic troop and building: one troop in four attacks buildings
only, every building reaches the enemy towers, and each places the unit of its own id, so a
synthetic battle's random hands and fields draw win conditions, siege buildings and support."""


def synthetic_actions(install, seed, spell_facts=SPELL_FACTS, unit_facts=None):
    """The bot's actions for both seats over 200 decisions of a synthetic battle, with its masks."""
    with VectorBattle(1, 1, install=install, engine="synthetic", synthetic_episode_steps=200) as v:
        bot = bound_bot(v, spell_facts=spell_facts, unit_facts=unit_facts)
        rngs = [np.random.default_rng([seed, seat]) for seat in range(2)]
        v.reset([seed], BLUE, RED)
        played = []
        for _ in range(200):
            masks = v.masks[0].copy()
            actions = [
                bot(v.observations[0, seat], v.masks[0, seat], rngs[seat]) for seat in (0, 1)
            ]
            played.append((actions, masks))
            v.step(np.array([actions]))
        return played


def test_the_same_seed_gives_the_same_actions_and_no_closed_action_is_taken(install):
    first = synthetic_actions(install, 7)
    second = synthetic_actions(install, 7)
    assert [a for a, _ in first] == [a for a, _ in second]
    plays = 0
    for actions, masks in first:
        for seat, action in enumerate(actions):
            assert masks[seat, action] == 1
            plays += action != 0
    assert plays > 0


def test_with_spell_facts_it_casts_spells_and_stays_deterministic(install):
    first = synthetic_actions(install, 11, EVERY_SPELL_FACTS)
    second = synthetic_actions(install, 11, EVERY_SPELL_FACTS)
    assert [a for a, _ in first] == [a for a, _ in second]
    with VectorBattle(1, 1, install=install, engine="synthetic", synthetic_episode_steps=200) as v:
        bot = bound_bot(v, spell_facts=EVERY_SPELL_FACTS)
        s = v.schema
        rngs = [np.random.default_rng([11, seat]) for seat in range(2)]
        v.reset([11], BLUE, RED)
        cast = 0
        for _ in range(200):
            actions = [
                bot(v.observations[0, seat], v.masks[0, seat], rngs[seat]) for seat in (0, 1)
            ]
            for seat, action in enumerate(actions):
                assert v.masks[0, seat, action] == 1
                if action:
                    card = int(s.field(v.observations[0, seat], "hand_card")[s.decode(action)[0]])
                    cast += card in EVERY_SPELL_FACTS
            v.step(np.array([actions]))
        assert cast > 0


def test_with_unit_facts_it_plays_its_win_conditions_and_stays_deterministic(install):
    first = synthetic_actions(install, 13, unit_facts=EVERY_UNIT_FACTS)
    second = synthetic_actions(install, 13, unit_facts=EVERY_UNIT_FACTS)
    assert [a for a, _ in first] == [a for a, _ in second]
    assert [a for a, _ in first] != [a for a, _ in synthetic_actions(install, 13)]
    for actions, masks in first:
        for seat, action in enumerate(actions):
            assert masks[seat, action] == 1


def test_the_bot_plays_no_spell_it_has_no_facts_for(install):
    with VectorBattle(1, 1, install=install, engine="synthetic", synthetic_episode_steps=300) as v:
        bot = bound_bot(v, spell_facts={})
        rng = np.random.default_rng(3)
        v.reset([3], BLUE, RED)
        s = v.schema
        kinds = set()
        for _ in range(300):
            o = v.observations[0, 0]
            action = bot(o, v.masks[0, 0], rng)
            if action:
                kinds.add(SYNTHETIC_TYPES[int(s.field(o, "hand_card")[s.decode(action)[0]])])
            v.step(np.array([[action, 0]]))
        assert "troop" in kinds and kinds <= {"troop", "building"}


def test_the_gym_env_binds_the_bot_as_its_opponent(install):
    bot = ScriptedBot(card_types=SYNTHETIC_TYPES)
    env = ClashGymEnv(
        BLUE, RED, install=install, engine="synthetic", opponent=bot, synthetic_episode_steps=8
    )
    try:
        env.reset(seed=1)
        assert bot.schema is env.schema
        for _ in range(8):
            env.step(0)
    finally:
        env.close()


def test_evaluate_counts_every_episode_once_from_the_policy_seat(install):
    # A synthetic episode is always won by side 0: the policy wins on seat 0 and loses on seat 1,
    # and the environments alternate the policy's seat.
    result = evaluate(
        wait_opponent,
        wait_opponent,
        deck=BLUE,
        opponent_deck=RED,
        episodes=7,
        envs=4,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=5,
    )
    assert (result.episodes, result.wins, result.losses, result.draws) == (7, 4, 3, 0)
    assert result.truncated == 0 and result.faults == {}
    assert result.win_rate == pytest.approx(4 / 7)


def test_evaluate_counts_a_faulted_episode_as_truncated(install):
    result = evaluate(
        wait_opponent,
        wait_opponent,
        deck=BLUE,
        episodes=4,
        envs=2,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=5,
        synthetic_fault_at=3,
        synthetic_fault_kind="refusal",
    )
    assert (result.episodes, result.wins, result.losses, result.truncated) == (4, 0, 0, 4)
    assert result.faults == {"refusal": 4}


# -- Ladder battles --------------------------------------------------------------------------------


@pytest.mark.ladder
def test_the_card_types_are_the_deck_cards_of_the_tables(install, tables):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        types = v.card_types()
        assert set(types.values()) == {"troop", "spell", "building"}
        is_deck_card = v._vocabulary.isDeckCard
        for name in v.vocabulary:
            assert (v.card_id(name) in types) == bool(is_deck_card(name)), name


@pytest.mark.ladder
def test_the_bot_beats_the_wait_opponent(install, tables):
    result = evaluate(
        ScriptedBot(),
        wait_opponent,
        deck=BLUE,
        episodes=2,
        envs=2,
        install=install,
        tables=tables,
    )
    assert (result.wins, result.episodes) == (2, 2)


@pytest.mark.ladder
def test_the_bot_plays_a_full_battle_against_the_random_opponent(install, tables):
    bot = ScriptedBot()
    env = ClashGymEnv(BLUE, RED, install=install, tables=tables, opponent=random_opponent, seat=1)
    try:
        observation, info = env.reset(seed=5)
        rng = np.random.default_rng(5)
        plays = illegal = 0
        done = False
        bot.bind(env.vector)
        while not done:
            action = bot(observation, env.vector.masks[0, env.seat], rng)
            plays += action != 0
            observation, _, terminated, truncated, info = env.step(action)
            illegal += info["illegal"]
            done = terminated or truncated
        assert terminated and info["fault"] == "none"
        assert plays > 0 and illegal == 0
    finally:
        env.close()


@pytest.mark.ladder
def test_the_spell_facts_are_spells_of_the_tables_cast_on_the_enemy(install, tables):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        types = v.card_types()
        facts = v.spell_facts()
        assert facts, "the tables list no spell the bot can cast"
        for card, fact in facts.items():
            assert types[card] == "spell", v.vocabulary[card]
            assert fact.radius > 0 and fact.damage >= 0 and fact.tower_damage >= 0
            assert fact.hits_air or fact.hits_ground
        # At least one spell of the test deck deals damage the bot reads.
        assert any(facts.get(v.card_id(name), SpellFacts(0)).damage > 0 for name in BLUE)
        # The facts are of the vector's card level: a higher level deals no less.
        higher = v.spell_facts(level=int(v.metadata["config"]["level"]) + 1)
        assert higher.keys() == facts.keys()
        assert all(higher[card].damage >= fact.damage for card, fact in facts.items())


@pytest.mark.ladder
def test_the_bot_casts_spells_in_ladder_battles(install, tables):
    battles = 4
    with VectorBattle(battles, 2, install=install, tables=tables) as v:
        bot = ScriptedBot().bind(v)
        s = v.schema
        spells = {card for card, kind in v.card_types().items() if kind == "spell"}
        rngs = [[np.random.default_rng([i, seat]) for seat in (0, 1)] for i in range(battles)]
        v.reset(list(range(battles)), BLUE, RED)
        # Each battle's first episode is played; once it ends, its next one only waits.
        finished = [False] * battles
        cast = illegal = faults = 0
        while not all(finished):
            for i in range(battles):
                for seat in (0, 1):
                    o = v.observations[i, seat]
                    action = s.wait
                    if not finished[i]:
                        action = bot(o, v.masks[i, seat], rngs[i][seat])
                    if action and not s.is_ability(action):
                        cast += int(s.field(o, "hand_card")[s.decode(action)[0]]) in spells
                    v.actions[i, seat] = action
            v.step()
            for i in range(battles):
                if finished[i]:
                    continue
                illegal += int(v.infos[i, s.info["illegal_0"]] + v.infos[i, s.info["illegal_1"]])
                if v.dones[i]:
                    finished[i] = True
                    faults += int(v.infos[i, s.info["fault"]] != 0)
        assert cast > 0
        assert illegal == 0 and faults == 0


@pytest.mark.ladder
def test_the_unit_facts_are_troop_and_building_cards_of_the_tables(install, tables):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        types = v.card_types()
        facts = v.unit_facts()
        assert facts, "the tables list no troop or building the bot can read"
        for card, fact in facts.items():
            assert types[card] in ("troop", "building"), v.vocabulary[card]
            assert 0 <= fact.unit < v.vocabulary_size and fact.range >= 0 and fact.hitpoints >= 0
        # The test deck holds a troop that attacks buildings only, and the tables a building that
        # attacks from range.
        assert any(
            facts[v.card_id(name)].buildings_only
            for name in BLUE
            if types.get(v.card_id(name)) == "troop"
        )
        assert any(f.building and f.range > 0 for f in facts.values())
        # The hit points are of the vector's card level: a higher level has no fewer.
        higher = v.unit_facts(level=int(v.metadata["config"]["level"]) + 1)
        assert higher.keys() == facts.keys()
        assert all(higher[card].hitpoints >= fact.hitpoints for card, fact in facts.items())
        assert any(higher[card].hitpoints > fact.hitpoints for card, fact in facts.items())


def play_bot_battles(install, tables, deck, battles=4):
    """Bot-against-bot Ladder battles of the deck on both seats, played to their result: every play
    as ``(card, col, row)``, the illegal actions and faults counted, and each play's hand slot and
    mask, as ``(slot, mask)`` in the order of the plays."""
    with VectorBattle(battles, 2, install=install, tables=tables) as v:
        bot = ScriptedBot().bind(v)
        s = v.schema
        rngs = [[np.random.default_rng([i, seat]) for seat in (0, 1)] for i in range(battles)]
        v.reset(list(range(battles)), deck, deck)
        finished = [False] * battles
        plays = [[] for _ in range(battles)]
        masks = [[] for _ in range(battles)]
        illegal = faults = 0
        while not all(finished):
            for i in range(battles):
                for seat in (0, 1):
                    o = v.observations[i, seat]
                    action = s.wait
                    if not finished[i]:
                        action = bot(o, v.masks[i, seat], rngs[i][seat])
                    if action and not s.is_ability(action):
                        slot, col, row = s.decode(action)
                        plays[i].append((int(s.field(o, "hand_card")[slot]), col, row))
                        masks[i].append((slot, v.masks[i, seat].copy()))
                    v.actions[i, seat] = action
            v.step()
            for i in range(battles):
                if finished[i]:
                    continue
                illegal += int(v.infos[i, s.info["illegal_0"]] + v.infos[i, s.info["illegal_1"]])
                if v.dones[i]:
                    finished[i] = True
                    faults += int(v.infos[i, s.info["fault"]] != 0)
        return bot, plays, illegal, faults, masks


@pytest.mark.ladder
def test_a_win_condition_deck_plays_its_win_condition_in_ladder_battles(install, tables):
    bot, plays, illegal, faults, _ = play_bot_battles(install, tables, BLUE)
    assert bot.attack_cards, "the test deck's win condition is not read from the tables"
    for battle in plays:
        attacks = [(card, row) for card, _, row in battle if card in bot.attack_cards]
        assert attacks
        # A tank on the back line, any other win condition at the bridge.
        for card, row in attacks:
            assert row == (bot.back_row if card in bot.tank_cards else bot.bridge_row)
    assert illegal == 0 and faults == 0


@pytest.mark.ladder
def test_a_siege_deck_places_its_siege_building_at_the_bridge(install, tables):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        siege = ScriptedBot().bind(v).siege_cards
        assert siege, "the tables list no building that reaches a tower from the bridge"
        # The test deck with its building-targeting troop swapped for a siege building.
        facts = v.unit_facts()
        name = v.vocabulary[min(siege)]
        deck = [n for n in BLUE if not facts.get(v.card_id(n), UnitFacts(0, 0)).buildings_only]
        deck = [name, *deck][: len(BLUE)]
    bot, plays, illegal, faults, masks = play_bot_battles(install, tables, deck, battles=2)
    s = bot.schema
    sieges = [
        (col, row, mask[s.play(slot, col, bot.bridge_row)])
        for battle, battle_masks in zip(plays, masks, strict=True)
        for (card, col, row), (slot, mask) in zip(battle, battle_masks, strict=True)
        if card in siege
    ]
    # At the bridge, or on the open tile nearest to it when the building does not land there
    # unmoved (the landing mask closes it).
    assert sieges and all(
        row == bot.bridge_row or (not bridge and row < bot.bridge_row) for _, row, bridge in sieges
    )
    assert illegal == 0 and faults == 0
