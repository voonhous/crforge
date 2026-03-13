# crforge

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A headless Clash Royale battle simulator built in Java, designed for reinforcement learning and AI
research. Deterministic tick-based engine with 121 data-driven cards, a LibGDX debug visualizer,
and a Python Gymnasium integration via ZMQ bridge.

## Features

- **121 cards**: troops, spells, and buildings loaded from community-decoded game data
- **Deterministic simulation**: 30 FPS fixed timestep, reproducible for RL training
- **Component-Entity-System architecture**: entities hold data, systems hold logic
- **Full combat mechanics**: melee, ranged, AOE, chain lightning, scatter projectiles,
  charge, dash, hook, reflect, shields, death spawn, variable damage (inferno), burst attacks
- **Status effects**: stun, slow, rage, freeze with multiplier-based stacking
- **Level scaling**: rarity-based iterative growth matching the original game's formulas
- **Python bridge**: ZMQ + JSON Gymnasium environment for RL training
- **Debug visualizer**: real-time LibGDX rendering with pause, speed control, and card deployment

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

# Run gym bridge server (default port 9876)
./gradlew :gym-bridge:run
```

## Modules

| Module       | Description                                                       |
|--------------|-------------------------------------------------------------------|
| `core`       | Headless simulation engine -- entities, systems, match logic      |
| `data`       | Card/unit/projectile config loading from JSON into typed objects  |
| `desktop`    | LibGDX debug visualizer for watching and interacting with matches |
| `gym-bridge` | ZMQ server + Python Gymnasium environment for RL training         |

`core` has no GUI dependencies. `data` depends on `core`. `desktop` and `gym-bridge` depend on
both.

## Architecture

The simulation uses a Component-Entity-System (CES) design. Entities (Troop, Building, Tower,
Projectile, AreaEffect) are containers for components (Health, Position, Combat, Movement).
Logic lives in systems that operate on entities each tick.

**Tick loop** (30 FPS, each frame):

```
 1. processPending       Flush spawn/removal queues
 2. match.update         Elixir regen, overtime transitions
 3. DeploymentSystem     Process queued card deploys
 4. SpawnerSystem        Tick live-spawners (Witch, Tombstone, etc.)
 5. StatusEffectSystem   Decrement durations, recalculate multipliers
 6. Entity.update        Per-entity timers (deploy, cooldowns, lifetime)
 7. AreaEffectSystem     Tick damage/buff zones (Poison, Freeze, Rage)
 8. TargetingSystem      Assign/update combat targets
 9. AbilitySystem        Charge, dash, variable damage ramp
10. CombatSystem         Execute attacks, spawn projectiles
    ProjectileSystem     Move projectiles, hit detection, chain/pierce
11. PhysicsSystem        Movement, collision avoidance, knockback
12. processDeaths        Death spawn, death projectile handlers
13. checkTimeLimit       Overtime, win conditions, sudden death
```

See [docs/architecture.md](docs/architecture.md) for the full system dependency graph, entity
lifecycle, and data loading pipeline.

## Card Data

Card statistics are loaded from JSON files in `data/src/main/resources/cards/`:

| File               | Format          | Contents                            |
|--------------------|-----------------|-------------------------------------|
| `buffs.json`       | map (name->def) | Buff/debuff definitions             |
| `projectiles.json` | map (name->def) | Projectile definitions              |
| `units.json`       | map (name->def) | Unit stats (refs projectiles)       |
| `cards.json`       | array           | Card definitions (refs units/projs) |

Stats are community-decoded from publicly available game data. Loading order:
buffs -> projectiles -> units -> cards (each stage resolves string references from prior stages).

## Visualizer Controls

| Key           | Action                                                     |
|---------------|------------------------------------------------------------|
| `Space`       | Pause / Resume                                             |
| `R`           | Reset match                                                |
| `P`           | Toggle path visualization                                  |
| `O`           | Toggle attack range circles                                |
| `D`           | Toggle floating damage numbers                             |
| `A`           | Toggle AOE damage indicators                               |
| `+` / `-`     | Speed up / slow down (0.25x - 8x)                          |
| `1-4`         | Select blue player's card from hand                        |
| `5-8`         | Select red player's card from hand                         |
| `Left click`  | Select card from hand UI, or deploy selected card on arena |
| `Right click` | Deselect card                                              |

## Tools

| Tool                                                | Description                                                            |
|-----------------------------------------------------|------------------------------------------------------------------------|
| [Formation Visualizer](tools/formation_visualizer/) | Tkinter app for viewing and editing multi-unit spawn formation offsets |

## Docs

| Document                                           | Description                                                     |
|----------------------------------------------------|-----------------------------------------------------------------|
| [Level Scaling](docs/level_scaling.md)             | Rarity multiplier tables and tower stat scaling formulas        |
| [Reverse Engineering](docs/reverse_engineering.md) | Guide for measuring missing unit stats from in-game observation |

## Code Style

This project uses [Google Java Format](https://github.com/google/google-java-format) enforced
via [Spotless](https://github.com/diffplug/spotless). Formatting is checked on build:

```bash
./gradlew spotlessApply
```

## macOS Note

On macOS, the LibGDX visualizer requires the `-XstartOnFirstThread` JVM argument. The Gradle
`desktop:run` task handles this automatically. If running from an IDE, add `-XstartOnFirstThread` to
your VM options.

## Disclaimer

This is an independent fan project created for educational and research purposes. It is **not**
affiliated with, endorsed by, or associated with Supercell. "Clash Royale", "Supercell", and related
names and imagery are trademarks of Supercell Oy. See [NOTICE](NOTICE) for full attribution.

## License

Licensed under the [Apache License 2.0](LICENSE).
