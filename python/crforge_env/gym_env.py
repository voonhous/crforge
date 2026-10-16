"""A Gymnasium environment: one seat of a battle, the other seat played by a given policy."""

from __future__ import annotations

from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any

import numpy as np
from gymnasium import Env, spaces

from .vector import Fault, LadderConfig, VectorBattle

OpponentPolicy = Callable[[np.ndarray, np.ndarray, np.random.Generator], int]
"""An opponent: given its observation, its mask and a random generator, an action."""

SEED_RANGE = (-(2**31), 2**31)
"""The seeds a battle takes (a Java int); ``reset(seed=...)`` outside it is refused."""


class BattleFaultError(RuntimeError):
    """A reset whose new episode faulted before its first decision."""

    def __init__(self, fault: Fault | None):
        super().__init__(
            "the episode faulted as it started: "
            + (f"{fault.kind}: {fault.message} ({fault.signature})" if fault else "unknown")
        )
        self.fault = fault


def wait_opponent(observation: np.ndarray, mask: np.ndarray, rng: np.random.Generator) -> int:
    """An opponent that never plays."""
    return 0


def random_opponent(observation: np.ndarray, mask: np.ndarray, rng: np.random.Generator) -> int:
    """An opponent that plays a random open action three decisions in ten, else waits."""
    if rng.random() >= 0.3:
        return 0
    open_actions = np.flatnonzero(mask[1:]) + 1
    return int(rng.choice(open_actions)) if open_actions.size else 0


def observation_space(vector: VectorBattle) -> spaces.Box:
    """A seat's observation as Gymnasium sees it: the flat int32 vector of the schema, with the
    bounds of :meth:`Schema.observation_bounds`. Its values are categories (vocabulary ids, codes,
    flags) and integer amounts, not continuous features: an encoder embeds the ids and scales the
    amounts itself."""
    low, high = vector.schema.observation_bounds(vector.vocabulary_size)
    return spaces.Box(low=low, high=high, shape=low.shape, dtype=np.int32)


def battle_seed(seed: int | None, rng: np.random.Generator) -> int:
    """The battle seed of a reset: the seed given, else one drawn from the environment's own
    generator (seeded by the last seeded reset)."""
    if seed is None:
        return int(rng.integers(0, SEED_RANGE[1]))
    if not SEED_RANGE[0] <= int(seed) < SEED_RANGE[1]:
        raise ValueError(f"a battle seed is a 32-bit integer, got {seed}")
    return int(seed)


class ClashGymEnv(Env):
    """The learner on ``seat`` of a battle against ``opponent``, one decision a step.

    - observation: the seat's flat int32 vector of the schema, in the seat's own frame
      (:func:`observation_space`); the layout is in ``self.schema``
    - action: one integer, ``0`` the wait, a hand slot played at a tile
      (``self.schema.play(slot, col, row)``), or a champion slot's ability
      (``self.schema.ability(slot)``)
    - mask: ``info["action_mask"]`` (int8) and :meth:`action_masks` (bool); an action the mask
      closed is taken as a wait and counted in ``info["illegal"]``
    - reward: +1, -1 or 0 when the match ends, 0 before
    - terminated: the match ended; truncated: the episode was cut short with no result, by the
      tick limit or by a fault. A fault (the battle core refused a behaviour, a bug of the core or
      of the environment) is never a result: the step is truncated with reward 0 and
      ``info["fault"]`` names the kind, with ``info["fault_message"]``,
      ``info["fault_signature"]`` and ``info["fault_bundle"]``
    - info: :meth:`VectorBattle.seat_info` of the learner's seat

    ``opponent`` is a policy ``opponent(observation, mask, rng) -> action`` of the other seat
    (:func:`random_opponent`, :func:`wait_opponent`, a :class:`ScriptedBot`); one with a
    ``bind(vector)`` method is bound to this environment's vector as it is made.

    ``deck`` is the learner's, ``opponent_deck`` the opponent's, eight card row names each;
    ``slots`` and ``opponent_slots`` their slot flags, one per card (None for none, every card
    plain; see :meth:`VectorBattle.reset`).
    ``install`` is the env module's installed distribution; ``tables`` the game tables folder of
    a data version (required for the ladder engine). The remaining keywords go to
    :class:`VectorBattle` (``synthetic_*``, ``jvm_args``).
    """

    metadata = {"render_modes": []}  # noqa: RUF012 (Gymnasium's class attribute)

    def __init__(
        self,
        deck: Sequence[str],
        opponent_deck: Sequence[str],
        *,
        install: str | Path,
        tables: str | Path | None = None,
        engine: str = "ladder",
        opponent: OpponentPolicy = random_opponent,
        seat: int = 0,
        config: LadderConfig | None = None,
        slots: Sequence[int] | None = None,
        opponent_slots: Sequence[int] | None = None,
        **vector_kwargs: Any,
    ):
        if seat not in (0, 1):
            raise ValueError("seat is 0 or 1")
        self.vector = VectorBattle(
            1, 1, install=install, engine=engine, tables=tables, config=config, **vector_kwargs
        )
        self.vector.set_autoreset(False)
        self.schema = self.vector.schema
        self.seat = seat
        self.opponent = opponent
        # An opponent that reads the layout or the tables (a ScriptedBot) is bound to the vector.
        bind = getattr(opponent, "bind", None)
        if callable(bind):
            bind(self.vector)
        decks = [list(deck), list(opponent_deck)]
        flags = [slots, opponent_slots]
        self._decks = (decks[seat], decks[1 - seat])
        self._slots = (flags[seat], flags[1 - seat])
        self.observation_space = observation_space(self.vector)
        self.action_space = spaces.Discrete(self.schema.actions)
        self._done = True

    def _observation(self) -> np.ndarray:
        return self.vector.observations[0, self.seat].copy()

    def action_masks(self) -> np.ndarray:
        """The learner's mask now, bool of the action space's size."""
        return self.vector.masks[0, self.seat].astype(bool)

    def reset(self, *, seed: int | None = None, options: dict | None = None):
        super().reset(seed=seed)
        self.vector.reset([battle_seed(seed, self.np_random)], *self._decks, *self._slots)
        if self.vector.dones[0]:
            raise BattleFaultError(self.vector.fault(0))
        self._done = False
        return self._observation(), self.vector.seat_info(0, self.seat)

    def step(self, action: int):
        if self._done:
            raise RuntimeError("the episode is over: reset")
        other = 1 - self.seat
        self.vector.actions[0, self.seat] = int(action)
        self.vector.actions[0, other] = int(
            self.opponent(
                self.vector.observations[0, other], self.vector.masks[0, other], self.np_random
            )
        )
        self.vector.step()
        info = self.vector.seat_info(0, self.seat)
        self._done = bool(self.vector.dones[0])
        truncated = self._done and (info["truncated"] or info["fault"] != "none")
        terminated = self._done and not truncated
        reward = 0.0 if truncated else float(self.vector.rewards[0, self.seat])
        return self._observation(), reward, terminated, truncated, info

    def close(self) -> None:
        self.vector.close()
