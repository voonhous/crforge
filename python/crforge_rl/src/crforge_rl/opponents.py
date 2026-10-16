"""Players that act for a batch of seats: the opponents of training and evaluation.

Every player here has one method, ``act(observations, masks, rng)``: given a batch of seat
observations ``(n, seat_ints)`` and masks ``(n, actions)``, it returns one action a seat
``(n,)``. The trainer's policy is wrapped the same way (``crforge_rl.policy.PolicyPlayer``), so the
evaluation plays any player against any other.

An opponent is named on the command line by a spec (:func:`load_opponent`):

- ``random``: :class:`RandomOpponent`, a random open action three decisions in ten, else the wait
  (``crforge_env.random_opponent`` on a batch)
- ``wait``: :class:`WaitOpponent`, never plays
- ``scripted``: ``crforge_env.ScriptedBot`` with its default rules, bound to the vector
- ``<module>:<name>``: a function of one seat with ``crforge_env``'s opponent signature
  ``(observation, mask, rng) -> int`` (``crforge_env.random_opponent`` is one), called per seat
  (:class:`SeatOpponent`); or an object or class that already has ``act`` on a batch. A class is
  made with no arguments; an object with a ``bind(vector)`` method (as ``ScriptedBot`` has) is
  bound to the vector it plays in. Another bot plugs in this way, with no change to the trainer
- ``checkpoint:<path>``: a frozen policy from a checkpoint (needs torch, see
  :func:`crforge_rl.policy.load_player`)
"""

from __future__ import annotations

import importlib
import inspect
from collections.abc import Callable
from typing import Any, Protocol

import numpy as np
from crforge_env import ScriptedBot, VectorBattle

SeatPolicy = Callable[[np.ndarray, np.ndarray, np.random.Generator], int]
"""``crforge_env``'s opponent: one seat's observation, its mask and a generator, an action."""


class Player(Protocol):
    """Acts for a batch of seats."""

    name: str

    def act(
        self, observations: np.ndarray, masks: np.ndarray, rng: np.random.Generator
    ) -> np.ndarray:
        """One action a seat, int32 ``(n,)``, each open in its seat's mask."""
        ...


class WaitOpponent:
    """Never plays."""

    name = "wait"

    def act(
        self, observations: np.ndarray, masks: np.ndarray, rng: np.random.Generator
    ) -> np.ndarray:
        return np.zeros(len(masks), dtype=np.int32)


class RandomOpponent:
    """A random open action ``play_rate`` of the decisions, else the wait.

    The batch form of ``crforge_env.random_opponent``: a seat that plays draws uniformly among its
    open actions other than the wait, as that function does.
    """

    name = "random"

    def __init__(self, play_rate: float = 0.3):
        if not 0.0 <= play_rate <= 1.0:
            raise ValueError(f"a play rate is between 0 and 1, got {play_rate}")
        self.play_rate = play_rate

    def act(
        self, observations: np.ndarray, masks: np.ndarray, rng: np.random.Generator
    ) -> np.ndarray:
        actions = np.zeros(len(masks), dtype=np.int32)
        plays = np.flatnonzero(rng.random(len(masks)) < self.play_rate)
        if plays.size:
            open_plays = masks[plays, 1:] != 0
            # A uniform draw among the open plays: the largest of uniform keys over them.
            keys = rng.random(open_plays.shape) * open_plays
            chosen = np.argmax(keys, axis=1) + 1
            actions[plays] = np.where(open_plays.any(axis=1), chosen, 0)
        return actions


class SeatOpponent:
    """A function of one seat (``crforge_env``'s opponent signature), called seat by seat."""

    def __init__(self, function: SeatPolicy, name: str | None = None):
        self.function = function
        self.name = name or getattr(function, "__name__", "seat_policy")

    def act(
        self, observations: np.ndarray, masks: np.ndarray, rng: np.random.Generator
    ) -> np.ndarray:
        return np.array(
            [int(self.function(o, m, rng)) for o, m in zip(observations, masks, strict=True)],
            dtype=np.int32,
        )


def _from_object(target: Any, name: str, vector: VectorBattle | None = None) -> Player:
    """A player from an imported object: a batch player, a class of one, or a seat function;
    bound to ``vector`` when it has ``bind`` (a bot that reads the layout or the tables)."""
    if inspect.isclass(target):
        target = target()
    bind = getattr(target, "bind", None)
    if callable(bind):
        if vector is None:
            raise ValueError(f"{name} must be bound to the vector it plays in: give the vector")
        bind(vector)
    if hasattr(target, "act"):
        if not hasattr(target, "name"):
            target.name = name
        return target
    if callable(target):
        return SeatOpponent(target, name)
    raise ValueError(f"{name} is neither a player (with act) nor a function of one seat")


def load_opponent(spec: str, vector: VectorBattle | None = None, **kwargs: Any) -> Player:
    """The player a spec names (the module's docstring lists them), to play in ``vector``.

    ``vector`` binds a bot that reads the layout or the tables, and checks a checkpoint's
    metadata; ``kwargs`` go to the checkpoint loader (``device``).
    """
    if spec == "random":
        return RandomOpponent()
    if spec == "wait":
        return WaitOpponent()
    if spec == "scripted":
        return _from_object(ScriptedBot(), "scripted", vector)
    if spec.startswith("checkpoint:"):
        from .policy import load_player  # needs torch, imported only for a checkpoint

        return load_player(spec.removeprefix("checkpoint:"), vector=vector, **kwargs)
    module_name, sep, attribute = spec.partition(":")
    if not sep or not module_name or not attribute:
        raise ValueError(
            f"an opponent is random, wait, checkpoint:<path> or <module>:<name>, not {spec!r}"
        )
    module = importlib.import_module(module_name)
    if not hasattr(module, attribute):
        raise ValueError(f"module {module_name} has no {attribute}")
    return _from_object(getattr(module, attribute), spec, vector)
