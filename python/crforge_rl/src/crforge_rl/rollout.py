"""The rollout's bookkeeping in numpy: which seats learn, the shaping reward, the advantages.

Nothing here needs torch, so the CI job without torch tests it.

The vector resets a finished battle on the step after its end (autoreset): that step ignores the
battle's actions and reports the new episode's first observation. A sample taken on such a step is
not a decision, so it is marked invalid: it is left out of the loss, and its advantage is 0.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from crforge_env import Schema

MODES = ("random", "self")


@dataclass(frozen=True)
class Seats:
    """The seats the learner plays in a vector of ``count`` battles, and the others.

    - ``random`` mode: the learner plays one seat of each battle, seat 0 in even battles and
      seat 1 in odd ones (the seat frames make the two sides the same game for a policy), and the
      opponent the other
    - ``self`` mode: the learner plays both seats of every battle; there is no opponent

    Seats are numbered flat, ``2 * battle + seat``, as ``observations.reshape(2 * count, ...)``
    lays them out.
    """

    count: int
    learner: np.ndarray
    opponent: np.ndarray

    @staticmethod
    def of(count: int, mode: str) -> Seats:
        if mode not in MODES:
            raise ValueError(f"a mode is one of {MODES}, not {mode!r}")
        battles = np.arange(count)
        if mode == "self":
            learner = np.arange(2 * count)
            opponent = np.zeros(0, dtype=np.int64)
        else:
            learner = 2 * battles + battles % 2
            opponent = 2 * battles + (1 - battles % 2)
        return Seats(count, learner, opponent)

    def battle(self, flat: np.ndarray) -> np.ndarray:
        """The battle of each flat seat."""
        return flat // 2

    def side(self, flat: np.ndarray) -> np.ndarray:
        """The side (0 or 1) of each flat seat."""
        return flat % 2


_TOWERS = ("king", "left", "right")


def tower_potential(schema: Schema, observations: np.ndarray) -> np.ndarray:
    """Each seat's tower balance: the share of the enemy's tower hit points lost less the share of
    its own lost, summed over the three towers of each side, so between -3 and 3.

    The shaping reward is its change from one decision to the next. It is the same quantity seen
    from the other side with the sign turned, so the shaping of a battle's two seats sums to 0,
    and potential-based, so it does not change which policy is best (Ng, Harada and Russell 1999)
    with a discount of 1. A seat whose observation was cleared (by a fault) reads 0.
    """
    total = np.zeros(len(observations), dtype=np.float64)
    for owner, sign in (("own", -1.0), ("enemy", 1.0)):
        for tower in _TOWERS:
            hp = observations[:, schema.header[f"{owner}_{tower}_hp"][0]].astype(np.float64)
            top = observations[:, schema.header[f"{owner}_{tower}_max_hp"][0]].astype(np.float64)
            lost = np.where(top > 0, 1.0 - hp / np.maximum(top, 1.0), 0.0)
            total += sign * lost
    return total


FULL_ELIXIR = 100_000
"""Ten elixir bars in the observation's unit (ten-thousandths of a bar): the most a king holds."""


def leaking(
    schema: Schema, observations: np.ndarray, masks: np.ndarray, actions: np.ndarray
) -> np.ndarray:
    """The seats that waited at full elixir with a play open: elixir the seat could have spent
    is being lost. The elixir counted is the king's less what the seat's waiting plays hold back.
    """
    elixir = observations[:, schema.header["elixir"][0]].astype(np.int64)
    held = observations[:, schema.header["elixir_reserved"][0]].astype(np.int64)
    could_play = masks[:, 1:].any(axis=1)
    return (elixir - held >= FULL_ELIXIR) & could_play & (actions == schema.wait)


def advantages(
    rewards: np.ndarray,
    values: np.ndarray,
    terminated: np.ndarray,
    truncated: np.ndarray,
    valid: np.ndarray,
    next_values: np.ndarray,
    gamma: float,
    lam: float,
) -> tuple[np.ndarray, np.ndarray]:
    """Generalized advantage estimation over a rollout of ``T`` decisions of ``M`` seats.

    Arrays are ``(T, M)``; ``next_values`` ``(M,)`` is the value of the observation after the
    rollout's last step.

    - a terminated step (the match ended) does not bootstrap
    - a truncated step (a fault, or the tick limit: no result) bootstraps from the value of the
      step's own observation, as the next one is cleared after a fault: the episode is treated as
      going on, never as lost or won
    - an invalid step (the autoreset step after an end) gets advantage 0 and cuts the trace

    :returns: ``(advantages, returns)``, float32 ``(T, M)``
    """
    steps = len(rewards)
    advantage = np.zeros_like(values, dtype=np.float64)
    running = np.zeros(values.shape[1], dtype=np.float64)
    for t in reversed(range(steps)):
        following = next_values if t == steps - 1 else values[t + 1]
        bootstrap = np.where(truncated[t], values[t], following)
        cont = ~(terminated[t] | truncated[t])
        delta = rewards[t] + gamma * bootstrap * ~terminated[t] - values[t]
        running = delta + gamma * lam * cont * running
        running = np.where(valid[t], running, 0.0)
        advantage[t] = running
    returns = advantage + values
    return advantage.astype(np.float32), returns.astype(np.float32)
