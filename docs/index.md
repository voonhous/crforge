---
title: Clash Royale battle simulator and reinforcement learning environment
description: crforge is an open source, deterministic Clash Royale battle simulator in Java, held tick by tick to battles recorded in the game, with a reinforcement learning environment and Gymnasium and PettingZoo wrappers for Python.
---

# crforge

crforge is an open source, deterministic Clash Royale battle simulator in Java, with a reinforcement learning environment on top of it. Its engine, the battle core, plays a Ladder 1v1 battle on the standard arena in steps of 50 ms of game time, 20 steps a second, on the game's own tables of one data version. The same inputs on the same tables give the same battle, step for step. Evolutions, heroes and champions are in scope.

The source, the issues and the releases are on GitHub at [voonhous/crforge](https://github.com/voonhous/crforge), under the Apache License 2.0.

## What is measured

Accuracy is measured, not assumed: every recorded reference battle is either matched tick for tick, or its first divergence or refusal is recorded.

- **Held to recorded battles.** The `conformance` module plays each reference battle's scenario on the battle core and compares every tick with the game's own trace of that battle, with no tolerance. CI replays the reference battles on each change and fails if any outcome moves. The outcome of each battle is listed in [`conformance/reference-expectations/<version>.json`](https://github.com/voonhous/crforge/tree/main/conformance/reference-expectations): a match, the first observation and field that differ, or the input the battle core refused.
- **Scored on real battles.** A coverage set of tens of thousands of real battles is scored after each merge, and the coverage badge in the [README](https://github.com/voonhous/crforge#readme) shows the share that matches to the end ([how a battle is checked and scored](https://github.com/voonhous/crforge/blob/main/conformance/README.md)).
- **Refused, never guessed.** Units, projectiles, buffs, area effects, actions and expressions are read from the game's tables, in their own units and under their own column names. A column or class the battle core does not model refuses the battle with the reason, rather than playing it approximately.
- **Integer time.** Every duration is whole milliseconds, stepped by exactly 50 per tick.
- **Fast.** About 127,000 steps a second on one thread on real Ladder battles, about 6,300 times the game's own 20 steps a second, headless ([Performance](performance.md)).

The game versions the battle rules hold for, and the data versions crforge runs, are in [Compatibility](compatibility.md).

## The reinforcement learning environment

The `env` module steps a vector of Ladder battles in the JVM behind flat buffers, which a Python trainer reads as arrays of the same memory, without a copy. One step takes both seats' actions in every battle, gives each play through the same path as a player's play, and leaves every observation, mask of legal actions, reward and info record in place. A seat sees what its player sees.

The `crforge-env` Python package under [`python/`](https://github.com/voonhous/crforge/blob/main/python/README.md) runs it from Python:

- `VectorBattle`: a vector of battles stepped in lockstep with a policy, its observations, masks, actions, rewards and dones as NumPy views of the buffers;
- `ClashGymEnv`: a single-agent Gymnasium environment for one seat against a given opponent (random, waiting, or the scripted `ScriptedBot`);
- `ClashParallelEnv`: a PettingZoo parallel environment for both seats of a battle, for self-play;
- a battle can be forked to look ahead before acting, a battle can start mid-match from a replay, and recorded replays can be labelled for imitation learning.

The observation schema, the action space and mask, the faults, the benchmark and the Python package are described in [The Environment](environment.md).

## The game data

crforge does not ship the game's data. The build makes the game tables on your machine from the game's own files, once you say where those come from with the Gradle property `crforge.assetSource` ([Building the game tables](game-tables.md#building-the-game-tables)). A debug visualizer plays battles by hand and replays recorded ones ([Debug Visualizer](architecture.md#debug-visualizer)).

## The documentation

| Page | What it covers |
|------|----------------|
| [Architecture](architecture.md) | Modules, packages, the 50 ms step and the debug visualizer |
| [The Battle Core](battle-core.md) | The engine: its package layout, one step and the entity tick, when a player's command runs, the tests and the game data, what is covered and the roadmap |
| [Battle Core Tests](battle-core-tests.md) | The tests and reference battles that hold the battle core, test class by test class |
| [Battle Core Assumptions](battle-core-assumptions.md) | The supplied answers the battle core carries, so they are not mistaken for settled behaviour |
| [Troop Pathfinding](pathfinding.md) | The routing grid, cell costs, the route search, endpoints and lanes, and the movement, targeting and state rules |
| [Game Tables and Reference Battles](game-tables.md) | The game tables the battle core reads, how they are built, and the recorded battles it is held to |
| [The Environment](environment.md) | The reinforcement learning environment: the observation schema, the action space and mask, the faults, the vector environment and its benchmark, and the Python package |
| [Compatibility](compatibility.md) | The client the simulator follows, the client versions that share its battle rules, and the data it runs |
| [Game Versions](game-versions.md) | Which data versions each game client version has run |
| [Performance](performance.md) | The headless tick benchmark, how to profile it and the current baseline |
| [Performance History](performance-history.md) | The earlier baselines and each round of optimisations |
| [Fork Performance](performance/fork.md) | What a fork of a battle costs, what it copies, and each round of work on it |
| [Conformance](https://github.com/voonhous/crforge/blob/main/conformance/README.md) | How a reference battle is checked, the four outcomes and the expectations file, and the coverage score |

## Disclaimer

crforge is an independent fan project created for educational and research purposes. It is not affiliated with, endorsed by, or associated with Supercell. "Clash Royale", "Supercell", and related names and imagery are trademarks of Supercell Oy.
