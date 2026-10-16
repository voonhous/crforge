import numpy as np
import pytest
from gymnasium.utils.env_checker import check_env
from pettingzoo.test import parallel_api_test

from crforge_env import ClashGymEnv, ClashParallelEnv, random_opponent
from tests.decks import BLUE, RED


def test_the_parallel_env_passes_the_pettingzoo_api_test(install):
    env = ClashParallelEnv(
        BLUE, RED, install=install, engine="synthetic", synthetic_episode_steps=12
    )
    try:
        parallel_api_test(env, num_cycles=30)
    finally:
        env.close()


def test_the_gym_env_passes_the_gymnasium_checker(install):
    env = ClashGymEnv(BLUE, RED, install=install, engine="synthetic", synthetic_episode_steps=12)
    check_env(env, skip_render_check=True)


def test_the_parallel_env_reports_both_seats_each_step(install):
    env = ClashParallelEnv(
        BLUE, RED, install=install, engine="synthetic", synthetic_episode_steps=4
    )
    try:
        observations, infos = env.reset(seed=3)
        seat = env.schema.header["seat"][0]
        assert set(observations) == {"seat_0", "seat_1"}
        assert observations["seat_1"][seat] == 1
        assert observations["seat_0"].dtype == np.int32
        assert infos["seat_0"]["action_mask"].shape == (env.schema.actions,)
        assert infos["seat_0"]["action_mask"].dtype == np.int8
        assert env.action_mask("seat_1").dtype == bool
        for step in range(1, 5):
            observations, rewards, terminations, truncations, infos = env.step(
                {"seat_0": 0, "seat_1": env.schema.actions}
            )
            assert observations["seat_0"][env.schema.header["episode_step"][0]] == step
            # Each seat's info counts its own illegal actions only.
            assert infos["seat_0"]["illegal"] == 0
            assert infos["seat_1"]["illegal"] == 1
        assert terminations == {"seat_0": True, "seat_1": True}
        assert truncations == {"seat_0": False, "seat_1": False}
        assert rewards == {"seat_0": 1.0, "seat_1": -1.0}
        assert infos["seat_0"]["fault"] == "none"
        assert env.agents == []
    finally:
        env.close()


def test_the_gym_env_runs_an_episode_against_the_random_opponent(install):
    env = ClashGymEnv(
        BLUE,
        RED,
        install=install,
        engine="synthetic",
        opponent=random_opponent,
        synthetic_episode_steps=6,
    )
    try:
        observation, info = env.reset(seed=1)
        assert observation.shape == (env.schema.seat_ints,)
        assert observation.dtype == np.int32
        assert env.observation_space.contains(observation)
        assert info["action_mask"][0] == 1
        assert env.action_masks().dtype == bool
        total = 0.0
        for _ in range(6):
            observation, reward, terminated, truncated, info = env.step(0)
            assert env.observation_space.contains(observation)
            total += reward
        assert terminated and not truncated
        assert total == 1.0
    finally:
        env.close()


def test_the_gym_env_is_reproduced_by_its_seed(install):
    def run():
        env = ClashGymEnv(
            BLUE, RED, install=install, engine="synthetic", synthetic_episode_steps=20
        )
        try:
            observations = [env.reset(seed=42)[0]]
            for action in range(1, 15):
                observations.append(env.step(action * 37)[0])
            return np.stack(observations)
        finally:
            env.close()

    np.testing.assert_array_equal(run(), run())


@pytest.mark.parametrize("kind", ["refusal", "bug", "env_bug"])
def test_a_fault_ends_the_gym_episode_as_truncated_with_no_reward(install, kind):
    env = ClashGymEnv(
        BLUE,
        RED,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=5,
        synthetic_fault_at=5,
        synthetic_fault_kind=kind,
    )
    try:
        env.reset(seed=1)
        for _ in range(4):
            _, _, terminated, truncated, info = env.step(0)
            assert not terminated and not truncated
            assert info["fault"] == "none" and info["fault_message"] == ""
        # The fault falls on the episode's last decision, where seat 0 would have won.
        _, reward, terminated, truncated, info = env.step(0)
        assert (terminated, truncated, reward) == (False, True, 0.0)
        assert info["fault"] == kind
        assert "forced" in info["fault_message"]
        assert info["fault_signature"].startswith(kind + " ")
        assert info["winner"] == env.schema.winner_none
        with pytest.raises(RuntimeError):
            env.step(0)
        env.reset()
    finally:
        env.close()


@pytest.mark.parametrize("kind", ["refusal", "bug", "env_bug"])
def test_a_fault_ends_the_parallel_episode_as_truncated_with_no_reward(install, kind):
    env = ClashParallelEnv(
        BLUE,
        RED,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=5,
        synthetic_fault_at=5,
        synthetic_fault_kind=kind,
    )
    try:
        env.reset(seed=1)
        for _ in range(4):
            env.step({"seat_0": 0, "seat_1": 0})
        _, rewards, terminations, truncations, infos = env.step({"seat_0": 0, "seat_1": 0})
        assert rewards == {"seat_0": 0.0, "seat_1": 0.0}
        assert terminations == {"seat_0": False, "seat_1": False}
        assert truncations == {"seat_0": True, "seat_1": True}
        for agent in ("seat_0", "seat_1"):
            assert infos[agent]["fault"] == kind
            assert "forced" in infos[agent]["fault_message"]
            assert infos[agent]["fault_signature"].startswith(kind + " ")
        assert env.agents == []
    finally:
        env.close()
