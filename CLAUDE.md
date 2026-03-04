# crforge — Claude Instructions

## Project Overview
crforge is a Clash Royale simulator in Java, ported from a JS reference implementation.
Goal: headless simulation for RL/AI training, LibGDX visualization, Python Gymnasium integration via TCP bridge.

**Tech Stack:** Java 17, Gradle (Kotlin DSL), LibGDX 1.12.1, Lombok, JUnit 5 + AssertJ

**Build:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew build`
**Test:** `./gradlew :core:test :data:test`
**Run visualizer:** `./gradlew :desktop:run`

---

## Module Structure

```
crforge/
├── core/           # Headless simulation (NO GUI dependencies)
├── data/           # Card/unit config loading (JSON → Card objects)
├── desktop/        # LibGDX visualization
├── gym-bridge/     # TCP server for Python
└── reference/      # Original & refactored JS source
    ├── src_og.js               # Monolithic legacy (10k lines)
    └── modular/                # Refactored ES6 blueprint
        ├── systems/            # Ability, Combat, Physics, Projectile, Targeting
        ├── models/             # Troop data schema
        └── utils/              # CardLoader, Geometry, PseudoRandom
```

### Core Package Structure

```
org.crforge.core/
├── arena/      → Arena, Tile, TileType
├── card/       → Card, CardRegistry, CardType, TroopStats, EffectStats
├── combat/     → TargetingSystem, CombatSystem
├── component/  → Health, Position, Combat, Movement, SpawnerComponent
├── engine/     → GameEngine, GameState, DeploymentSystem, SpawnerSystem
├── entity/
│   ├── base/       → Entity, AbstractEntity, EntityType, MovementType, TargetType
│   ├── unit/       → Troop
│   ├── structure/  → Building, Tower
│   └── projectile/ → Projectile
├── effect/     → StatusEffectType, AppliedEffect, StatusEffectSystem
├── match/      → Match, Standard1v1Match, GameMode
├── physics/    → PhysicsSystem
├── player/     → Player, Team, Deck, Hand, Elixir, dto/PlayerActionDTO
└── util/       → Vector2
```

### Data Package Structure

```
org.crfoge.data/
├── card/
│   └── CardRegistry.java
└── loader/
    ├── CardLoader.java
    └── dto/  → CardConfigDTO, UnitConfigDTO, EffectConfigDTO
```

### Desktop Package Structure

```
org.crforge.desktop/
├── CRForgeGame.java          → LibGDX Game application
├── DesktopLauncher.java      → Entry point (includes macOS LWJGL fix)
├── render/
│   └── DebugRenderer.java    → ShapeRenderer-based debug visualization
└── screen/
    └── DebugGameScreen.java  → Screen with game loop and input handling
```

---

## Key Design Decisions

1. **CES Architecture** — Entities are containers for components. Logic lives in Systems.
2. **Tick-based simulation** — 30 FPS (`GameEngine.TICKS_PER_SECOND = 30`, `DELTA_TIME = 1/30f`). Deterministic for RL/AI.
3. **Frame-based timing** — Durations in frames; convert via `frames / 30`
4. **Floats for coordinates** — 32-bit floats; 18x32 tile arena doesn't need doubles
5. **Match abstraction** — `GameEngine` is mode-agnostic; `Match` owns arena, players, timing, placement validation. Extend for 2v2, Triple Elixir, etc.
6. **Status Modifier System** — Multiplier-based stacking (e.g., Rage + Slow stack correctly)
7. **Lombok** — `@Getter`, `@SuperBuilder`, `@RequiredArgsConstructor`
8. **TCP + JSON-Lines** — For Python bridge (simple, debuggable)
9. **Win condition** — Crown tower destruction ends game immediately; overtime at 3 min (crown count), sudden death at 5 min (tower HP)
10. **Elixir regen** — 1 per 2.8s normal, 1 per 1.4s overtime, max 10

---

## Debug Visualizer Controls
- `SPACE` — Pause/Resume
- `P` — Show pathing
- `R` — Reset match
- `+/-` — Speed (0.25x–8x)
- `1-4` — Play blue card (random position)
- `5-8` — Play red card (random position)
- `Click` — Deploy at position

---

## Card Data Pipeline

### Source of Truth: crcsvdecoder
Card data is generated from a **separate private repository** (APK-extracted assets — kept private for legal reasons):

**Path:** `/Users/voon/PycharmProjects/PythonProject/PythonProject/crcsvdecoder/`

Key files:
- `clash_card_parser.py` — **edit this to change JSON output schema**
- `<season_folder>/summarised_assets/` — decoded CSV source data
- `<season_folder>/parsed_cards.json` — generated output (copy → cards.json here)
- `LEVEL_SCALING.md` — stat scaling formulas per rarity/level
- `decoder/lib_csv.py` — LZMA CSV codec

Season folders follow the pattern `YYYYMM_season_NN/` (e.g., `202601_season_79/`).

### When card.json schema changes are needed
**Always consult and edit `clash_card_parser.py` first**, then update `cards.json` and any Java-side consumers (DTOs, loaders).

### cards.json location
`data/src/main/resources/cards/cards.json`

### cards.json schema
```json
{
  "id": "knight",
  "name": "Knight",
  "description": "TID_SPELL_INFO_KNIGHT",
  "type": "TROOP|SPELL|BUILDING",
  "cost": 3,
  "units": [{ ... }],
  // BUILDING only:
  "buildingHealth": 400,
  "lifeTime": 40.0,
  "spawnNumber": 0,
  "spawnInterval": 2.0
}
```

Unit fields: `name, health, damage, speed, mass, collisionRadius, sightRange, range, attackCooldown, loadTime, deployTime, targetType, movementType, offsetX, offsetY, visualRadius, projectile`

Projectile fields: `name, damage, speed, radius, homing`

### Workflow for new season data
1. Place decoded CSVs in a new season folder in crcsvdecoder
2. Run `python decoder/decoder_csv.py <season_folder>`
3. Run `python clash_card_parser.py`
4. Copy `parsed_cards.json` → `data/src/main/resources/cards/cards.json`

---

## 15 Starter Cards

| Card        | Type     | Elixir | Notes                |
|-------------|----------|--------|----------------------|
| Knight      | Troop    | 3      | Melee tank           |
| Giant       | Troop    | 5      | Targets buildings only |
| Valkyrie    | Troop    | 4      | AOE melee            |
| Barbarians  | Troop    | 5      | Spawns 4             |
| Goblins     | Troop    | 2      | Spawns 3, fast       |
| Minions     | Troop    | 3      | Air, spawns 3        |
| Baby Dragon | Troop    | 4      | Air, AOE             |
| Musketeer   | Troop    | 4      | Long range           |
| Archers     | Troop    | 3      | Spawns 2             |
| Bomber      | Troop    | 2      | Ground-only AOE      |
| Arrows      | Spell    | 3      | Large radius         |
| Fireball    | Spell    | 4      | High damage          |
| Zap         | Spell    | 2      | Stun + damage        |
| Cannon      | Building | 3      | Ground defense       |
| Tombstone   | Building | 3      | Spawner              |

Extra cards added in Phase 4: Ice Wizard (Slow), Rage (spell), Freeze (spell)

---

## Tests

Run: `./gradlew :core:test :data:test` (107 tests: 104 core, 3 data)

| Test File                            | Module | Covers                                      |
|--------------------------------------|--------|---------------------------------------------|
| HealthTest                           | core   | Damage, healing, shields                    |
| TroopTest                            | core   | Builder, targeting, deployment              |
| TargetingSystemTest                  | core   | Target acquisition, filtering               |
| GameStateTest                        | core   | Entity lifecycle, win conditions            |
| GameEngineTest                       | core   | Tick loop, spawning, combat integration     |
| CombatSystemTest                     | core   | Melee, ranged, AOE, cooldowns               |
| ProjectileTest                       | core   | Movement, hit detection, AOE                |
| HandTest                             | core   | Card cycling, rotation, invalid slots       |
| DeploymentSystemTest                 | core   | Elixir spending, hand cycling               |
| MatchTest                            | core   | Player management, overtime, placement      |
| PlayerDeploymentIntegrationTest      | core   | Full deployment flow, multi-unit, stats     |
| SpawnerSystemTest                    | core   | Spawning logic independent of building class|
| CardLoaderTest                       | data   | JSON → Card object conversion               |

---

## Reference Files
- Original JS: `reference/src_og.js` (10,453 lines)
- `reference/modular/systems/AbilitySystem.js` — Dash, Jump, Inferno, Spawn, Charge
- `reference/modular/systems/ProjectileSystem.js` — Graveyard, Fireball, Log
- `reference/modular/systems/CombatSystem.js` — Hunter (shotgun), Chain Lightning
- `reference/modular/models/Troop.js` — Data schema (array index → readable names)
- `reference/modular/utils/PseudoRandom.js` — Seeded RNG (Java parity needed)
- `reference/modular/RLInterface.js` — Blueprint for Python/Gym API