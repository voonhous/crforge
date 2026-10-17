"""Reinforcement learning environments on the crforge battle core.

- :class:`VectorBattle`: many battles stepped in one JVM call, read as numpy views
- :class:`ClashParallelEnv`: a PettingZoo parallel environment over one battle, both seats
- :class:`ClashGymEnv`: a Gymnasium environment for one seat against a given opponent
- :class:`Schema`: the observation, mask, action and info layout, read from the env module
- :class:`ScriptedBot`: a scripted opponent of a few readable rules on what its player sees
- :func:`evaluate`: a policy against an opponent over many episodes, as wins, losses and draws
- :func:`label_replays`: replays labelled for behavioural training, as (observation, mask, action)
  rows of each seat

Every entry point takes the env module's installed distribution (``install``, made by
``./gradlew :env:installDist``) and, for Ladder battles, the game tables folder of a data version
(``tables``); neither has a default.
"""

from .evaluation import EvaluationResult, evaluate
from .gym_env import BattleFaultError, ClashGymEnv, random_opponent, wait_opponent
from .labels import FLAGS, LabelRun, ReplayLabels, label_replays
from .parallel_env import ClashParallelEnv
from .schema import SUPPORTED_VERSION, Schema
from .scripted import ScriptedBot
from .vector import Fault, LadderConfig, SpellFacts, VectorBattle

__all__ = [
    "FLAGS",
    "SUPPORTED_VERSION",
    "BattleFaultError",
    "ClashGymEnv",
    "ClashParallelEnv",
    "EvaluationResult",
    "Fault",
    "LabelRun",
    "LadderConfig",
    "ReplayLabels",
    "Schema",
    "ScriptedBot",
    "SpellFacts",
    "VectorBattle",
    "evaluate",
    "label_replays",
    "random_opponent",
    "wait_opponent",
]
