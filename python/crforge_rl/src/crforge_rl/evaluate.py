"""Evaluation: a player against an opponent over a fixed set of battles, and the command
``crforge-rl-eval`` that evaluates a checkpoint.

The player and the opponent are any two :class:`crforge_rl.opponents.Player`: the trained policy,
a frozen checkpoint, the random opponent, a scripted bot. Each battle of the evaluation vector is
played once, the player on seat 0 in even battles and on seat 1 in odd ones, and the same seeds
give the same battles at every evaluation, so the curve compares like with like.

It counts as ``crforge_env.evaluate`` does, but asks each player once a decision for all its
seats of the vector (``Player.act`` on a batch), so a network runs one forward pass a decision
rather than one a battle.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
from crforge_env import LadderConfig, VectorBattle

from .opponents import Player, load_opponent
from .rollout import Seats


@dataclass(frozen=True)
class EvalResult:
    """The outcome of an evaluation from the player's side."""

    opponent: str
    games: int
    wins: int
    losses: int
    draws: int
    cut: int
    """episodes cut short by the tick limit, with no result"""
    faults: int
    """episodes ended by a fault, with no result"""
    plays_per_game: float
    """the player's plays given in a game, on average: a policy that never plays wins nothing,
    and one that plays every open action leaks no elixir; both show here"""

    @property
    def win_rate(self) -> float:
        """Wins over all games, as ``crforge_env.evaluate`` counts it: a draw, a cut episode and a
        fault are not wins."""
        return self.wins / self.games if self.games else 0.0


def evaluate(
    vector: VectorBattle,
    player: Player,
    opponent: Player,
    deck: list[str],
    seeds: list[int],
    rng: np.random.Generator,
    max_steps: int = 10_000,
) -> EvalResult:
    """Plays every battle of ``vector`` once, ``player`` against ``opponent``, on one deck.

    :param seeds: one battle seed a battle of the vector
    :param max_steps: decisions after which the evaluation stops (a safety net: a Ladder battle
        ends by itself, in about 740 decisions with no overtime)
    """
    count = vector.count
    seats = Seats.of(count, "random")
    schema = vector.schema
    vector.set_autoreset(True)
    vector.reset(seeds, deck, deck)
    flat_obs = vector.observations.reshape(2 * count, schema.seat_ints)
    flat_masks = vector.masks.reshape(2 * count, schema.actions)
    flat_actions = vector.actions.reshape(2 * count)
    running = ~vector.dones.astype(bool)  # a battle that faulted at its start is over already
    outcome = np.full(count, -1, dtype=np.int64)  # 0 win, 1 loss, 2 draw, 3 cut, 4 fault
    plays = np.zeros(count, dtype=np.int64)
    winner_at = schema.info["winner"]
    fault_at = schema.info["fault"]
    truncated_at = schema.info["truncated"]
    for battle in np.flatnonzero(~running):
        outcome[battle] = 4
    steps = 0
    while running.any() and steps < max_steps:
        active = np.flatnonzero(running)
        mine = seats.learner[active]
        theirs = seats.opponent[active]
        flat_actions[:] = 0
        chosen = player.act(flat_obs[mine], flat_masks[mine], rng)
        flat_actions[mine] = chosen
        flat_actions[theirs] = opponent.act(flat_obs[theirs], flat_masks[theirs], rng)
        plays[active] += chosen != 0
        vector.step()
        steps += 1
        ended = active[vector.dones[active] != 0]
        for battle in ended:
            info = vector.infos[battle]
            if info[fault_at] != 0:
                outcome[battle] = 4
            elif info[truncated_at] != 0:
                outcome[battle] = 3
            elif info[winner_at] == schema.winner_draw:
                outcome[battle] = 2
            else:
                outcome[battle] = 0 if info[winner_at] == battle % 2 else 1
        running[ended] = False
    outcome[running] = 3
    return EvalResult(
        opponent=opponent.name,
        games=count,
        wins=int((outcome == 0).sum()),
        losses=int((outcome == 1).sum()),
        draws=int((outcome == 2).sum()),
        cut=int((outcome == 3).sum()),
        faults=int((outcome == 4).sum()),
        plays_per_game=float(plays.mean()),
    )


def eval_seeds(count: int, base: int = 1_000_000) -> list[int]:
    """The fixed battle seeds of an evaluation of ``count`` battles."""
    return [base + i for i in range(count)]


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="crforge-rl-eval",
        description="Evaluate a checkpoint against opponents on Ladder battles of its own deck.",
    )
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--install", required=True, help="env/build/install/env")
    parser.add_argument("--tables", required=True, help="the tables folder of a data version")
    parser.add_argument(
        "--opponents",
        default="random",
        help="comma-separated: random, wait, checkpoint:<path>, <module>:<name>",
    )
    parser.add_argument("--games", type=int, default=128)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--device", default="auto", help="auto (cuda, else mps, else cpu)")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)

    from .policy import load_player  # torch is needed from here on

    probe = load_player(args.checkpoint, device=args.device)
    config = LadderConfig(**probe.checkpoint["ladder_config"])
    with VectorBattle(
        args.games, args.threads, install=args.install, tables=args.tables, config=config
    ) as vector:
        player = load_player(args.checkpoint, device=args.device, vector=vector)
        rng = np.random.default_rng(args.seed)
        for spec in args.opponents.split(","):
            opponent = load_opponent(spec, device=args.device, vector=vector)
            result = evaluate(
                vector, player, opponent, player.checkpoint["deck"], eval_seeds(args.games), rng
            )
            print(json.dumps({**asdict(result), "win_rate": round(result.win_rate, 4)}))


if __name__ == "__main__":
    main()
