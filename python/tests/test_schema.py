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
    assert seen == set(range(1, s.actions))


def test_the_bounds_hold_a_cleared_observation(vector):
    low, high = vector.schema.observation_bounds(vector.vocabulary_size)
    assert low.dtype == high.dtype == np.int32
    assert (low <= 0).all() and (high >= 0).all()
    row = vector.schema.header["hand_card"][0]
    assert (low[row], high[row]) == (-1, vector.vocabulary_size - 1)
