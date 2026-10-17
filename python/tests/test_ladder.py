"""Ladder battles on the game tables (--tables). Nothing here hard-codes game data: the expected
values are read from the tables, the schema or the battle, or held as relations."""

import json
from pathlib import Path

import numpy as np
import pytest
from gymnasium.utils.env_checker import check_env
from pettingzoo.test import parallel_api_test

from crforge_env import ClashGymEnv, ClashParallelEnv, LadderConfig, VectorBattle, random_opponent
from crforge_env.bench import draw_actions
from tests.decks import BLUE, RED

pytestmark = pytest.mark.ladder


def test_a_ladder_battle_starts_with_the_decks_towers_and_a_closed_hand(install, tables):
    with VectorBattle(2, 1, install=install, tables=tables) as v:
        v.reset([1, 2], BLUE, RED)
        s = v.schema
        assert v.data_version and v.content_sha and v.vocabulary_hash
        assert v.vocabulary == sorted(v.vocabulary)
        for i in range(2):
            elixir = [s.field(v.observations[i, seat], "elixir") for seat in range(2)]
            assert elixir[0] == elixir[1] > 0
            for seat in range(2):
                o = v.observations[i, seat]
                assert s.field(o, "elixir_reserved") == 0
                assert s.field(o, "play_open_in_ticks") > 0
                rows = s.entities(o)
                # Every entity at the start is a tower, and each tower the header lists has a row.
                assert (rows[:, s.entity["kind"]] == s.kinds["tower"]).all()
                towers = [n for n in s.header if n.endswith("_hp") and not n.endswith("max_hp")]
                standing = [n for n in towers if s.field(o, n) > 0]
                assert len(standing) == len(towers) == len(rows)
                deck = BLUE if seat == 0 else RED
                assert list(s.field(o, "deck")) == [v.card_id(c) for c in deck]
                hand = list(s.field(o, "hand_card"))
                assert set(hand) <= {v.card_id(c) for c in deck}
                # Before the start cooldown the mask is the wait alone.
                assert v.masks[i, seat].sum() == 1 and v.masks[i, seat, s.wait] == 1
                observation_low, observation_high = s.observation_bounds(v.vocabulary_size)
                assert ((o >= observation_low) & (o <= observation_high)).all()


def test_the_mask_is_honoured_and_a_closed_action_is_counted_for_its_seat(install, tables):
    with VectorBattle(4, 1, install=install, tables=tables) as v:
        v.reset([1, 2, 3, 4], BLUE, RED)
        s = v.schema
        # A play before the start cooldown is closed: taken as a wait, counted for that seat.
        closed = s.play(0, s.cols // 2, 2)
        assert v.masks[0, 1, closed] == 0
        v.step(np.array([[0, closed]] + [[0, 0]] * 3))
        first, second = v.seat_info(0, 0), v.seat_info(0, 1)
        assert (first["illegal"], second["illegal"]) == (0, 1)
        assert second["last_refusal"] == "MASKED" and first["last_refusal"] == "NONE"
        assert second["illegal_total"] == 1
        # Random open actions are never refused as illegal, and some are played.
        rng = np.random.default_rng(0)
        played = 0
        for _ in range(300):
            actions = draw_actions(v.masks, rng, 0.5)
            played += int((actions != 0).sum())
            v.step(actions)
            assert v.infos[:, s.info["illegal_0"]].sum() == 0
            assert v.infos[:, s.info["illegal_1"]].sum() == 0
        assert played > 0
        assert all(v.fault(i) is None for i in range(4))


def test_the_same_seeds_and_actions_give_the_same_observations(install, tables):
    config = LadderConfig(max_ticks=600)
    with (
        VectorBattle(4, 1, install=install, tables=tables, config=config) as one,
        VectorBattle(4, 4, install=install, tables=tables, config=config) as four,
        VectorBattle(4, 1, install=install, tables=tables, config=config) as again,
    ):
        seeds = [1, 2, 3, 4]
        for v in (one, four, again):
            v.reset(seeds, BLUE, RED)
        rng = np.random.default_rng(0)
        for _ in range(150):
            actions = draw_actions(one.masks, rng, 0.5)
            for v in (one, four, again):
                v.step(actions)
            np.testing.assert_array_equal(one.observations, four.observations)
            np.testing.assert_array_equal(one.observations, again.observations)
            np.testing.assert_array_equal(one.masks, four.masks)
            np.testing.assert_array_equal(one.infos, four.infos)
            np.testing.assert_array_equal(one.rewards, again.rewards)


def test_the_gym_env_plays_a_whole_battle(install, tables):
    env = ClashGymEnv(BLUE, RED, install=install, tables=tables, opponent=random_opponent)
    try:
        observation, info = env.reset(seed=7)
        rng = np.random.default_rng(7)
        done = False
        steps = 0
        while not done:
            mask = env.action_masks()
            assert (mask == info["action_mask"].astype(bool)).all()
            open_actions = np.flatnonzero(mask)
            action = int(rng.choice(open_actions)) if rng.random() < 0.3 else 0
            observation, reward, terminated, truncated, info = env.step(action)
            assert env.observation_space.contains(observation)
            assert info["illegal"] == 0
            done = terminated or truncated
            steps += 1
            assert steps < 10_000
        assert terminated and not truncated and info["fault"] == "none"
        s = env.schema
        result = s.field(observation, "result")
        if info["winner"] == env.seat:
            assert reward == 1.0 and result == s.results["won"]
            assert info["crowns"] >= info["crowns_enemy"]
        elif info["winner"] == s.winner_draw:
            assert reward == 0.0 and result == s.results["draw"]
        else:
            assert info["winner"] == 1 - env.seat
            assert reward == -1.0 and result == s.results["lost"]
            assert info["crowns"] <= info["crowns_enemy"]
        assert info["tick"] == s.field(observation, "tick") > 0
    finally:
        env.close()


def test_a_tick_limit_truncates_with_no_result(install, tables):
    config = LadderConfig(decision_ticks=5, max_ticks=50)
    env = ClashParallelEnv(BLUE, RED, install=install, tables=tables, config=config)
    try:
        env.reset(seed=3)
        for _ in range(100):
            _, rewards, terminations, truncations, infos = env.step({})
            if not env.agents:
                break
        assert truncations == {"seat_0": True, "seat_1": True}
        assert not any(terminations.values())
        assert rewards == {"seat_0": 0.0, "seat_1": 0.0}
        assert infos["seat_0"]["winner"] == env.schema.winner_none
        assert infos["seat_0"]["fault"] == "none"
        assert infos["seat_0"]["tick"] >= config.max_ticks
    finally:
        env.close()


def test_the_seats_see_each_other_from_their_own_frames(install, tables):
    env = ClashParallelEnv(BLUE, RED, install=install, tables=tables)
    try:
        observations, infos = env.reset(seed=5)
        s = env.schema
        own = s.field(observations["seat_0"], "own_king_hp")
        assert own == s.field(observations["seat_1"], "enemy_king_hp")
        assert infos["seat_0"]["crowns"] == infos["seat_1"]["crowns_enemy"] == 0
    finally:
        env.close()


def test_the_wrappers_pass_the_api_checks_on_a_battle(install, tables):
    parallel = ClashParallelEnv(BLUE, RED, install=install, tables=tables)
    try:
        parallel_api_test(parallel, num_cycles=100)
    finally:
        parallel.close()
    check_env(ClashGymEnv(BLUE, RED, install=install, tables=tables), skip_render_check=True)


def test_a_saved_vocabulary_whose_ids_moved_is_refused(install, tables, tmp_path):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        path = tmp_path / "metadata.json"
        v.write_metadata(path)
        v.check_metadata(path)
        saved = json.loads(path.read_text())
        names = saved["vocabulary"]["names"]
        moved = names[1]
        names[0], names[1] = names[1], names[0]
        with pytest.raises(ValueError, match=moved):
            v.check_metadata(saved)
        saved = json.loads(path.read_text())
        saved["vocabulary"]["names"].append("NoSuchRow")
        with pytest.raises(ValueError, match="NoSuchRow"):
            v.check_metadata(saved)
        saved = json.loads(path.read_text())
        del saved["vocabulary"]
        with pytest.raises(ValueError, match="no vocabulary"):
            v.check_metadata(saved)


def test_slot_flags_reach_the_match_and_the_seats_header(install, tables):
    with VectorBattle(2, 1, install=install, tables=tables) as v:
        s = v.schema
        evolution = [s.slot_evolution] + [0] * (s.deck_size - 1)
        v.reset([1, 2], BLUE, RED, slots0=[evolution, [0] * s.deck_size])
        assert list(s.field(v.observations[0, 0], "deck_slot")) == evolution
        assert not s.field(v.observations[1, 0], "deck_slot").any()
        assert not s.field(v.observations[0, 1], "deck_slot").any()
        for i in range(2):
            for seat in range(2):
                assert not s.field(v.observations[i, seat], "deck_evolution").any()
        # A reset without flags plays every card plain again.
        v.reset([1, 2], BLUE, RED)
        assert not s.field(v.observations[0, 0], "deck_slot").any()
        with pytest.raises(ValueError, match="slot flags"):
            v.reset([1, 2], BLUE, RED, slots1=[0] * (s.deck_size - 1))
        with pytest.raises(ValueError, match="slot flags"):
            v.reset([1, 2], BLUE, RED, slots1=[4] + [0] * (s.deck_size - 1))
    env = ClashGymEnv(BLUE, RED, install=install, tables=tables, seat=1, slots=evolution)
    try:
        observation, _ = env.reset(seed=3)
        assert list(env.schema.field(observation, "deck_slot")) == evolution
    finally:
        env.close()


def test_the_ability_actions_stay_closed_with_no_champion(install, tables):
    with VectorBattle(2, 1, install=install, tables=tables) as v:
        v.reset([1, 2], BLUE, RED)
        s = v.schema
        rng = np.random.default_rng(1)
        for _ in range(200):
            assert not v.masks[:, :, s.first_ability :].any()
            assert (v.observations[:, :, list(s.header["ability_unit"])] == -1).all()
            v.step(draw_actions(v.masks, rng, 0.5))
        # An ability action given anyway is closed: taken as a wait and counted.
        v.step(np.array([[s.ability(0), 0], [0, s.ability(1)]]))
        assert v.seat_info(0, 0)["last_refusal"] == "MASKED"
        assert v.seat_info(1, 1)["last_refusal"] == "MASKED"


def test_a_card_not_in_the_tables_is_refused(install, tables):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        with pytest.raises(ValueError, match="Archers"):
            v.reset([1], [*BLUE[:1], "Archers", *BLUE[2:]], RED)


# Three champion cards in one deck: a battle whose setup the battle core refuses.
THREE_CHAMPIONS = [
    "ArcherQueen",
    "GoldenKnight",
    "SkeletonKing",
    "Knight",
    "Archer",
    "Musketeer",
    "Giant",
    "MiniPekka",
]


def test_a_fault_writes_a_bundle_whose_folder_the_fault_info_names(install, tables, tmp_path):
    folder = tmp_path / "bundles"
    with VectorBattle(
        1, 1, install=install, tables=tables, config=LadderConfig(fault_bundles=folder)
    ) as v:
        v.reset([3], THREE_CHAMPIONS, RED)
        fault = v.fault(0)
        assert fault is not None and fault.kind == "refusal"
        bundle = Path(fault.bundle)
        assert bundle.parent == folder.resolve()
        record = json.loads((bundle / "fault.json").read_text(encoding="utf-8"))
        assert record["kind"] == "refusal"
        assert record["signature"] == fault.signature
        assert record["data_version"] == v.data_version
        assert record["content_sha"] == v.content_sha
        assert record["seed"] == 3
        assert record["decks"] == [THREE_CHAMPIONS, RED]
        assert record["replay"] == "replay.json"
        replay = json.loads((bundle / "replay.json").read_text(encoding="utf-8"))
        assert replay["capture"]["content_sha"] == v.content_sha
        for seat in range(2):
            info = v.seat_info(0, seat)
            assert info["fault"] == "refusal"
            assert info["fault_bundle"] == fault.bundle
    # Off by default: the fault is kept, no bundle is written.
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        v.reset([3], THREE_CHAMPIONS, RED)
        assert v.fault(0).bundle is None
        assert v.seat_info(0, 0)["fault_bundle"] == ""


def test_a_look_ahead_plays_forks_and_leaves_the_battles_as_they_were(install, tables):
    with VectorBattle(2, 2, install=install, tables=tables) as v:
        v.reset([5, 6], BLUE, RED)
        s = v.schema
        rng = np.random.default_rng(3)
        for _ in range(200):
            v.step(draw_actions(v.masks, rng, 0.3))
        while not v.masks[0, 0, 1 : s.first_ability].any():
            v.step(np.zeros((2, 2), dtype=np.int32))
        play = 1 + int(np.flatnonzero(v.masks[0, 0, 1 : s.first_ability])[0])
        before = (v.observations.copy(), v.masks.copy(), v.infos.copy())
        indices = np.array([0, 0, 1])
        seats = np.array([0, 0, 1])
        actions = np.array([s.wait, play, s.wait])
        observations, infos = v.lookahead(indices, seats, actions, 160)
        assert observations.shape == (3, s.seat_ints) and infos.shape == (3, s.info_ints)
        assert (infos[:, s.info["fault"]] == 0).all()
        assert (infos[:, s.info["tick"]] >= v.infos[indices, s.info["tick"]] + 160).all()
        assert (observations[:, s.header["seat"][0]] == seats).all()
        assert not np.array_equal(observations[0], observations[1])
        again, _ = v.lookahead(indices, seats, actions, 160)
        assert np.array_equal(again, observations)
        for now, then in zip((v.observations, v.masks, v.infos), before, strict=True):
            assert np.array_equal(now, then)
