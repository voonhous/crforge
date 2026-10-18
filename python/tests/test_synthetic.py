import numpy as np
import pytest

from crforge_env import VectorBattle
from tests.decks import BLUE, RED


def test_the_arrays_view_the_jvm_buffers_without_a_copy(install):
    with VectorBattle(3, 1, install=install, engine="synthetic") as v:
        s = v.schema
        assert v.observations.shape == (3, 2, s.seat_ints)
        assert v.observations.dtype == np.int32
        assert v.masks.shape == (3, 2, s.actions)
        assert v.masks.dtype == np.uint8
        assert v.actions.shape == (3, 2)
        assert v.rewards.shape == (3, 2)
        assert v.rewards.dtype == np.float32
        assert v.dones.shape == (3,)
        assert v.infos.shape == (3, s.info_ints)
        for array in (v.observations, v.masks, v.actions, v.rewards, v.dones, v.infos):
            assert not array.flags["OWNDATA"]
        # A Python write lands in the Java buffer.
        v.actions[2, 1] = 1234
        assert int(v._env.actions().asIntBuffer().get(5)) == 1234
        # A Java write shows in the array.
        v.reset([1, 2, 3], BLUE, RED)
        assert list(v.observations[:, 0, s.header["seat"][0]]) == [0, 0, 0]
        assert list(v.observations[:, 1, s.header["seat"][0]]) == [1, 1, 1]
        assert (v.masks[:, :, s.wait] == 1).all()


def test_episodes_end_and_restart_on_the_next_step(install):
    with VectorBattle(2, 2, install=install, engine="synthetic", synthetic_episode_steps=3) as v:
        v.reset([5, 6], BLUE, RED)
        for step in range(1, 4):
            v.step(np.zeros((2, 2), dtype=np.int32))
            assert [v.info(i)["episode_step"] for i in range(2)] == [step, step]
        assert v.dones.tolist() == [1, 1]
        assert v.rewards.tolist() == [[1.0, -1.0], [1.0, -1.0]]
        v.step()
        assert v.dones.tolist() == [0, 0]
        assert [v.info(i)["reset"] for i in range(2)] == [1, 1]
        assert [v.info(i)["episode_step"] for i in range(2)] == [0, 0]


def test_the_same_seeds_and_actions_give_the_same_observations(install):
    seeds = [11, 12, 13, 14, 15]
    kwargs = dict(install=install, engine="synthetic", synthetic_episode_steps=50)
    with (
        VectorBattle(5, 1, **kwargs) as one,
        VectorBattle(5, 4, **kwargs) as four,
        VectorBattle(5, 1, **kwargs) as again,
    ):
        assert four.threads == 4
        rng = np.random.default_rng(3)
        for v in (one, four, again):
            v.reset(seeds, BLUE, RED)
        for _ in range(60):
            actions = rng.integers(0, one.schema.actions, size=(5, 2))
            for v in (one, four, again):
                v.step(actions)
            np.testing.assert_array_equal(one.observations, four.observations)
            np.testing.assert_array_equal(one.observations, again.observations)
            np.testing.assert_array_equal(one.masks, four.masks)
            np.testing.assert_array_equal(one.infos, again.infos)


def test_a_bad_deck_is_refused(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        with pytest.raises(ValueError):
            v.reset([1], BLUE[:7], RED)
        with pytest.raises(ValueError):
            v.reset([1, 2], BLUE, RED)
        v.reset([1], [BLUE], [RED])


def test_a_ladder_vector_needs_its_tables(install):
    with pytest.raises(ValueError, match="tables"):
        VectorBattle(1, 1, install=install, engine="ladder")


@pytest.mark.parametrize("kind", ["refusal", "bug", "env_bug"])
def test_a_forced_fault_ends_that_episode_alone_as_truncated(install, kind):
    with VectorBattle(
        2,
        1,
        install=install,
        engine="synthetic",
        synthetic_episode_steps=10,
        synthetic_fault_at=3,
        synthetic_fault_kind=kind,
    ) as v:
        v.reset([1, 2], BLUE, RED)
        for _ in range(2):
            v.step()
            assert v.dones.tolist() == [0, 0]
        v.step()
        assert v.dones.tolist() == [1, 1]
        for i in range(2):
            info = v.info(i)
            assert info["truncated"] == 1
            assert info["winner"] == v.schema.winner_none
            assert v.schema.fault_names[info["fault"]] == kind
            fault = v.fault(i)
            assert fault is not None and fault.kind == kind
            assert "forced" in fault.message
            assert fault.signature.startswith(kind + " ")
            # A synthetic environment plays no battle: it writes no bundle.
            assert fault.bundle is None
            assert v.seat_info(i, 0)["fault_bundle"] == ""
        assert v.rewards.tolist() == [[0.0, 0.0], [0.0, 0.0]]
        assert v.fault_kind_counts()[kind] == 2
        assert sum(v.fault_counts().values()) == 2
        # The next step starts the next episode.
        v.step()
        assert v.dones.tolist() == [0, 0]
        assert v.fault(0) is None


def test_a_saved_export_of_another_schema_is_refused(install, tmp_path):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        path = tmp_path / "metadata.json"
        v.write_metadata(path)
        v.check_metadata(path)
        saved = dict(v.metadata)
        saved["schema"] = {**saved["schema"], "version": v.schema.version + 1}
        with pytest.raises(ValueError, match="schema version"):
            v.check_metadata(saved)
        saved["schema"] = {**v.metadata["schema"], "header_fields": ["tick"]}
        with pytest.raises(ValueError, match="header_fields"):
            v.check_metadata(saved)


def test_autoreset_seeds_cover_every_seed_by_default_and_stay_in_a_range_given(install):
    kwargs = dict(install=install, engine="synthetic", synthetic_episode_steps=1)
    with VectorBattle(3, 1, **kwargs) as plain, VectorBattle(3, 2, **kwargs) as ranged:
        assert plain.autoreset_seeds == (-(2**31), 2**31 - 1)
        assert plain.metadata["autoreset_seeds"] == {"min": -(2**31), "max": 2**31 - 1}
        ranged.set_autoreset_seeds(10_000_000, 2**31 - 1)
        assert ranged.autoreset_seeds == (10_000_000, 2**31 - 1)
        assert ranged.metadata["autoreset_seeds"] == {"min": 10_000_000, "max": 2**31 - 1}
        plain.reset([1, 2, 3], BLUE, RED)
        ranged.reset([1, 2, 3], BLUE, RED)
        assert [ranged.episode_seed(i) for i in range(3)] == [1, 2, 3]
        seen_plain: list[int] = []
        seen_ranged: list[int] = []
        for _ in range(200):
            plain.step()
            ranged.step()
            restarted = np.flatnonzero(ranged.infos[:, ranged.schema.info["reset"]])
            seen_ranged += [ranged.episode_seed(int(i)) for i in restarted]
            restarted = np.flatnonzero(plain.infos[:, plain.schema.info["reset"]])
            seen_plain += [plain.episode_seed(int(i)) for i in restarted]
        assert len(seen_ranged) == len(seen_plain) == 300
        assert all(10_000_000 <= seed < 2**31 for seed in seen_ranged)
        # Without a range the draw takes the whole 32-bit range, negative seeds included.
        assert any(seed < 0 for seed in seen_plain)


def test_the_same_reset_seeds_give_the_same_autoreset_seeds(install):
    kwargs = dict(install=install, engine="synthetic", synthetic_episode_steps=2)
    with VectorBattle(4, 1, **kwargs) as one, VectorBattle(4, 4, **kwargs) as four:
        episodes = []
        for v in (one, four):
            v.set_autoreset_seeds(100, 200)
            v.reset([5, 6, 7, 8], BLUE, RED)
            seeds = []
            for _ in range(30):
                v.step()
                seeds.append([v.episode_seed(i) for i in range(4)])
            episodes.append(seeds)
        assert episodes[0] == episodes[1]
        assert all(100 <= seed <= 200 for row in episodes[0][2:] for seed in row)


def test_an_empty_autoreset_seed_range_is_refused(install):
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        v.set_autoreset_seeds(5, 10)
        with pytest.raises(ValueError, match="no seed"):
            v.set_autoreset_seeds(10, 5)
        with pytest.raises(ValueError, match="32-bit"):
            v.set_autoreset_seeds(0, 2**31)
        assert v.autoreset_seeds == (5, 10)
