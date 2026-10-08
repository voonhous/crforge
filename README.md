# crforge

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Build](https://github.com/voonhous/crforge/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/voonhous/crforge/actions/workflows/build-and-test.yml)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

A headless Clash Royale battle simulator built in Java, designed for reinforcement learning and AI research. Deterministic tick-based engine with data-driven cards and a LibGDX debug visualizer. The Python Gymnasium environment was removed; it will be rebuilt on the battle core.

<p align="center">
  <img src="docs/assets/debug-visualizer.gif" alt="Debug visualizer showing a simulated battle" width="320">
</p>

## Features

- **Full combat**: melee, ranged, AOE, chain lightning, scatter projectiles, charge, dash, hook, reflect, shields, death spawn, burst attacks
- **Status effects**: stun, slow, rage, freeze with multiplier-based stacking
- **Level scaling**: rarity-based iterative growth matching the original game's formulas
- **Component-Entity-System**: entities hold data, systems hold logic
- **Community card data**: >100 troops, spells, and buildings loaded from JSON with automatic reference resolution

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

The debug visualizer runs the battle core, which needs the game tables: set `crforge.gameTables=<folder>` in `~/.gradle/gradle.properties` (or pass `-Pcrforge.gameTables=<folder>`, or set `CRFORGE_GAME_TABLES`). It prints the tables folder, data version and content sha at startup, and its key map; see
[Debug Visualizer](docs/architecture.md#debug-visualizer) for the full list, including the routing
overlays (`G`, `N`).

## Modules

| Module        | Description                                                                                 |
|---------------|---------------------------------------------------------------------------------------------|
| `core`        | Headless simulation engine -- entities, systems, match logic                                |
| `data`        | Card/unit/projectile config loading from JSON into typed objects                            |
| `desktop`     | LibGDX debug visualizer for watching and interacting with matches                           |
| `conformance` | Checks the battle core against recorded reference battles ([README](conformance/README.md)) |

`core` has no GUI dependencies. `data` depends on `core`. `desktop` and `conformance` depend on `core` only.

## Docs

| Document                                                   | Description                                              |
|------------------------------------------------------------|----------------------------------------------------------|
| [Simulation & Entities](docs/simulation.md)                | Tick loop, entity lifecycle, entity types                |
| [Arena, Match & Economy](docs/arena-and-match.md)          | Arena layout, placement, win conditions, elixir, hand    |
| [Targeting, Combat & Abilities](docs/combat.md)            | Target locking, attack pipeline, 10 ability types        |
| [Card Data Schema](docs/schema.md)                         | JSON schema, loading pipeline, reference resolution      |

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
