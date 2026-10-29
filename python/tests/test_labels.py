"""Replays labelled for behavioural training. The replays are synthetic: episodes of the Ladder
environment written as replays by the env module, labelled back. Nothing here hard-codes game
data: the expected rows are the episode's own."""

import gzip
import json

import numpy as np
import pytest

from crforge_env import FLAGS, LadderConfig, VectorBattle, label_replays
from crforge_env.labels import flag_names
from tests.decks import BLUE, RED, arena


def _episode(install, tables, decisions, seed=5):
    """Plays one battle at 1-tick decisions, each seat a random open play about one decision in
    fifteen; returns its rows, actions and replay document."""
    rng = np.random.default_rng(seed)
    observations, masks, actions = [], [], []
    with VectorBattle(
        1,
        1,
        install=install,
        tables=tables,
        config=LadderConfig(decision_ticks=1, arena=arena(tables)),
    ) as v:
        v.set_autoreset(False)
        v.reset([seed], BLUE, RED)
        for _ in range(decisions):
            if v.dones[0]:
                break
            observations.append(v.observations[0].copy())
            masks.append(v.masks[0].copy())
            chosen = []
            for seat in range(2):
                open_actions = np.flatnonzero(v.masks[0, seat][1:]) + 1
                if len(open_actions) and rng.random() < 1 / 15:
                    chosen.append(int(rng.choice(open_actions)))
                else:
                    chosen.append(0)
            v.actions[0] = chosen
            actions.append(chosen)
            v.step()
        return (
            np.array(observations),
            np.array(masks),
            np.array(actions),
            v.replay_json(0),
            v.data_version,
            v.content_sha,
            v.metadata,
        )


@pytest.mark.ladder
def test_an_episode_written_as_a_replay_is_labelled_with_its_own_rows(install, tables, tmp_path):
    observations, masks, actions, replay, _, _, metadata = _episode(install, tables, 900)
    file = tmp_path / "episode.json"
    file.write_text(replay)

    run = label_replays([file], install, tables, decision_ticks=1, check=True)

    assert run.metadata["schema"] == metadata["schema"]
    assert run.metadata["config"]["decision_ticks"] == 1
    assert run.metadata["vocabulary"]["hash"] == metadata["vocabulary"]["hash"]
    (labels,) = run.replays
    decisions = len(actions)
    assert labels.rows == 2 * decisions
    assert labels.observations.dtype == np.int32 and labels.masks.dtype == np.uint8
    assert np.array_equal(labels.observations, observations.reshape(2 * decisions, -1))
    assert np.array_equal(labels.masks, masks.reshape(2 * decisions, -1))
    assert np.array_equal(labels.seats, np.tile([0, 1], decisions))
    assert np.array_equal(labels.ticks, np.repeat(np.arange(decisions), 2))
    taken = actions.reshape(-1)
    played = taken != 0
    assert played.sum() > 5, "the episode plays cards"
    # A play is labelled with its hand slot; its tile is the one holding the point the screen sent.
    assert np.array_equal(labels.actions != 0, played)
    tiles = run.metadata["schema"]["cols"] * run.metadata["schema"]["rows"]
    assert np.array_equal((labels.actions[played] - 1) // tiles, (taken[played] - 1) // tiles)
    assert (labels.flags == 0).all()
    counts = labels.counts
    assert counts["labelled"] == counts["commands"] == int(played.sum())
    assert counts["refusal"] is None
    assert labels.drive["reproduced"] and labels.drive["state_reproduced"]
    assert labels.drive["matched_commands"] == counts["commands"]
    assert run.totals()["labelled"] == counts["labelled"]


@pytest.mark.ladder
def test_crawl_records_are_labelled_on_their_data_version_and_written_per_replay(
    install, tables, tmp_path
):
    _, _, _, replay, data_version, content_sha, _ = _episode(install, tables, 400, seed=9)
    # A crawl record carries the replay as the server sent it, with no capture block: the record
    # names the data version beside it.
    served = json.loads(replay)
    del served["capture"]
    record = {
        "format": 1,
        "session": {"client_version": "0.0.0", "fetched_at": "2026-10-16T00:00:00Z"},
        "battle": {"content_version": data_version, "content_sha": content_sha},
        "replay": json.dumps(served),
    }
    unsure = dict(record, battle={"candidates": {data_version: content_sha}})
    crawl = tmp_path / "crawl.jsonl.gz"
    with gzip.open(crawl, "wt") as out:
        out.write(json.dumps(record) + "\n" + json.dumps(unsure) + "\n")
    single = tmp_path / "episode.json"
    single.write_text(replay)

    one = label_replays([single, crawl], install, tables, decision_ticks=5, threads=1)
    out = tmp_path / "rows"
    two = label_replays([single, crawl], install, tables, decision_ticks=5, threads=2, out_dir=out)

    assert len(one.replays) == 2
    assert len(one.unreadable) == 1
    assert "candidates" in one.unreadable[0][1]
    first, second = one.replays
    assert np.array_equal(first.observations, second.observations)
    assert np.array_equal(first.actions, second.actions)
    assert (first.ticks % 5 == 0).all()
    counts = first.counts
    assert counts["decision_ticks"] == 5
    assert (
        counts["labelled"] + counts["collisions"] + counts["inexpressible"] + counts["after_end"]
        == counts["commands"]
    )
    # Written per replay, with the metadata and one counts line a replay, and none kept in memory.
    assert two.replays == []
    assert json.loads((out / "metadata.json").read_text()) == one.metadata
    lines = (out / "counts.jsonl").read_text().splitlines()
    assert len(lines) == 2
    for summary, line in zip(two.summaries, lines, strict=True):
        assert json.loads(line) == summary
        saved = np.load(summary["file"])
        assert np.array_equal(saved["observations"], first.observations)
        assert np.array_equal(saved["masks"], first.masks)
        assert np.array_equal(saved["actions"], first.actions)
        assert json.loads(str(saved["counts"])) == counts


def test_a_shape_the_labeller_does_not_read_is_refused(install):
    with pytest.raises(ValueError, match="shape"):
        label_replays([], install, "unused", shape="unknown")


def test_the_flags_are_named_as_the_env_module_numbers_them(install):
    from crforge_env import _jvm

    _jvm.start(install)
    labeller = _jvm.java_class("org.crforge.env.ReplayLabeller")
    assert FLAGS == {
        "label_masked": int(labeller.LABEL_MASKED),
        "collision": int(labeller.COLLISION),
        "inexpressible": int(labeller.INEXPRESSIBLE),
        "point_unmatched": int(labeller.POINT_UNMATCHED),
        "given_as_recorded": int(labeller.GIVEN_AS_RECORDED),
        "unpromised": int(labeller.UNPROMISED),
    }
    assert flag_names(FLAGS["collision"] | FLAGS["unpromised"]) == ["collision", "unpromised"]
