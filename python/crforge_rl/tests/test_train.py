"""A tiny training on the CPU: it runs, writes its run folder, and counts faults (needs torch)."""

import csv
import json
import math

import pytest

torch = pytest.importorskip("torch")

from crforge_env import VectorBattle  # noqa: E402

from crforge_rl.policy import load_checkpoint  # noqa: E402
from crforge_rl.train import TrainConfig, train  # noqa: E402


def tiny(install, tmp_path, **overrides) -> TrainConfig:
    values = dict(
        install=str(install),
        engine="synthetic",
        run_dir=str(tmp_path / "run"),
        battles=6,
        threads=2,
        steps=12,
        updates=3,
        epochs=2,
        minibatches=2,
        eval_every=2,
        eval_games=4,
        eval_opponents="random,wait",
        device="cpu",
        synthetic_episode_steps=8,
        model=dict(embedding=8, entity_hidden=16, trunk=32, tile_hidden=16),
    )
    values.update(overrides)
    return TrainConfig(**values)


def test_a_tiny_training_runs_and_writes_its_run(install, tmp_path):
    lines = []
    result = train(tiny(install, tmp_path), log=lines.append)
    run = tmp_path / "run"
    assert result.updates == 3
    # Invalid samples (the reset steps after each end) are not counted as decisions.
    assert 0 < result.learner_steps < 3 * 12 * 6
    assert all(math.isfinite(v) for v in result.last_losses.values())
    for name in ("config.json", "metadata.json", "log.jsonl", "faults.json", "latest.pt"):
        assert (run / name).is_file(), name
    assert (run / "update-2.pt").is_file() and (run / "update-3.pt").is_file()
    logged = [json.loads(line) for line in (run / "log.jsonl").read_text().splitlines()]
    assert [line["update"] for line in logged] == [1, 2, 3]
    assert all(math.isfinite(line["value_loss"]) for line in logged)
    assert sum(sum(line["train_games"].values()) for line in logged) > 0
    # The play rate is of each update's own decisions: a synthetic mask opens about a quarter of
    # the tiles of every slot, so an untrained policy plays most decisions in every update.
    assert all(line["plays_per_1000_steps"] > 500 for line in logged)
    with (run / "curve.csv").open() as source:
        rows = list(csv.DictReader(source))
    # Evaluations before training, at update 2 and at the end, against both opponents.
    assert [(r["update"], r["opponent"]) for r in rows] == [
        (u, o) for u in ("0", "2", "3") for o in ("random", "wait")
    ]
    assert all(int(r["games"]) == 4 for r in rows)
    with VectorBattle(1, 1, install=install, engine="synthetic") as v:
        model, payload = load_checkpoint(run / "latest.pt", v)
    assert payload["update"] == 3 and payload["learner_steps"] == result.learner_steps
    assert all(torch.isfinite(p).all() for p in model.parameters())


def test_self_play_trains_on_both_seats(install, tmp_path):
    result = train(
        tiny(install, tmp_path, mode="self", updates=1, eval_games=0), log=lambda _: None
    )
    assert result.updates == 1
    assert result.learner_steps > 6 * 12  # both seats of six battles


def test_faults_end_episodes_as_truncated_and_are_logged(install, tmp_path):
    lines = []
    result = train(
        tiny(install, tmp_path, synthetic_fault_at=3, synthetic_fault_kind="bug", eval_games=0),
        log=lines.append,
    )
    assert result.faults["by_kind"]["bug"] > 0
    (signature,) = result.faults["by_signature"]
    assert signature.startswith("bug ")
    assert any(line.startswith("new fault (bug)") for line in lines)
    summary = json.loads((tmp_path / "run" / "faults.json").read_text())
    assert summary["by_kind"]["bug"] == result.faults["by_kind"]["bug"]
    logged = [json.loads(x) for x in (tmp_path / "run" / "log.jsonl").read_text().splitlines()]
    # A fault is never a result.
    assert all(line["train_games"]["won"] + line["train_games"]["lost"] == 0 for line in logged)


def test_the_curve_is_drawn_with_the_plot_extra(install, tmp_path):
    pytest.importorskip("matplotlib")
    train(tiny(install, tmp_path, updates=2), log=lambda _: None)
    assert (tmp_path / "run" / "curve.png").stat().st_size > 0


@pytest.mark.ladder
def test_a_short_ladder_training_runs(install, tables, tmp_path):
    result = train(
        tiny(
            install,
            tmp_path,
            engine="ladder",
            tables=str(tables),
            battles=4,
            steps=8,
            updates=1,
            eval_games=2,
            eval_opponents="random,scripted",
        ),
        log=lambda _: None,
    )
    assert result.updates == 1
    assert [e["opponent"] for e in result.evaluations[-2:]] == ["random", "scripted"]
