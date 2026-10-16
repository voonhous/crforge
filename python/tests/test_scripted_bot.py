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


@pytest.fixture(scope="module")
def synthetic(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as vector:
        yield vector


def crafted(schema, *, elixir, hand, costs, enemies=(), pending=0, enemy_left=1, enemy_right=1):
    """A seat observation written by hand: the elixir in ten-thousandths, the hand's card ids and
    costs, the enemy characters as ``(x, y)`` in the seat's frame, and the enemy princess towers'
    hit points."""
    o = np.zeros(schema.seat_ints, dtype=np.int32)

    def put(name, values):
        o[list(schema.header[name])] = values

    put("elixir", elixir)
    put("hand_card", hand)
    put("hand_cost", costs)
    put("pending_count", pending)
    put("enemy_left_hp", enemy_left)
    put("enemy_right_hp", enemy_right)
    put("entity_count", len(enemies))
    for k, (x, y) in enumerate(enemies):
        at = schema.header_ints + k * schema.entity_ints
        o[at + schema.entity["kind"]] = schema.kinds["character"]
        o[at + schema.entity["side"]] = 1
        o[at + schema.entity["x"]] = x
        o[at + schema.entity["y"]] = y
        o[at + schema.entity["hp"]] = 100
        o[at + schema.entity["flags"]] = schema.flags["alive"]
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


def test_it_defends_the_threatened_lane_behind_the_towers_with_a_troop_or_building(synthetic):
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
    # Across the river on the right: the cheapest troop or building, the right lane's defence tile.
    right = s.width_units - 3000
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, enemies=[(right, own_half)])
    assert s.decode(bot(o, open_mask(s), rng)) == (2, bot.lane_cols[1], bot.defend_row)
    # The deepest of two threats picks the lane.
    o = crafted(s, elixir=elixir, hand=hand, costs=costs, enemies=[(right, own_half), (3000, 4000)])
    assert s.decode(bot(o, open_mask(s), rng)) == (2, bot.lane_cols[0], bot.defend_row)
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


def synthetic_actions(install, seed):
    """The bot's actions for both seats over 200 decisions of a synthetic battle, with its masks."""
    with VectorBattle(1, 1, install=install, engine="synthetic", synthetic_episode_steps=200) as v:
        bot = bound_bot(v)
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


def test_the_bot_plays_only_troops_and_buildings_of_the_card_types(install):
    with VectorBattle(1, 1, install=install, engine="synthetic", synthetic_episode_steps=300) as v:
        bot = bound_bot(v)
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
