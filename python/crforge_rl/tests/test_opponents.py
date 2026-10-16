"""The players, the evaluation and the records (no torch)."""

import numpy as np
import pytest
from crforge_env import VectorBattle, random_opponent

from crforge_rl.evaluate import evaluate
from crforge_rl.opponents import (
    RandomOpponent,
    SeatOpponent,
    WaitOpponent,
    load_opponent,
)
from crforge_rl.records import Curve, FaultLog, read_curve

DECK = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap"]


class AlwaysFirstOpen:
    """A batch player for the spec test: the lowest open play, else the wait."""

    name = "first_open"

    def act(self, observations, masks, rng):
        plays = masks[:, 1:] != 0
        return np.where(plays.any(axis=1), plays.argmax(axis=1) + 1, 0).astype(np.int32)


class NeedsBinding:
    """A seat function that must be bound to its vector first, as ScriptedBot is."""

    def __init__(self):
        self.vector = None

    def bind(self, vector):
        self.vector = vector
        return self

    def __call__(self, observation, mask, rng):
        assert self.vector is not None
        return 0


def test_the_random_opponent_plays_only_open_actions_at_its_rate():
    rng = np.random.default_rng(1)
    masks = (rng.random((4000, 50)) < 0.1).astype(np.uint8)
    masks[:, 0] = 1
    masks[:10, 1:] = 0  # seats with nothing to play
    actions = RandomOpponent(0.3).act(np.zeros((4000, 5)), masks, rng)
    assert (masks[np.arange(4000), actions] == 1).all()
    assert (actions[:10] == 0).all()
    assert 0.25 < (actions != 0).mean() < 0.35
    assert (WaitOpponent().act(None, masks, rng) == 0).all()


def test_a_seat_function_plays_seat_by_seat():
    rng = np.random.default_rng(2)
    masks = np.zeros((6, 9), dtype=np.uint8)
    masks[:, 0] = 1
    masks[:, 4] = 1
    player = SeatOpponent(random_opponent)
    actions = player.act(np.zeros((6, 3)), masks, rng)
    assert set(actions.tolist()) <= {0, 4}
    assert player.name == "random_opponent"


def test_specs_name_the_players(install):
    assert isinstance(load_opponent("random"), RandomOpponent)
    assert isinstance(load_opponent("wait"), WaitOpponent)
    assert load_opponent("crforge_env:random_opponent").name == "crforge_env:random_opponent"
    assert load_opponent("tests.test_opponents:AlwaysFirstOpen").name == "first_open"
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        bound = load_opponent("tests.test_opponents:NeedsBinding", vector=v)
        assert bound.function.vector is v
    with pytest.raises(ValueError, match="bound"):
        load_opponent("tests.test_opponents:NeedsBinding")
    with pytest.raises(ValueError):
        load_opponent("nonsense")
    with pytest.raises(ValueError):
        load_opponent("crforge_env:no_such_player")


def test_an_evaluation_plays_each_battle_once_from_both_sides(install):
    # The synthetic battle ends at its 5th decision with side 0 the winner, whatever is played.
    with VectorBattle(6, 2, install=install, engine="synthetic", synthetic_episode_steps=5) as v:
        result = evaluate(
            v, RandomOpponent(), WaitOpponent(), DECK, list(range(6)), np.random.default_rng(0)
        )
    assert (result.games, result.wins, result.losses, result.draws) == (6, 3, 3, 0)
    assert result.win_rate == pytest.approx(0.5)
    assert 0 < result.plays_per_game < 5


def test_faults_are_counted_by_kind_and_signature(install, tmp_path):
    with VectorBattle(
        4,
        1,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=10,
        synthetic_fault_at=2,
        synthetic_fault_kind="refusal",
    ) as v:
        result = evaluate(
            v, WaitOpponent(), WaitOpponent(), DECK, list(range(4)), np.random.default_rng(0)
        )
        assert result.faults == 4
        assert result.win_rate == 0.0
        log = FaultLog()
        v.reset(list(range(4)), DECK, DECK)
        v.step()
        v.step()
        new = log.observe(v, range(4), update=7)
    assert len(new) == 1
    assert log.by_kind == {"refusal": 4}
    record = log.by_signature[new[0]]
    assert (record.count, record.first_update, record.kind) == (4, 7, "refusal")
    log.write(tmp_path / "faults.json")
    assert '"refusal": 4' in (tmp_path / "faults.json").read_text()


def test_the_curve_is_a_csv_of_rows(tmp_path):
    curve = Curve(tmp_path / "curve.csv")
    row = dict(
        update=1, learner_steps=10, wall_s=1.5, steps_per_s=6.7, opponent="random", games=4,
        wins=3, losses=1, draws=0, cut=0, faults=0, win_rate=0.75, plays_per_game=12.0,
    )  # fmt: skip
    curve.append(row)
    with pytest.raises(ValueError):
        curve.append({"update": 2})
    rows = read_curve(tmp_path / "curve.csv")
    assert rows[0]["win_rate"] == "0.75" and rows[0]["opponent"] == "random"
