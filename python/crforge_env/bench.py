"""The Python-side benchmark: environment steps a second through the bridge, by batch size.

One trial steps a :class:`VectorBattle` of ``count`` environments for a fixed number of decisions
with random open actions drawn in numpy, as a trainer would draw them, and times the whole loop:
the action draw, the JVM call and the reads of the results. The ``synthetic`` engine measures the
bridge with no battle behind it; the ``ladder`` engine measures Ladder battles on the game tables
(``--tables``, required for it).

Run it with ``crforge-env-bench --install <env/build/install/env> --tables <folder>`` or
``python -m crforge_env.bench``; ``--json`` writes the rows to a file beside the table it prints.
"""

from __future__ import annotations

import argparse
import json
import platform
import statistics
import sys
import time
from dataclasses import asdict, dataclass

import numpy as np

from .vector import ENGINES, LadderConfig, VectorBattle

BLUE = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap"]
"""Side 0's deck: card row names of the tables."""
RED = ["Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins"]
"""Side 1's deck."""


@dataclass
class Row:
    engine: str
    count: int
    threads: int
    steps: int
    seconds: float
    env_steps_per_s: float
    ticks_per_s: float
    us_per_call: float
    us_per_env_step: float
    draw_us_per_call: float
    obs_mb_per_step: float
    repeats: int
    spread_pct: float


def draw_actions(masks: np.ndarray, rng: np.random.Generator, play_rate: float) -> np.ndarray:
    """A random open play for each seat with probability ``play_rate``, else the wait.

    The seats that play pick the open action of the highest priority, under a random permutation
    of the actions rolled by a random offset each call: every open action can be chosen, and the
    work is one small integer pass over the playing seats' masks, about what sampling a policy's
    output costs, rather than a random key per action. A seat with only the wait open waits.
    """
    count = masks.shape[0]
    actions = np.zeros((count, 2), dtype=np.int32)
    play = rng.random((count, 2)) < play_rate
    if play.any():
        n = masks.shape[2]
        priority = (_priority(n) + rng.integers(n)) % n + 1
        open_plays = masks[play].astype(np.int32)
        open_plays[:, 0] = 0
        scored = open_plays * priority
        chosen = scored.argmax(axis=1)
        chosen[scored.max(axis=1) == 0] = 0
        actions[play] = chosen
    return actions


_PRIORITY: dict[int, np.ndarray] = {}


def _priority(n: int) -> np.ndarray:
    """A fixed random permutation of the actions, drawn once."""
    if n not in _PRIORITY:
        _PRIORITY[n] = np.random.default_rng(12345).permutation(n).astype(np.int32)
    return _PRIORITY[n]


def trial(
    vector: VectorBattle, steps: int, rng: np.random.Generator, play_rate: float
) -> tuple[float, float, int]:
    """Seconds for ``steps`` decisions, the seconds of that spent drawing actions, and the battle
    ticks stepped."""
    tick = vector.schema.info["tick"]
    drawing = 0.0
    ticks = 0
    before = vector.infos[:, tick].astype(np.int64)
    start = time.perf_counter()
    for _ in range(steps):
        t = time.perf_counter()
        actions = draw_actions(vector.masks, rng, play_rate)
        drawing += time.perf_counter() - t
        vector.step(actions)
        # The reads a trainer makes every step: the rewards and dones, and here the ticks.
        _ = vector.rewards.sum()
        _ = vector.dones.any()
        after = vector.infos[:, tick].astype(np.int64)
        ticks += int(np.where(after >= before, after - before, after).sum())
        before = after
    return time.perf_counter() - start, drawing, ticks


def run(
    engine: str,
    counts: list[int],
    threads: int,
    steps: int,
    repeats: int,
    play_rate: float,
    install: str,
    tables: str | None,
    config: LadderConfig | None,
) -> list[Row]:
    rows: list[Row] = []
    for count in counts:
        with VectorBattle(
            count,
            threads,
            install=install,
            engine=engine,
            tables=tables if engine == "ladder" else None,
            config=config if engine == "ladder" else None,
        ) as vector:
            rng = np.random.default_rng(1)
            vector.reset(list(range(1, count + 1)), BLUE, RED)
            # Warm the JIT and the caches on the first decisions.
            trial(vector, max(steps // 5, 10), rng, play_rate)
            results = [trial(vector, steps, rng, play_rate) for _ in range(repeats)]
            seconds = [r[0] for r in results]
            best = min(range(repeats), key=lambda i: seconds[i])
            spread = (max(seconds) - seconds[best]) / seconds[best] * 100
            per_call = seconds[best] / steps
            rows.append(
                Row(
                    engine=engine,
                    count=count,
                    threads=vector.threads,
                    steps=steps,
                    seconds=seconds[best],
                    env_steps_per_s=count * steps / seconds[best],
                    ticks_per_s=results[best][2] / seconds[best],
                    us_per_call=per_call * 1e6,
                    us_per_env_step=per_call / count * 1e6,
                    draw_us_per_call=min(r[1] for r in results) / steps * 1e6,
                    obs_mb_per_step=vector.observations.nbytes / 1e6,
                    repeats=repeats,
                    spread_pct=spread,
                )
            )
            print(format_row(rows[-1]), flush=True)
    return rows


def format_header() -> str:
    return (
        "| engine | envs | threads | env steps/s | ticks/s | us per call | us per env step "
        "| draw us per call | obs MB | spread |\n"
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
    )


def format_row(r: Row) -> str:
    ticks = f"{r.ticks_per_s:,.0f}" if r.engine == "ladder" else "-"
    return (
        f"| {r.engine} | {r.count} | {r.threads} | {r.env_steps_per_s:,.0f} | {ticks} "
        f"| {r.us_per_call:,.1f} | {r.us_per_env_step:,.2f} | {r.draw_us_per_call:,.1f} "
        f"| {r.obs_mb_per_step:.2f} | {r.spread_pct:.0f}% |"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument(
        "--install",
        required=True,
        help="the env module's installed distribution (./gradlew :env:installDist makes "
        "env/build/install/env)",
    )
    parser.add_argument(
        "--tables", default=None, help="the game tables folder of a data version (ladder)"
    )
    parser.add_argument("--engine", choices=ENGINES, nargs="+", default=list(ENGINES))
    parser.add_argument("--counts", type=int, nargs="+", default=[1, 16, 64, 256])
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--steps", type=int, default=200, help="decisions per trial")
    parser.add_argument("--repeats", type=int, default=3, help="trials; the best is reported")
    parser.add_argument("--play-rate", type=float, default=0.3)
    parser.add_argument("--decision-ticks", type=int, default=None)
    parser.add_argument("--json", default=None, help="write the rows to this file")
    args = parser.parse_args(argv)
    if "ladder" in args.engine and args.tables is None:
        parser.error("the ladder engine needs --tables, the game tables folder of a data version")
    config = LadderConfig(decision_ticks=args.decision_ticks)
    print(
        f"# crforge-env bench: {' and '.join(args.engine)}, threads {args.threads}, "
        f"{args.steps} steps x {args.repeats}, play rate {args.play_rate}, python "
        f"{platform.python_version()} numpy {np.__version__} on {platform.machine()}"
    )
    print(format_header())
    rows: list[Row] = []
    for engine in args.engine:
        rows += run(
            engine,
            args.counts,
            args.threads,
            args.steps,
            args.repeats,
            args.play_rate,
            args.install,
            args.tables,
            config,
        )
    if args.json:
        with open(args.json, "w", encoding="utf-8") as out:
            json.dump([asdict(r) for r in rows], out, indent=2)
            out.write("\n")
    print(f"# median spread over repeats {statistics.median(r.spread_pct for r in rows):.0f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
