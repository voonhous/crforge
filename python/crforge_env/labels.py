"""Replays labelled for behavioural training: (observation, mask, action) rows of each seat.

:func:`label_replays` reads replay files (a replay document ``.json``, or a crawl's JSON Lines
output ``.jsonl``, ``.jsonl.zst`` or ``.jsonl.gz``, as the desktop replay viewer reads them),
steps each replay's battle through the env module's ``org.crforge.env.ReplayLabeller`` and returns
or writes, per replay, numpy arrays: at every decision of each seat, the observation and mask the
Ladder environment shows that seat and the action that gives the command the player gave in the
decision's window, or the wait. The rows are of the same schema as a :class:`VectorBattle`'s, so
a model trained on them plays the environment. Nothing here holds data: any replay of the tables'
data version is labelled. docs/environment.md, "Labelling replays", gives the alignment and the
counts.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from . import _jvm

SHAPES = ("replay", "generated")
"""The kinds of replay read: a replay the game client records, or a generated test case."""

FLAGS = {
    "label_masked": 1,
    "collision": 2,
    "inexpressible": 4,
    "point_unmatched": 8,
    "given_as_recorded": 16,
    "unpromised": 32,
}
"""A row's flags (``ReplayLabeller``'s constants), by name."""


@dataclass
class ReplayLabels:
    """One replay's rows: decision after decision, seat 0's row then seat 1's."""

    source: str
    """the file, with ``:<line>`` for a record of a JSON Lines file"""
    observations: np.ndarray
    """int32 ``(rows, seat_ints)``: each row's observation, in the seat's frame"""
    masks: np.ndarray
    """uint8 ``(rows, actions)``: each row's legality mask"""
    actions: np.ndarray
    """int32 ``(rows,)``: each row's label, an action of the schema (0 the wait)"""
    ticks: np.ndarray
    """int32 ``(rows,)``: each row's decision tick"""
    seats: np.ndarray
    """int32 ``(rows,)``: each row's seat, the game side it plays"""
    flags: np.ndarray
    """int32 ``(rows,)``: each row's flags (:data:`FLAGS`)"""
    counts: dict[str, Any]
    """what became of the replay's commands, by name, with ``decision_ticks``, ``stop_tick`` and
    ``refusal`` (None for a replay labelled to its end)"""
    drive: dict[str, Any] | None = None
    """with ``check``: what a battle driven from the labels alone came to against the replay's"""

    @property
    def rows(self) -> int:
        return int(self.actions.shape[0])

    def save(self, file: str | Path) -> None:
        """Writes the arrays to an ``.npz`` file, the counts and the drive as JSON strings in it."""
        np.savez(
            file,
            observations=self.observations,
            masks=self.masks,
            actions=self.actions,
            ticks=self.ticks,
            seats=self.seats,
            flags=self.flags,
            counts=np.array(json.dumps(self.counts)),
            drive=np.array(json.dumps(self.drive)),
            source=np.array(self.source),
        )


@dataclass
class LabelRun:
    """What :func:`label_replays` labelled: the schema's export, each replay's rows or summary."""

    metadata: dict[str, Any]
    """the env module's metadata export (schema, configuration with the decision interval,
    vocabulary with its data version, content sha and hash), to be kept with the rows; a model
    checks it as it checks a vector's (:meth:`VectorBattle.check_metadata`)"""
    replays: list[ReplayLabels] = field(default_factory=list)
    """each replay's rows, in the files' order; empty when the rows were written to a folder"""
    summaries: list[dict[str, Any]] = field(default_factory=list)
    """each replay's source, counts, drive and the file its rows were written to (or None)"""
    unreadable: list[tuple[str, str]] = field(default_factory=list)
    """each record of a file that could not be read, with why"""

    def totals(self) -> dict[str, int]:
        """The counts summed over the replays, with ``replays`` and ``refused``."""
        total: dict[str, int] = {"replays": len(self.summaries), "refused": 0}
        for summary in self.summaries:
            counts = summary["counts"]
            if counts.get("refusal") is not None:
                total["refused"] += 1
            for name, value in counts.items():
                if name not in ("refusal", "decision_ticks", "stop_tick"):
                    total[name] = total.get(name, 0) + int(value)
        return total


def _ints(java_array) -> np.ndarray:
    return np.array(memoryview(java_array), dtype=np.int32, copy=True)


def _bytes(java_array) -> np.ndarray:
    return np.array(memoryview(java_array), dtype=np.int8, copy=True).view(np.uint8)


def _drive_dict(drive) -> dict[str, Any]:
    return {
        "decisions": int(drive.decisions()),
        "rows_matched": int(drive.rowsMatched()),
        "commands": int(drive.commands()),
        "replay_commands": int(drive.replayCommands()),
        "matched_commands": int(drive.matchedCommands()),
        "points_differ": int(drive.pointsDiffer()),
        "steps_matched": int(drive.stepsMatched()),
        "reproduced": bool(drive.reproduced()),
        "state_reproduced": bool(drive.stateReproduced()),
        "first_divergent_tick": int(drive.firstDivergentTick()),
        "divergence": None if drive.divergence() is None else str(drive.divergence()),
        "stop": None if drive.stop() is None else str(drive.stop()),
    }


def _safe_name(source: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", source)


def label_replays(
    paths: Iterable[str | Path],
    install: str | Path,
    tables: str | Path,
    *,
    decision_ticks: int = 5,
    threads: int = 1,
    shape: str = "replay",
    check: bool = False,
    out_dir: str | Path | None = None,
    jvm_args: Iterable[str] = (),
    mask_version: int | None = None,
) -> LabelRun:
    """Labels every replay of some files.

    :param paths: replay documents (``.json``) and crawl outputs (``.jsonl``, ``.jsonl.zst``,
        ``.jsonl.gz``); a crawl record is read only on the tables of the data version it names
    :param install: the env module's installed distribution (``env/build/install/env``)
    :param tables: the tables folder of the replays' data version
    :param decision_ticks: the decision interval, as the environment's (5, or 1 to decide every
        tick)
    :param threads: replays labelled at once, each whole on one thread
    :param shape: ``replay`` for replays the game client records, ``generated`` for generated
        test cases
    :param check: also drive a battle from each replay's labels alone and check it against the
        replay's (``ReplayLabeller.drive``); its outcome is in each replay's ``drive``
    :param out_dir: when given, each replay's rows are written to ``<out_dir>/<source>.npz``
        (:meth:`ReplayLabels.save`), ``metadata.json`` and ``counts.jsonl`` (one replay a line)
        beside them, and none is kept in memory
    :param jvm_args: options for the JVM, used only if this call starts it
    :param mask_version: the mask rule the rows' masks follow, as ``LadderConfig.mask_version``
        (None for the env module's default, the landing rule 2); under rule 2 a play is labelled
        with the tile it landed on, which the row's mask opens
    :return: the metadata, each replay's rows (or, with ``out_dir``, none) and summaries
    """
    if shape not in SHAPES:
        raise ValueError(f"shape is one of {SHAPES}, got {shape!r}")
    if threads < 1:
        raise ValueError(f"at least one thread, got {threads}")
    _jvm.start(install, tuple(jvm_args))
    path_class = _jvm.java_class("java.nio.file.Path")
    vector_env = _jvm.java_class("org.crforge.env.VectorEnv")
    files = _jvm.java_class("org.crforge.env.ReplayFiles")
    game_tables = vector_env.tables(path_class.of(str(Path(tables).expanduser().resolve())))
    labeller = _jvm.java_class("org.crforge.env.ReplayLabeller").of(
        game_tables, int(decision_ticks), shape
    )
    if mask_version is not None:
        labeller = labeller.withMaskVersion(int(mask_version))
    run = LabelRun(metadata=json.loads(str(labeller.metadataJson())))
    folder = None if out_dir is None else Path(out_dir)
    if folder is not None:
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "metadata.json").write_text(json.dumps(run.metadata) + "\n")

    sources: list[tuple[str, Any]] = []
    for path in paths:
        for source in files.read(path_class.of(str(Path(path).expanduser().resolve()))):
            name = str(source.name())
            if source.document() is None:
                run.unreadable.append((name, str(source.problem())))
            else:
                sources.append((name, source.document()))

    def one(item: tuple[str, Any]) -> tuple[ReplayLabels | None, dict[str, Any]]:
        name, document = item
        labels = labeller.label(document)
        drive = _drive_dict(labeller.drive(document, labels)) if check else None
        replay = ReplayLabels(
            source=name,
            observations=_ints(labels.observations()).reshape(
                -1, run.metadata["schema"]["seat_ints"]
            ),
            masks=_bytes(labels.masks()).reshape(-1, run.metadata["schema"]["actions"]),
            actions=_ints(labels.actions()),
            ticks=_ints(labels.ticks()),
            seats=_ints(labels.seats()),
            flags=_ints(labels.flags()),
            counts=json.loads(str(labels.countsJson())),
            drive=drive,
        )
        written = None
        if folder is not None:
            written = folder / f"{_safe_name(name)}.npz"
            replay.save(written)
        summary = {
            "source": name,
            "counts": replay.counts,
            "drive": drive,
            "file": None if written is None else str(written),
        }
        return (None if folder is not None else replay), summary

    if threads == 1:
        results = [one(item) for item in sources]
    else:
        with ThreadPoolExecutor(max_workers=threads) as pool:
            results = list(pool.map(one, sources))
    for replay, summary in results:
        if replay is not None:
            run.replays.append(replay)
        run.summaries.append(summary)
    if folder is not None:
        with (folder / "counts.jsonl").open("w") as out:
            for summary in run.summaries:
                out.write(json.dumps(summary) + "\n")
    return run


def flag_names(flags: int) -> list[str]:
    """The names of a row's flags."""
    return [name for name, bit in FLAGS.items() if flags & bit]
