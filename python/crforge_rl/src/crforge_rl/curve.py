"""The win-rate curve as a picture, and the command ``crforge-rl-plot``: ``curve.csv`` drawn as
``curve.png``, one line an opponent. Needs matplotlib (the plot extra)."""

from __future__ import annotations

import argparse
from pathlib import Path

from .records import read_curve


def plot(source: Path, target: Path) -> None:
    """Draws the win rate of each opponent against the learner's decisions; nothing for a curve
    of no rows."""
    import matplotlib

    matplotlib.use("Agg")
    from matplotlib import pyplot

    rows = read_curve(source)
    if not rows:
        return  # a run with no evaluation has no curve
    figure, axes = pyplot.subplots(figsize=(7, 4), dpi=120)
    for opponent in dict.fromkeys(row["opponent"] for row in rows):
        mine = [row for row in rows if row["opponent"] == opponent]
        axes.plot(
            [int(row["learner_steps"]) for row in mine],
            [float(row["win_rate"]) for row in mine],
            marker="o",
            markersize=3,
            linewidth=1.5,
            label=f"vs {opponent}",
        )
    axes.set_xlabel("learner decisions")
    axes.set_ylabel("win rate")
    axes.set_ylim(0.0, 1.0)
    axes.grid(alpha=0.3)
    axes.spines[["top", "right"]].set_visible(False)
    axes.legend(frameon=False)
    figure.tight_layout()
    figure.savefig(target)
    pyplot.close(figure)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="crforge-rl-plot", description=__doc__)
    parser.add_argument("curve", type=Path, help="a run's curve.csv")
    parser.add_argument("--out", type=Path, help="the picture, curve.png next to it by default")
    args = parser.parse_args(argv)
    plot(args.curve, args.out or args.curve.with_name("curve.png"))


if __name__ == "__main__":
    main()
