# CRForge Core

Headless Clash Royale simulation engine. No GUI dependencies - can run at 1000x+ real-time speed.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         GameEngine                              │
│  Coordinates all systems, runs simulation at 30 FPS             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────┐  ┌──────────────────┐  ┌─────────────────────────┐ │
│  │  Match  │  │  DeploymentSystem│  │      GameState          │ │
│  │         │  │                  │  │                         │ │
│  │ Players │  │ Card → Entity    │  │ Entity container        │ │
│  │ Arena   │  │ Spell casting    │  │ Win conditions          │ │
│  │ Rules   │  │ Action queue     │  │ Tower tracking          │ │
│  └─────────┘  └──────────────────┘  └─────────────────────────┘ │
│                                                                 │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │
│  │ TargetingSystem │  │  CombatSystem   │  │  PhysicsSystem  │  │
│  │                 │  │                 │  │                 │  │
│  │ Target acquire  │  │ Melee/ranged    │  │ Movement        │  │
│  │ Priority rules  │  │ AOE damage      │  │ Collision       │  │
│  │ Sight ranges    │  │ Projectiles     │  │ Bounds          │  │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘  │
│                                                                 │
│  ┌───────────────────┐                                          │
│  │ EntityTimerSystem │  Deploy timers, lifetime decay,          │
│  │                   │  tower activation, grounded timer        │
│  └───────────────────┘                                          │
└─────────────────────────────────────────────────────────────────┘
```

## Match System

The `Match` class is the core abstraction for game modes. It encapsulates:

- **Arena configuration** - dimensions, tile layout
- **Player management** - 1v1, 2v2, etc.
- **Timing rules** - match duration, overtime duration
- **Placement validation** - where players can deploy cards
- **Win conditions** - mode-specific rules

### Creating Different Game Modes

Extend `Match` to create new modes:

```java
// Standard 1v1 (included)
public class Standard1v1Match extends Match {
    public int getMaxPlayersPerTeam() { return 1; }
    public int getMatchDurationTicks() { return 180 * 30; } // 3 min
    public int getOvertimeDurationTicks() { return 120 * 30; } // 2 min
}

// Example: 2v2 mode
public class Match2v2 extends Match {
    public Match2v2() {
        super(Arena.wide()); // Wider arena for 2v2
    }

    public int getMaxPlayersPerTeam() { return 2; }

    @Override
    public void createTowers(TowerSpawnCallback callback) {
        // 2v2 has different tower layout
    }
}

// Example: Triple Elixir mode
public class TripleElixirMatch extends Standard1v1Match {
    @Override
    public void update(float deltaTime) {
        // Apply 3x elixir regen
        for (Player p : getAllPlayers()) {
            p.getElixir().update(deltaTime * 3);
        }
    }
}
```

### Using the Match System

```java
// Create engine and match
GameEngine engine = new GameEngine();
Standard1v1Match match = new Standard1v1Match();

// Add players
Player blue = new Player(Team.BLUE, deck, false);
Player red = new Player(Team.RED, deck, true); // AI
match.addPlayer(blue);
match.addPlayer(red);

// Configure engine
engine.setMatch(match);
engine.initMatch();

// Game loop
while (engine.isRunning()) {
    // Get player actions from input/AI
    PlayerActionDTO action = getAction(blue);
    if (action != null) {
        engine.queueAction(blue, action);
    }

    engine.tick();
}

// Check winner
Team winner = match.getWinner();
```

## Package Structure

```
org.crforge.core/
├── arena/          # Arena, Tile, TileType
├── card/           # Card, CardRegistry, TroopStats
├── combat/         # TargetingSystem, CombatSystem
├── component/      # Health, Position, Combat, Movement
├── engine/         # GameEngine, GameState, DeploymentSystem
├── entity/         # Entity hierarchy (Troop, Building, Tower, Projectile)
├── match/          # Match, Standard1v1Match, GameMode
├── physics/        # PhysicsSystem
├── player/         # Player, Team, Deck, Hand, Elixir
└── util/           # Vector2
```

## Player System

### Components

- **Player** - Owns deck, hand, elixir. Validates card plays.
- **Deck** - 8 cards, shuffled at match start
- **Hand** - 4 active cards + next card preview
- **Elixir** - Regenerates at 1/2.8s (normal) or 1/1.4s (overtime)

### Card Deployment Flow

```
1. Player queues action → engine.queueAction(player, action)
2. Match validates placement → match.validateAction(player, action)
3. DeploymentSystem queues request
4. On tick: DeploymentSystem.update()
   a. Player.tryPlayCard() - validates elixir, cycles hand
   b. spawnTroops/spawnBuilding/castSpell - creates entities
5. Next tick: GameState.processPending() - entities become active
```

## Simulation Details

- **Tick rate**: 30 FPS (constant `DELTA_TIME = 1/30`)
- **Deterministic**: Same inputs = same outputs (critical for RL)
- **Frame-based timing**: All durations in ticks, convert via `ticks / 30`

## Building & Testing

```bash
# Build
./gradlew :core:build

# Run tests
./gradlew :core:test

# Run specific test
./gradlew :core:test --tests "GameEngineTest"
```

## Cards (15 Starter Cards)

| Card | Type | Cost | Notes |
|------|------|------|-------|
| Knight | Troop | 3 | Melee tank |
| Giant | Troop | 5 | Buildings only |
| Valkyrie | Troop | 4 | AOE melee |
| Barbarians | Troop | 5 | Spawns 4 |
| Goblins | Troop | 2 | Spawns 3, fast |
| Minions | Troop | 3 | Air, spawns 3 |
| Baby Dragon | Troop | 4 | Air, AOE |
| Musketeer | Troop | 4 | Long range |
| Archers | Troop | 3 | Spawns 2 |
| Bomber | Troop | 2 | Ground-only AOE |
| Arrows | Spell | 3 | Large radius |
| Fireball | Spell | 4 | High damage |
| Zap | Spell | 2 | Stun + damage |
| Cannon | Building | 3 | Ground defense |
| Tombstone | Building | 3 | Spawner |