"""A PettingZoo parallel environment: one battle, both seats acting each decision."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Any

import numpy as np
from gymnasium import spaces
from pettingzoo import ParallelEnv

from .gym_env import BattleFaultError, battle_seed, observation_space
from .vector import LadderConfig, VectorBattle

SEATS = ("seat_0", "seat_1")
"""The agents: ``seat_0`` plays side 0, ``seat_1`` side 1."""


class ClashParallelEnv(ParallelEnv):
    """Both players of one battle as the agents ``seat_0`` and ``seat_1``.

    Each agent's observation is its seat's flat int32 vector of the schema, in its own frame;
    its mask is ``infos[agent]["action_mask"]`` (and :meth:`action_mask`); its action is one
    integer of the schema's action space, the wait for an agent left out of the actions. The
    episode ends for both seats together: terminated with +1, -1 or 0 when the match ends,
    truncated with 0 when it is cut short by the tick limit or by a fault. A fault is never a
    result: each agent's info names its kind under ``fault``, with ``fault_message`` and
    ``fault_signature``. Each agent's info is :meth:`VectorBattle.seat_info` of its seat: its own
    crowns and illegal counts, not the other's.

    ``install`` is the env module's installed distribution; ``tables`` the game tables folder of
    a data version (required for the ladder engine).
    """

    metadata = {"render_modes": [], "name": "crforge_ladder_v1"}  # noqa: RUF012

    def __init__(
        self,
        deck0: Sequence[str],
        deck1: Sequence[str],
        *,
        install: str | Path,
        tables: str | Path | None = None,
        engine: str = "ladder",
        config: LadderConfig | None = None,
        **vector_kwargs: Any,
    ):
        self.vector = VectorBattle(
            1, 1, install=install, engine=engine, tables=tables, config=config, **vector_kwargs
        )
        self.vector.set_autoreset(False)
        self.schema = self.vector.schema
        self._decks = (list(deck0), list(deck1))
        self.possible_agents = list(SEATS)
        self.agents: list[str] = []
        self._observation_space = observation_space(self.vector)
        self._action_space = spaces.Discrete(self.schema.actions)
        self._rng = np.random.default_rng()

    def observation_space(self, agent: str) -> spaces.Space:
        return self._observation_space

    def action_space(self, agent: str) -> spaces.Space:
        return self._action_space

    def action_mask(self, agent: str) -> np.ndarray:
        """The agent's mask now, bool of the action space's size."""
        return self.vector.masks[0, SEATS.index(agent)].astype(bool)

    def _observations(self) -> dict[str, np.ndarray]:
        return {a: self.vector.observations[0, i].copy() for i, a in enumerate(SEATS)}

    def _infos(self) -> dict[str, dict[str, Any]]:
        return {a: self.vector.seat_info(0, i) for i, a in enumerate(SEATS)}

    def reset(self, seed: int | None = None, options: dict | None = None):
        if seed is not None:
            self._rng = np.random.default_rng(seed)
        self.vector.reset([battle_seed(seed, self._rng)], *self._decks)
        if self.vector.dones[0]:
            raise BattleFaultError(self.vector.fault(0))
        self.agents = list(self.possible_agents)
        return self._observations(), self._infos()

    def step(self, actions: dict[str, int]):
        if not self.agents:
            raise RuntimeError("the episode is over: reset")
        for i, agent in enumerate(SEATS):
            self.vector.actions[0, i] = int(actions.get(agent, self.schema.wait))
        self.vector.step()
        infos = self._infos()
        done = bool(self.vector.dones[0])
        info = infos[SEATS[0]]
        truncated = done and (info["truncated"] or info["fault"] != "none")
        terminated = done and not truncated
        rewards = {
            a: 0.0 if truncated else float(self.vector.rewards[0, i]) for i, a in enumerate(SEATS)
        }
        observations = self._observations()
        if done:
            self.agents = []
        return (
            observations,
            rewards,
            {a: terminated for a in SEATS},
            {a: truncated for a in SEATS},
            infos,
        )

    def close(self) -> None:
        self.vector.close()
