import numpy as np
import pytest

from crforge_env import SUPPORTED_VERSION, Schema, VectorBattle
from crforge_env._jvm import java_class
from crforge_env.schema import Codes


@pytest.fixture(scope="module")
def vector(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        yield v


def test_the_schema_comes_from_the_metadata(vector):
    s = vector.schema
    j = java_class("org.crforge.env.Schema")
    assert s.version == SUPPORTED_VERSION == int(j.VERSION)
    assert (s.seat_ints, s.header_ints, s.entity_ints, s.max_entities) == (
        int(j.SEAT_INTS),
        int(j.HEADER_INTS),
        int(j.ENTITY_INTS),
        int(j.MAX_ENTITIES),
    )
    assert (s.actions, s.info_ints, s.cols, s.rows) == (
        int(j.ACTIONS),
        int(j.INFO_INTS),
        int(j.COLS),
        int(j.ROWS),
    )
    assert s.header["tick"] == (int(j.H_TICK),)
    assert s.header["hand_card"] == tuple(int(j.H_HAND_CARD) + k for k in range(s.hand_slots))
    assert s.header["last_refusal"] == (int(j.H_LAST_REFUSAL),)
    assert len(s.header["pending_card"]) == s.pending_rows
    assert s.entity["flags"] == int(j.E_FLAGS)
    assert s.info["illegal_1"] == int(j.I_ILLEGAL_1)
    assert s.info["first_side"] == int(j.I_FIRST_SIDE)
    assert s.flags["king"] == int(j.F_KING)
    assert s.refusals[int(j.REFUSAL_MASKED)] == "MASKED"
    assert s.fault_names[int(j.FAULT_ENV_BUG)] == "env_bug"


def test_another_schema_version_is_refused(vector):
    metadata = dict(vector.metadata)
    metadata["schema"] = {**metadata["schema"], "version": SUPPORTED_VERSION + 1}
    with pytest.raises(RuntimeError, match="schema version"):
        Schema(metadata, Codes.from_java())


def test_the_python_action_codec_agrees_with_the_java_one(vector):
    s = vector.schema
    j = java_class("org.crforge.env.Schema")
    rng = np.random.default_rng(0)
    for _ in range(500):
        slot = int(rng.integers(s.hand_slots))
        col = int(rng.integers(s.cols))
        row = int(rng.integers(s.rows))
        action = s.play(slot, col, row)
        assert action == int(j.play(slot, col, row))
        assert s.decode(action) == (int(j.slot(action)), int(j.col(action)), int(j.row(action)))
    seen = {
        s.play(k, c, r) for k in range(s.hand_slots) for c in range(s.cols) for r in range(s.rows)
    }
    assert seen == set(range(1, s.first_ability))


def test_the_ability_actions_follow_every_play_one_per_champion_slot(vector):
    s = vector.schema
    j = java_class("org.crforge.env.Schema")
    assert (s.ability_slots, s.first_ability) == (int(j.ABILITY_SLOTS), int(j.FIRST_ABILITY))
    assert s.actions == 1 + s.hand_slots * s.cols * s.rows + s.ability_slots
    assert [s.ability(k) for k in range(s.ability_slots)] == list(range(s.first_ability, s.actions))
    for k in range(s.ability_slots):
        action = s.ability(k)
        assert action == int(j.ability(k))
        assert s.is_ability(action) and s.ability_slot(action) == k
        with pytest.raises(ValueError):
            s.decode(action)
    assert not s.is_ability(s.wait) and not s.is_ability(s.first_ability - 1)
    with pytest.raises(ValueError):
        s.ability(s.ability_slots)
    assert len(s.header["ability_champion"]) == s.ability_slots
    assert len(s.header["deck_slot"]) == len(s.header["deck_evolution"]) == s.deck_size
    assert len(s.header["hand_form"]) == s.hand_slots


def test_a_saved_export_of_the_first_schema_is_refused(vector):
    saved = dict(vector.metadata)
    saved["schema"] = {
        **saved["schema"],
        "version": 1,
        "actions": vector.schema.first_ability,
    }
    with pytest.raises(ValueError, match="schema version 1"):
        vector.check_metadata(saved)


def test_the_bounds_hold_a_cleared_observation(vector):
    low, high = vector.schema.observation_bounds(vector.vocabulary_size)
    assert low.dtype == high.dtype == np.int32
    assert (low <= 0).all() and (high >= 0).all()
    row = vector.schema.header["hand_card"][0]
    assert (low[row], high[row]) == (-1, vector.vocabulary_size - 1)


def test_version_3_names_its_states_buff_slots_enemy_plays_and_scales(vector):
    s = vector.schema
    j = java_class("org.crforge.env.Schema")
    assert s.state_names == tuple(str(n) for n in j.stateNames())
    assert len(s.state_names) == s.scales["max_state"] + 1
    assert s.scales["elixir_scale"] == 10_000 and s.scales["tile_units"] == s.tile_units
    assert s.no_state == int(j.NO_STATE) and s.no_target == int(j.NO_TARGET)
    assert s.buff_slots == int(j.BUFF_SLOTS)
    assert [s.entity[f"buff_{b}"] for b in range(s.buff_slots)] == [
        int(j.E_BUFFS) + 2 * b for b in range(s.buff_slots)
    ]
    assert s.entity["radius"] == s.entity_ints - 1
    assert len(s.header["enemy_play_card"]) == s.enemy_play_rows == int(j.ENEMY_PLAY_ROWS)
    assert s.header["enemy_elixir"] == (int(j.H_ENEMY_ELIXIR),)
    assert len(s.header["queue"]) == s.queue_slots
    assert len(s.header["deck_level"]) == s.deck_size
    assert s.flags["awake"] == int(j.F_AWAKE) and s.flags["charged"] == int(j.F_CHARGED)


def test_the_columns_of_the_written_rows_are_read_by_name(vector):
    s = vector.schema
    vector.reset([3], ["a"] * s.deck_size, ["b"] * s.deck_size)
    observation = vector.observations[0, 0]
    columns = s.columns(observation)
    rows = s.entities(observation)
    assert set(columns) == set(s.entity)
    for name, index in s.entity.items():
        np.testing.assert_array_equal(columns[name], rows[:, index])
