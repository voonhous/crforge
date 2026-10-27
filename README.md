# crforge

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Build](https://github.com/voonhous/crforge/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/voonhous/crforge/actions/workflows/build-and-test.yml)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![16.402.19 coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvoonhous%2Fcrforge%2Fbadges%2Fbadge-16.402.19.json)](conformance/README.md#coverage)
[![16.402.21 coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvoonhous%2Fcrforge%2Fbadges%2Fbadge-16.402.21.json)](conformance/README.md#coverage)
[![16.402.22 coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fvoonhous%2Fcrforge%2Fbadges%2Fbadge-16.402.22.json)](conformance/README.md#coverage)

A deterministic Clash Royale battle simulator in Java. Its battle core plays a battle in 50 ms steps on the game's own tables of one data version, and is held tick by tick to battles recorded in the game. A LibGDX debug visualizer plays battles by hand and replays recorded ones. A reinforcement learning environment steps a vector of battles behind flat buffers a Python trainer reads without a copy ([docs/environment.md](docs/environment.md)).

- **Tick-exact**: held tick for tick, with no tolerance, to battles recorded in the game on every data version it supports. CI replays all of them on each change and fails if any outcome moves. The outcome of each is listed in [conformance/reference-expectations](conformance/reference-expectations/) ([conformance](conformance/README.md))
- **Fast**: about 104,000 steps a second on one thread on real Ladder replays, about 5,000 times the game's own 20 steps a second, headless ([docs/performance.md](docs/performance.md))

<p align="center">
  <img src="docs/assets/debug-visualizer.gif" alt="The replay viewer playing a Ladder replay beside the game's own replay of the same battle" width="800">
</p>

## Porting or reimplementing crforge

Porting crforge's logic to another language, by hand or by asking an AI assistant to rewrite it? Please put a comment at each ported function or module that names the crforge file and the commit you read, for example:

```rust
// Ported from crforge core/src/main/java/org/crforge/core/pathfinding/target/TargetingVisit.java @ <commit> (https://github.com/voonhous/crforge)
```

It is a request, not a license condition (crforge is Apache 2.0), and it is mostly for your benefit. crforge is held tick by tick to battles recorded in the game, and its rules get corrected whenever a recording shows them wrong.

With the file and commit in your code, `git log <commit>..origin/main -- <file>` lists every change to that logic since you read it (`main` is the development branch). The repository URL in the comment lets us find your port and tell you when we fix something it carries.

To hear about fixes, watch the repository's releases (the release notes list behaviour corrections per mechanic), or open an issue to ask about one.

## Why a tick-accurate simulator

Clash Royale battles turn on small margins: a troop that retargets one step later, a projectile that lands one tick after a tower dies, a spell that catches a unit at the edge of its radius. A simulator that only approximates the game (hand-tuned stats, floating point time, mechanics guessed from videos) drifts from the real battle within seconds, and the drift compounds. An agent trained on such a simulator learns the simulator's quirks rather than the game, and results from different simulators cannot be compared.

The game itself is no substitute: it cannot be run headless, stepped, forked or replayed at the scale machine learning needs. A simulator that gives the same battle as the game, step for step, from the same inputs, can stand in for it: train and evaluate agents offline, label recorded replays, and run experiments anyone can reproduce.

The full match of the animation above: the replay viewer plays the simulated battle (left) beside the game's own replay of the same battle (right).

https://github.com/user-attachments/assets/83b3e12e-3aa6-45c2-ab4b-3997bd8bfcd6

## Goal

crforge aims to be that simulator for AI and ML research on Clash Royale: an open, deterministic and accurate 1v1 battle engine, with evolutions, heroes and champions in scope. Accuracy is measured, not assumed: every recorded reference battle is either matched tick for tick, or its first divergence or refusal is recorded. On top of the battle core, the plan is a reinforcement learning environment and tooling to learn from recorded replays.

## Features

- **The game's own data**: units, projectiles, buffs, area effects, actions and expressions are read from the game's tables, in their own units and under their own column names; a column or class the battle core does not model is refused, never guessed
- **Integer time**: every duration is whole milliseconds, stepped by exactly 50 per tick
- **Held to recorded battles**: the `conformance` module plays each recorded battle's scenario and compares every tick with the game's own trace, with no tolerance
- **Fidelity ledger**: every battle core class says which of its parts are settled and which are not (`./gradlew :core:fidelityReport`)
- **Replays**: the visualizer plays a replay file, or a crawl of them, on the data version it was recorded on

## Quick Start

**Requirements:** Java 17

```bash
# Build
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew build

# Run tests
./gradlew test

# Run debug visualizer
./gradlew :desktop:run
```

> **macOS:** The visualizer needs `-XstartOnFirstThread`. The Gradle task handles this; add it to VM options if running from an IDE.

The debug visualizer and the tests run the battle core, which needs the game tables. This repository does not ship them. The build makes them on your machine from the game's own files once you say where those come from, with the Gradle property `crforge.assetSource` (in `~/.gradle/gradle.properties`, or `-Pcrforge.assetSource=...`):

- `cdn` fetches them from the game's asset CDN (an opt-in, never a default)
- a `file:` URI names a local copy

That is the only way the tables are found ([Building the game tables](docs/game-tables.md#building-the-game-tables)).

The debug visualizer prints the tables folder, data version and content sha at startup, and its key map. See [Debug Visualizer](docs/architecture.md#debug-visualizer) for the full list, including the routing overlays (`G`, `N`).

## Playing a battle

```bash
./gradlew :desktop:run -Pcrforge.assetSource=<source>
```

This opens the debug screen on a Ladder 1v1 battle on the standard arena, with towers and cards at level 11. You play both sides from the hand panels beside the arena: blue (side 0) at the bottom, red (side 1) at the top. The two decks are fixed, in `org.crforge.desktop.battle.BattleDecks`.

To play a card:

1. Select it: click it in its hand, or press `1`-`4` for blue's hand and `5`-`8` for red's. A number key only selects the card.
2. Move the mouse over the arena. The hovered tile shows where the card would be placed: each unit's outline and attack range, a spell's circle, or a red tile where the card cannot be placed.
3. Left click to play it. Right click deselects the card.

A play runs 20 ticks (one second) after the click, as a player's play does in the game. A play the game would not accept (not enough elixir, a card already waiting to be played, no tile to place it on) is refused on the spot with a message.

| Key | Action |
| --- | --- |
| `SPACE` | Pause / resume |
| `.` | Pause and advance exactly one tick |
| `+` / `-` | Speed up / slow down (0.25x to 8x) |
| `R` | Start a new battle |
| `I` | Switch between Deploy and Inspect; in Inspect, click a unit to pin its details |
| `O`, `D`, `A`, `H` | Range circles, damage numbers, area damage, HP numbers |
| `V` | Switch to the next data version built (a new battle on it) |
| `T` | Hide / show the diagnostics sidebar |

The full key list, including the routing overlays, is in [Controls](docs/architecture.md#controls).

To play against a trained model instead of playing both sides, pass it as an ONNX file with `--args="--model <file.onnx>"` ([Playing against a model](docs/architecture.md#playing-against-a-model)).

## Watching a replay

```bash
./gradlew :desktop:run -Pcrforge.assetSource=<source> --args="--replay <file>"
```

This opens the replay viewer on a replay file instead of a Ladder battle. You can also drop a replay file on the window of the debug screen or of the viewer. The viewer reads:

- a replay file (`.json`): one battle and the plays of both sides
- a file of many replays, one a line (`.jsonl`, or compressed as `.jsonl.zst` or `.jsonl.gz`): the viewer lists them above the arena, and `[` and `]` open the previous and next

This repository ships no replays. The battle core can write any battle it plays as a replay file (`org.crforge.core.battle.replay.ScenarioWriter`), and the environment writes one for a battle that faults ([fault bundles](docs/environment.md#fault-bundles)).

A replay plays only on the tables of the data version it was recorded on. When the replay names its data version, the viewer switches to that version if you have built its tables, and refuses the replay with the reason if you have not. Build a version's tables with `-Pcrforge.dataVersion=<version>`. A replay the battle core cannot play in full is refused with every reason, never played in part.

| Key | Action |
| --- | --- |
| `SPACE` | Pause / resume |
| `.` / `,` | Pause and step one tick forward / back |
| `+` / `-` | Speed up / slow down |
| `R` | Restart the replay from tick 0 |
| `F` | Flip the view: the game's view of side 1 (the default, as the game shows a replay) or of side 0 |
| `[` / `]`, `L` | Previous / next replay of a file of many, hide or show the list |
| Left click | Inspect a unit |

The plays are the replay's own: cards cannot be selected or played in the viewer. More in [Replays](docs/architecture.md#replays).

## Modules

| Module        | Description                                                                                 |
|---------------|---------------------------------------------------------------------------------------------|
| `core`        | The battle core: a headless battle simulator on the game's tables                           |
| `desktop`     | LibGDX debug visualizer for watching and interacting with matches                           |
| `conformance` | Checks the battle core against recorded reference battles ([README](conformance/README.md)) |
| `env`         | The reinforcement learning environment: a vector of battles behind flat buffers ([docs](docs/environment.md)) |
| `tables`      | The table decoder: builds the game tables of a data version from the game's files           |
| `python/`     | The `crforge-env` Python package: the environment's vector and wrappers for Python trainers ([README](python/README.md)) |

`core` has no GUI dependencies. `conformance` and `env` depend on `core` only, `desktop` on `core` and `env` (and ONNX Runtime, for a model opponent), and `tables` on nothing in the project. `python/` is not a Gradle module: it runs the `env` module's installed jars in the JVM. The recorded battles live in a separate game data repository, at the commit `crforge-data.lock` names.

## Docs

| Document                                                     | Description                                                          |
|--------------------------------------------------------------|----------------------------------------------------------------------|
| [Architecture](docs/architecture.md)                         | Modules, packages, the 50 ms step and the debug visualizer           |
| [The Battle Core](docs/battle-core.md)                       | The step, the entity tick, what is covered and what is assumed       |
| [Troop Pathfinding](docs/pathfinding.md)                     | Routing grid, cell costs, routes and movement rules                  |
| [Game Tables and Reference Battles](docs/game-tables.md)     | The tables the battle core reads and the battles it is held to       |
| [The Environment](docs/environment.md)                       | The RL environment: its schema, mask, faults, benchmark and Python package |

See [docs/architecture.md](docs/architecture.md) for the full documentation index.

## Code Style

This project uses [Google Java Format](https://github.com/google/google-java-format) enforced
via [Spotless](https://github.com/diffplug/spotless). Formatting is checked on build:

```bash
./gradlew spotlessApply
```

## Acknowledgements

This project was inspired by
[scholarlygaming's Clash Royale engine](https://www.reddit.com/r/ClashRoyale/comments/f21isa/effort_post_clash_royale_engine_development/),
the first somewhat complete open-source Clash Royale simulation engine.

## Disclaimer

This is an independent fan project created for educational and research purposes. It is **not**
affiliated with, endorsed by, or associated with Supercell. "Clash Royale", "Supercell", and related
names and imagery are trademarks of Supercell Oy. See [NOTICE](NOTICE) for full attribution.

## License

Licensed under the [Apache License 2.0](LICENSE).
