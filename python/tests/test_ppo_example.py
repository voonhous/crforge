"""The example trainer (``examples/ppo.py``) run as a user runs it, on synthetic environments.

torch is not a dependency of the package, so the tests are skipped without it, and the run says so.
"""

import csv
import subprocess
import sys
from pathlib import Path

import pytest

torch = pytest.importorskip("torch", reason="the example trainer needs torch")

EXAMPLE = Path(__file__).resolve().parent.parent / "examples" / "ppo.py"


def run(install, out: Path, *arguments: str) -> list[dict[str, str]]:
    """Runs a tiny training into ``out`` and returns its curve's rows."""
    command = [sys.executable, str(EXAMPLE), "--install", str(install), "--engine", "synthetic"]
    sizes = ["--envs", "4", "--threads", "1", "--steps", "16", "--entities", "8"]
    evaluations = ["--eval-every", "2", "--eval-episodes", "4", "--updates", "3"]
    done = subprocess.run(
        [*command, "--out", str(out), *sizes, *evaluations, *arguments],
        capture_output=True,
        text=True,
        timeout=300,
    )
    assert done.returncode == 0, done.stderr
    with (out / "curve.csv").open(newline="") as file:
        return list(csv.DictReader(file))


@pytest.mark.parametrize("opponent", ["random", "self"])
def test_a_tiny_training_writes_its_curve_and_checkpoint(install, tmp_path, opponent):
    rows = run(install, tmp_path, "--opponent", opponent)
    # An evaluation before the first update, one every second update and one at the end.
    assert [int(row["update"]) for row in rows] == [0, 2, 3]
    learner_seats = 8 if opponent == "self" else 4
    assert [int(row["decisions"]) for row in rows] == [0, 32 * learner_seats, 48 * learner_seats]
    for row in rows:
        assert int(row["episodes"]) == 4
        counted = sum(int(row[name]) for name in ("wins", "losses", "draws", "truncated"))
        assert counted == 4
    checkpoint = torch.load(tmp_path / "policy.pt", weights_only=False)
    assert checkpoint["update"] == 3
    assert checkpoint["entities"] == 8
    assert checkpoint["metadata"]["schema"]["actions"] > 0
    assert all(torch.isfinite(weights).all() for weights in checkpoint["model"].values())
    assert (tmp_path / "metadata.json").is_file()
    # The provenance record: every role reads the v4 observation as its seat is shown it, but the
    # random opponent, which reads no field.
    record = checkpoint["provenance"]
    assert record["registry"] == checkpoint["metadata"]["fields"]["version"]
    roles = record["roles"]
    assert set(roles) == {"critic", "eval_opponent", "learner", "opponent"}
    for role in ("learner", "critic"):
        assert roles[role]["adapters"] == ["v4"] and roles[role]["hidden"] is False
        assert "entity.hp" in roles[role]["reads"]
    assert roles["eval_opponent"]["adapters"] == []
    assert roles["opponent"]["adapters"] == (["v4"] if opponent == "self" else [])
    assert not any(role["hidden"] for role in roles.values())


def test_episode_ends_are_left_out_of_the_decisions(install, tmp_path):
    # Synthetic episodes of 5 decisions: every sixth step restarts a battle and is no decision.
    rows = run(install, tmp_path, "--synthetic-episode-steps", "5")
    assert int(rows[-1]["decisions"]) == 4 * 40
