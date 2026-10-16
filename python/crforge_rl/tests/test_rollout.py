"""The rollout's bookkeeping: seats, the shaping and the advantages (no torch)."""

import numpy as np
import pytest
from crforge_env import VectorBattle

from crforge_rl.rollout import Seats, advantages, leaking, tower_potential

DECK = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap"]


def test_random_mode_alternates_the_learner_side_by_battle():
    seats = Seats.of(4, "random")
    assert seats.learner.tolist() == [0, 3, 4, 7]
    assert seats.opponent.tolist() == [1, 2, 5, 6]
    assert seats.side(seats.learner).tolist() == [0, 1, 0, 1]
    assert seats.battle(seats.opponent).tolist() == [0, 1, 2, 3]


def test_self_mode_learns_on_every_seat():
    seats = Seats.of(3, "self")
    assert seats.learner.tolist() == list(range(6))
    assert seats.opponent.size == 0
    with pytest.raises(ValueError):
        Seats.of(3, "league")


def test_the_tower_balance_is_zero_sum_between_the_seats(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        v.reset([1], DECK, DECK)
        s = v.schema
        seats = v.observations[0].copy()
        # The enemy's towers of one seat are the other's own: a king at half, a princess fallen.
        for name, value in (("king_hp", 500), ("king_max_hp", 1000), ("left_hp", 0)):
            seats[0, s.header[f"enemy_{name}"][0]] = value
            seats[1, s.header[f"own_{name}"][0]] = value
        for name in ("left_max_hp", "right_hp", "right_max_hp"):
            for seat, owner in ((0, "enemy"), (1, "own"), (0, "own"), (1, "enemy")):
                seats[seat, s.header[f"{owner}_{name}"][0]] = 1000
        for seat, owner in ((0, "own"), (1, "enemy")):
            seats[seat, s.header[f"{owner}_king_hp"][0]] = 1000
            seats[seat, s.header[f"{owner}_king_max_hp"][0]] = 1000
            seats[seat, s.header[f"{owner}_left_hp"][0]] = 1000
        phi = tower_potential(s, seats)
        assert phi[0] == pytest.approx(1.5)
        assert phi[0] + phi[1] == pytest.approx(0.0)
        # A cleared observation (a fault) reads 0.
        assert tower_potential(s, np.zeros_like(seats)).tolist() == [0.0, 0.0]


def test_advantages_follow_the_ends_of_episodes():
    gamma, lam = 0.9, 0.5
    # One seat over four steps: a step, a terminated step (+1), the reset step after it, a step.
    rewards = np.array([[0.0], [1.0], [0.0], [0.0]])
    values = np.array([[0.2], [0.4], [0.7], [0.1]])
    terminated = np.array([[False], [True], [False], [False]])
    truncated = np.zeros((4, 1), dtype=bool)
    valid = np.array([[True], [True], [False], [True]])
    next_values = np.array([0.3])
    adv, ret = advantages(rewards, values, terminated, truncated, valid, next_values, gamma, lam)
    last = 0.0 + gamma * 0.3 - 0.1
    end = 1.0 - 0.4  # no bootstrap past the end
    first = (0.0 + gamma * 0.4 - 0.2) + gamma * lam * end
    assert adv[:, 0] == pytest.approx([first, end, 0.0, last])
    assert ret[:, 0] == pytest.approx(adv[:, 0] + values[:, 0])


def test_a_truncated_step_bootstraps_from_its_own_value():
    values = np.array([[0.5], [0.6]])
    adv, _ = advantages(
        np.zeros((2, 1)),
        values,
        np.zeros((2, 1), dtype=bool),
        np.array([[True], [False]]),
        np.array([[True], [False]]),
        np.array([9.0]),
        0.9,
        0.95,
    )
    assert adv[0, 0] == pytest.approx(0.9 * 0.5 - 0.5)
    assert adv[1, 0] == 0.0


def test_a_wait_at_full_elixir_with_a_play_open_is_a_leak(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        s = v.schema
        obs = np.zeros((4, s.seat_ints), dtype=np.int32)
        obs[:, s.header["elixir"][0]] = [100_000, 100_000, 100_000, 90_000]
        obs[2, s.header["elixir_reserved"][0]] = 30_000  # a play waiting holds three back
        masks = np.zeros((4, s.actions), dtype=np.uint8)
        masks[:, 0] = 1
        masks[[0, 2, 3], 5] = 1
        waits = np.zeros(4, dtype=np.int64)
        assert leaking(s, obs, masks, waits).tolist() == [True, False, False, False]
        assert not leaking(s, obs, masks, np.array([5, 0, 0, 0])).any()
