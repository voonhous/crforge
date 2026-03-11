# crforge

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A headless Clash Royale battle simulator built in Java, designed for reinforcement learning and AI research. Includes a LibGDX debug visualizer and a planned Python Gymnasium integration via TCP bridge.

## Features

- **121 cards** -- troops, spells, and buildings with data-driven stats
- **Tick-based deterministic simulation** -- 30 FPS fixed timestep, reproducible for RL training
- **Component-Entity-System architecture** -- clean separation of data and logic
- **Debug visualizer** -- real-time LibGDX rendering with pause, speed control, and card deployment
- **Modular design** -- headless core with no GUI dependencies, visualization and bridge as separate modules
- **Level scaling** -- rarity-based stat growth matching the original game's formulas

## Quick Start

**Requirements:** Java 17

```bash
# Build
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew build

# Run tests
./gradlew :core:test :data:test

# Run debug visualizer
./gradlew :desktop:run
```

## Modules

| Module | Description |
|--------|-------------|
| `core` | Headless simulation engine -- entities, systems, match logic (no GUI dependencies) |
| `data` | Card/unit/projectile config loading from JSON into typed Java objects |
| `desktop` | LibGDX debug visualizer for watching and interacting with matches |
| `gym-bridge` | TCP server for Python Gymnasium integration (planned) |

## Visualizer Controls

| Key | Action |
|-----|--------|
| `Space` | Pause / Resume |
| `P` | Show pathing |
| `O` | Toggle attack range circles |
| `R` | Reset match |
| `+` / `-` | Speed up / slow down (0.25x - 8x) |
| `1-4` | Select blue player's card from hand |
| `5-8` | Select red player's card from hand |
| `Left click` | Deploy selected card at position |
| `Right click` | Deselect card |

## Card Data

Card statistics are loaded from JSON files in `data/src/main/resources/cards/`. The stats are community-decoded from publicly available game data and stored across four files: `cards.json`, `units.json`, `projectiles.json`, and `buffs.json`.

## macOS Note

On macOS, the LibGDX visualizer requires the `-XstartOnFirstThread` JVM argument. The Gradle `desktop:run` task handles this automatically. If running from an IDE, add `-XstartOnFirstThread` to your VM options.

## Disclaimer

This is an independent fan project created for educational and research purposes. It is **not** affiliated with, endorsed by, or associated with Supercell. "Clash Royale", "Supercell", and related names and imagery are trademarks of Supercell Oy. See [NOTICE](NOTICE) for full attribution.

## License

Licensed under the [Apache License 2.0](LICENSE).
