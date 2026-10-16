"""What a run writes besides its checkpoints: the win-rate curve and the faults it met.

Nothing here needs torch.
"""

from __future__ import annotations

import csv
import json
from collections.abc import Iterable, Mapping
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from crforge_env import VectorBattle

CURVE_FIELDS = (
    "update",
    "learner_steps",
    "wall_s",
    "steps_per_s",
    "opponent",
    "games",
    "wins",
    "losses",
    "draws",
    "cut",
    "faults",
    "win_rate",
    "plays_per_game",
)
"""The columns of ``curve.csv``: one row an evaluation against one opponent. ``win_rate`` is the
wins over all games (a draw, a cut episode and a fault are not wins);
``plays_per_game`` is the player's plays given in a game, on average."""


@dataclass
class FaultRecord:
    """One fault signature: how often it was met and the first time."""

    kind: str
    exception: str
    message: str
    count: int = 0
    first_update: int = -1
    first_tick: int = -1


@dataclass
class FaultLog:
    """The faults of a vector by kind and by signature, met during a run.

    A fault ends one battle's episode as truncated with no result; the vector counts them by
    signature and by kind. ``observe`` takes the battles that just ended by a fault and keeps the
    first message of each signature, so a run's log names every distinct fault it found.
    """

    by_signature: dict[str, FaultRecord] = field(default_factory=dict)
    by_kind: dict[str, int] = field(default_factory=dict)

    def observe(self, vector: VectorBattle, battles: Iterable[int], update: int) -> list[str]:
        """Records the faults of the given battles (each ended by a fault on the last step).

        :returns: the signatures met for the first time
        """
        new: list[str] = []
        for battle in battles:
            fault = vector.fault(int(battle))
            if fault is None:
                continue
            self.by_kind[fault.kind] = self.by_kind.get(fault.kind, 0) + 1
            record = self.by_signature.get(fault.signature)
            if record is None:
                record = FaultRecord(fault.kind, fault.exception, fault.message)
                record.first_update = update
                record.first_tick = fault.tick
                self.by_signature[fault.signature] = record
                new.append(fault.signature)
            record.count += 1
        return new

    @property
    def total(self) -> int:
        return sum(self.by_kind.values())

    def summary(self) -> dict[str, Any]:
        """The log as JSON: counts by kind, then each signature, most frequent first."""
        signatures = sorted(self.by_signature.items(), key=lambda item: -item[1].count)
        return {
            "total": self.total,
            "by_kind": dict(sorted(self.by_kind.items())),
            "by_signature": {signature: asdict(record) for signature, record in signatures},
        }

    def write(self, path: Path) -> None:
        path.write_text(json.dumps(self.summary(), indent=1) + "\n", encoding="utf-8")


class Curve:
    """The win-rate curve, appended to ``curve.csv`` one evaluation row at a time."""

    def __init__(self, path: Path):
        self.path = path
        if not path.exists():
            with path.open("w", newline="", encoding="utf-8") as out:
                csv.writer(out).writerow(CURVE_FIELDS)

    def append(self, row: Mapping[str, Any]) -> None:
        missing = set(CURVE_FIELDS) - set(row)
        if missing:
            raise ValueError(f"a curve row lacks {sorted(missing)}")
        with self.path.open("a", newline="", encoding="utf-8") as out:
            csv.writer(out).writerow([row[name] for name in CURVE_FIELDS])


def read_curve(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source))
