"""Evaluation: a policy against an opponent over many episodes, counted as wins, losses and draws.

:func:`evaluate` plays ``episodes`` episodes of ``policy`` against ``opponent`` on a vector of
battles and returns an :class:`EvaluationResult`. Both are policies of the opponent's shape,
``policy(observation, mask, rng) -> action`` for one seat (a trained model is wrapped in such a
function); one with a ``bind(vector)`` method, such as :class:`ScriptedBot`, is bound to the
vector first.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from .vector import LadderConfig, VectorBattle

Policy = Callable[[np.ndarray, np.ndarray, np.random.Generator], int]
"""A seat's policy: given its observation, its mask and a random generator, an action."""


@dataclass
class EvaluationResult:
    """The episodes of an evaluation, counted from the policy's seat."""

    episodes: int = 0
    wins: int = 0
    losses: int = 0
    draws: int = 0
    truncated: int = 0
    """episodes cut short with no result: by the tick limit or by a fault"""
    faults: dict[str, int] = field(default_factory=dict)
    """the faulted episodes by kind (``refusal``, ``bug``, ``env_bug``); they are also truncated"""
    illegal: int = 0
    """the policy's actions refused as illegal, over all episodes"""

    @property
    def win_rate(self) -> float:
        """Wins over all episodes, truncated ones included (0 for none)."""
        return self.wins / self.episodes if self.episodes else 0.0


def _bind(policy: Policy, vector: VectorBattle) -> None:
    bind = getattr(policy, "bind", None)
    if callable(bind):
        bind(vector)


def evaluate(
    policy: Policy,
    opponent: Policy,
    *,
    deck: Sequence[str],
    episodes: int,
    install: str | Path,
    opponent_deck: Sequence[str] | None = None,
    tables: str | Path | None = None,
    engine: str = "ladder",
    config: LadderConfig | None = None,
    envs: int = 8,
    threads: int = 1,
    seed: int = 0,
    **vector_kwargs: Any,
) -> EvaluationResult:
    """Plays ``episodes`` episodes of ``policy`` (with ``deck``) against ``opponent`` (with
    ``opponent_deck``, ``deck`` when None) and counts them from the policy's seat.

    The episodes run on a vector of ``envs`` battles stepped on ``threads`` JVM threads; the
    policy plays seat 0 in the even environments and seat 1 in the odd ones, so an even ``envs``
    gives each side half the episodes. Each environment plays a fixed share of the episodes, the
    first ones of the seed sequence its battle seed starts, so the counted episodes do not depend
    on which ones end first. The run is reproduced by its ``seed``: the battle seeds and each
    seat's generator are drawn from it.

    ``install``, ``tables``, ``engine``, ``config`` and the remaining keywords (``synthetic_*``,
    ``jvm_args``) go to :class:`VectorBattle`.
    """
    if episodes < 1:
        raise ValueError("an evaluation plays at least one episode")
    envs = min(envs, episodes)
    opponent_deck = deck if opponent_deck is None else opponent_deck
    quota = [episodes // envs + (1 if i < episodes % envs else 0) for i in range(envs)]
    policy_seat = [i % 2 for i in range(envs)]
    decks = [
        [list(deck) if policy_seat[i] == seat else list(opponent_deck) for i in range(envs)]
        for seat in (0, 1)
    ]
    seeds = np.random.default_rng(seed).integers(0, 2**31, envs)
    rngs = [[np.random.default_rng([seed, i, seat]) for seat in (0, 1)] for i in range(envs)]
    result = EvaluationResult()
    faults: Counter[str] = Counter()
    with VectorBattle(
        envs, threads, install=install, engine=engine, tables=tables, config=config, **vector_kwargs
    ) as vector:
        _bind(policy, vector)
        _bind(opponent, vector)
        s = vector.schema
        illegal_field = [s.info["illegal_0"], s.info["illegal_1"]]
        vector.reset([int(x) for x in seeds], decks[0], decks[1])
        counted = [0] * envs
        while any(counted[i] < quota[i] for i in range(envs)):
            for i in range(envs):
                for seat in (0, 1):
                    # A finished environment's actions are ignored (the step resets it), as are
                    # those of one whose share is played.
                    if vector.dones[i] or counted[i] >= quota[i]:
                        vector.actions[i, seat] = s.wait
                        continue
                    player = policy if seat == policy_seat[i] else opponent
                    vector.actions[i, seat] = int(
                        player(vector.observations[i, seat], vector.masks[i, seat], rngs[i][seat])
                    )
            vector.step()
            for i in range(envs):
                if counted[i] >= quota[i]:
                    continue
                result.illegal += int(vector.infos[i, illegal_field[policy_seat[i]]])
                if not vector.dones[i]:
                    continue
                counted[i] += 1
                result.episodes += 1
                winner = int(vector.infos[i, s.info["winner"]])
                fault = int(vector.infos[i, s.info["fault"]])
                if fault:
                    faults[s.fault_names[fault]] += 1
                if winner == policy_seat[i]:
                    result.wins += 1
                elif winner == 1 - policy_seat[i]:
                    result.losses += 1
                elif winner == s.winner_draw:
                    result.draws += 1
                else:
                    result.truncated += 1
    result.faults = dict(faults)
    return result
