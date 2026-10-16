"""The policy's masking, its log-probabilities and its checkpoints (needs torch)."""

import json

import numpy as np
import pytest

torch = pytest.importorskip("torch")

from crforge_env import VectorBattle  # noqa: E402

from crforge_rl.policy import (  # noqa: E402
    Layout,
    Policy,
    PolicyPlayer,
    load_checkpoint,
    pick_device,
    save_checkpoint,
    trim,
)

DECK = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap"]


@pytest.fixture(scope="module")
def synthetic(install):
    with VectorBattle(16, 1, install=install, engine="synthetic", synthetic_entities=30) as v:
        v.reset(list(range(16)), DECK, DECK)
        v.step()
        yield v


def batch(vector):
    count = vector.count
    obs = vector.observations.reshape(2 * count, -1).copy()
    masks = vector.masks.reshape(2 * count, -1).copy()
    return obs, masks


def test_every_sampled_action_is_open_in_its_mask(synthetic):
    torch.manual_seed(0)
    model = Policy(Layout.of(synthetic))
    obs, masks = batch(synthetic)
    # Close every play of half the seats and all but one tile of the others' first slot.
    masks[::2, 1:] = 0
    tiles = model.layout.tiles
    masks[1::2, 1 : 1 + tiles] = 0
    masks[1::2, 1 + 37] = 1
    seen = set()
    for _ in range(50):
        actions, logp, value = model.sample(
            torch.from_numpy(trim(obs, model.layout)), torch.from_numpy(masks)
        )
        actions = actions.numpy()
        assert (masks[np.arange(len(actions)), actions] == 1).all()
        assert torch.isfinite(logp).all() and torch.isfinite(value).all()
        seen.update(actions.tolist())
    assert (np.array(sorted(seen)) > 0).any()  # it does play
    assert 1 + 37 in seen  # the one open tile of the first slot is reachable


def test_the_sampled_log_probability_is_the_evaluated_one(synthetic):
    torch.manual_seed(1)
    model = Policy(Layout.of(synthetic))
    obs, masks = batch(synthetic)
    o, m = torch.from_numpy(trim(obs, model.layout)), torch.from_numpy(masks)
    actions, logp, value = model.sample(o, m)
    logp2, entropy, value2 = model.evaluate(o, m, actions)
    torch.testing.assert_close(logp, logp2)
    torch.testing.assert_close(value, value2)
    assert torch.isfinite(entropy).all() and (entropy >= 0).all()
    # The probabilities of every open action of a seat sum to 1.
    open_actions = torch.from_numpy(np.flatnonzero(masks[0]))
    with torch.no_grad():
        all_logp, _, _ = model.evaluate(
            o[:1].expand(len(open_actions), -1), m[:1].expand(len(open_actions), -1), open_actions
        )
    assert float(all_logp.exp().sum()) == pytest.approx(1.0, abs=1e-4)


def test_split_and_join_are_inverse(synthetic):
    model = Policy(Layout.of(synthetic))
    actions = torch.arange(model.layout.actions)
    first, tile = model.split(actions)
    assert torch.equal(model.join(first, tile), actions)
    assert int(first.max()) == model.layout.hand_slots + model.layout.extras


def test_the_entity_rows_cut_after_the_most_written(synthetic):
    model = Policy(Layout.of(synthetic))
    obs, _ = batch(synthetic)
    cut = trim(obs, model.layout)
    assert cut.shape[1] == model.layout.header_ints + 30 * model.layout.entity_ints
    full = model.encode(torch.from_numpy(obs))[0]
    torch.testing.assert_close(model.encode(torch.from_numpy(cut))[0], full)


def test_a_checkpoint_round_trips_and_refuses_a_vector_it_does_not_fit(synthetic, tmp_path):
    torch.manual_seed(2)
    model = Policy(Layout.of(synthetic))
    path = tmp_path / "model.pt"
    save_checkpoint(path, model, synthetic, deck=DECK, update=3)
    loaded, payload = load_checkpoint(path, synthetic)
    assert payload["deck"] == DECK and payload["update"] == 3
    obs, masks = batch(synthetic)
    o = torch.from_numpy(trim(obs, model.layout))
    torch.testing.assert_close(loaded.encode(o)[0], model.encode(o)[0])
    for key, value in model.state_dict().items():
        torch.testing.assert_close(loaded.state_dict()[key], value)
    player = PolicyPlayer(loaded, torch.device("cpu"))
    actions = player.act(obs, masks, np.random.default_rng(0))
    assert (masks[np.arange(len(actions)), actions] == 1).all()

    # Another schema version is refused, naming it.
    changed = dict(payload)
    changed["metadata"] = json.loads(json.dumps(payload["metadata"]))
    changed["metadata"]["schema"]["version"] += 1
    torch.save(changed, tmp_path / "other_schema.pt")
    with pytest.raises(ValueError, match="schema version"):
        load_checkpoint(tmp_path / "other_schema.pt", synthetic)
    # Another vocabulary size, or another checkpoint format, is refused.
    torch.save({**payload, "vocabulary_size": 7}, tmp_path / "other_vocabulary.pt")
    with pytest.raises(ValueError, match="vocabulary"):
        load_checkpoint(tmp_path / "other_vocabulary.pt", synthetic)
    torch.save({**payload, "format": 99}, tmp_path / "other_format.pt")
    with pytest.raises(ValueError, match="format"):
        load_checkpoint(tmp_path / "other_format.pt", synthetic)


def test_auto_picks_an_available_device():
    device = pick_device("auto")
    assert device.type in ("cuda", "mps", "cpu")
    assert pick_device("cpu").type == "cpu"


@pytest.mark.ladder
def test_a_ladder_checkpoint_refuses_moved_vocabulary_ids(install, tables, tmp_path):
    with VectorBattle(1, 1, install=install, tables=tables) as v:
        model = Policy(Layout.of(v))
        save_checkpoint(tmp_path / "m.pt", model, v)
        load_checkpoint(tmp_path / "m.pt", v)
        payload = torch.load(tmp_path / "m.pt", weights_only=True)
        names = payload["metadata"]["vocabulary"]["names"]
        names[0], names[1] = names[1], names[0]
        torch.save(payload, tmp_path / "moved.pt")
        with pytest.raises(ValueError):
            load_checkpoint(tmp_path / "moved.pt", v)
