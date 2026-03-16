# crforge Architecture

## Module Structure

```
crforge/
├─ core/           Headless simulation engine (no GUI dependencies)
├─ data/           Card/unit config loading (JSON -> Card objects)
├─ desktop/        LibGDX visualization (ShapeRenderer debug renderer)
├─ gym-bridge/     TCP server for Python Gymnasium integration
└─ reference/      Original JS source and refactored ES6 modules
```

- **core** depends on nothing (pure Java 17 + Lombok)
- **data** depends on core (loads JSON into core model classes)
- **desktop** depends on core + data + LibGDX
- **gym-bridge** depends on core + data + JeroMQ

## Core Package Layout

```
org.crforge.core/
├─ ability/
│  ├─ AbilityComponent, AbilitySystem
│  └─ ChargeAbility, DashAbility, HookAbility, TunnelAbility, ...
├─ arena/
│  └─ Arena, Tile, TileType
├─ card/
│  ├─ Card, CardType, Rarity, LevelScaling
│  ├─ TroopStats, ProjectileStats, AreaEffectStats, EffectStats
│  └─ AttackSequenceHit, LiveSpawnConfig, ...
├─ combat/
│  ├─ TargetingSystem, CombatSystem
│  ├─ ProjectileSystem, AoeDamageService
│  └─ AoeDamageEvent
├─ component/
│  ├─ Health, Position, Combat, Movement
│  ├─ SpawnerComponent, AttachedComponent
│  └─ ElixirCollectorComponent, ModifierSource
├─ effect/
│  ├─ StatusEffectType, AppliedEffect, StatusEffectSystem
│  └─ BuffDefinition, BuffRegistry
├─ engine/
│  ├─ GameEngine               Tick loop, system orchestration
│  ├─ GameState                Entity lifecycle, queries
│  ├─ DeploymentSystem         Deployment orchestration (queue, sync delay, stagger)
│  ├─ EntityFactory            Entity construction (troops, buildings, spells, projectiles)
│  ├─ DeathHandler
│  └─ ElixirCollectionSystem
├─ entity/
│  ├─ base/
│  │  ├─ Entity, AbstractEntity
│  │  ├─ EntityType, MovementType, TargetType
│  │  └─ SpawnerSystem
│  ├─ unit/        -> Troop
│  ├─ structure/   -> Building, Tower
│  ├─ projectile/  -> Projectile
│  └─ effect/      -> AreaEffect, AreaEffectSystem
├─ match/
│  └─ Match, Standard1v1Match, GameMode
├─ physics/
│  └─ PhysicsSystem
├─ player/
│  ├─ Player, Team, Deck, Hand, Elixir, LevelConfig
│  └─ dto/ -> PlayerActionDTO
└─ util/
   └─ Vector2, FormationLayout
```

## System Responsibilities

| System             | Responsibility                                                    |
|--------------------|-------------------------------------------------------------------|
| GameEngine         | Orchestrates tick loop, coordinates all systems                   |
| GameState          | Entity registry, alive cache, spawn/death queues                  |
| TargetingSystem    | Assigns combat targets based on range, type, priority             |
| CombatSystem       | Attack execution: windup, melee damage, multi-target, recoil      |
| ProjectileSystem   | Projectile lifecycle: movement, hit detection, AOE, chain, pierce |
| AoeDamageService   | Damage dealing, status effect application, AOE radius checks      |
| DeploymentSystem   | Deployment orchestration (queue, sync delay, stagger timing)      |
| EntityFactory      | Entity construction with level-scaled stats (used by Deployment)  |
| SpawnerSystem      | Spawner ticks, death-spawn, live-spawn (Witch, Tombstone, etc.)   |
| PhysicsSystem      | Movement, collision avoidance, knockback displacement             |
| StatusEffectSystem | Manages buff/debuff durations and multiplier stacking             |
| AbilitySystem      | Charge, dash, variable damage (inferno), reflect, hook            |
| AreaEffectSystem   | Ticking/one-shot area effects (Poison, Freeze, heal zones)        |
| AttachedUnitSystem | Syncs position/death/effects for attached units (Ram Rider)       |

## Dependency Graph (Systems)

```
AoeDamageService    --> GameState
ProjectileSystem    --> GameState, AoeDamageService, UnitSpawner (functional interface)
CombatSystem        --> GameState, AoeDamageService, ProjectileSystem
SpawnerSystem       --> GameState, AoeDamageService, Match
DeploymentSystem    --> EntityFactory
EntityFactory       --> GameState, AoeDamageService
StatusEffectSystem  --> GameState (passed per call)
TargetingSystem     --> (stateless, receives entity list)
PhysicsSystem       --> Arena, GameState
AbilitySystem       --> GameState
AreaEffectSystem    --> GameState
AttachedUnitSystem  --> GameState
GameState           --> DeathHandler (functional interface -> SpawnerSystem::onDeath)
GameEngine          --> all systems (orchestrator)
```

Key constraint: no circular dependencies between systems. Cross-system callbacks
use functional interfaces (UnitSpawner, DeathHandler) wired at construction time.

## Tick Loop Order

GameEngine.tick() runs these steps each frame (30 FPS):

```
 1. processPending        Flush spawn/removal queues, refresh alive cache
 2. match.update          Elixir regen, overtime transitions
 3. DeploymentSystem      Process queued card deploys (server sync delay)
 4. SpawnerSystem         Tick live-spawners (Witch skeletons, Furnace spirits)
 5. StatusEffectSystem    Decrement durations, recalculate multipliers
 5.5 AttachedUnitSystem   Sync attached unit positions and propagate effects
 6. Entity.update         Per-entity timers (deploy, cooldowns, lifetime)
 7. AreaEffectSystem      Tick damage/buff zones (Poison, Freeze, Rage)
 8. TargetingSystem       Assign/update combat targets
 9. AbilitySystem         Update charge state, variable damage ramp
10. CombatSystem          Execute attacks (delegates to ProjectileSystem for ranged)
    ProjectileSystem      Move projectiles, detect hits, chain/pierce/knockback
11. PhysicsSystem         Entity movement, collision avoidance, path following
12. processDeaths         Run death handlers (death spawn, death projectile)
13. checkTimeLimit        Overtime entry, win conditions, sudden death
14. incrementFrame        Advance frame counter
```

## Entity Lifecycle

```
Card deploy -> DeploymentSystem validates + spends elixir
            -> GameState.spawnEntity (queued)
            -> processPending (next tick) adds to alive list
            -> Entity.update decrements deployTimer
            -> Once deployed: targetable, can attack, physics-active
            -> Health reaches 0 -> marked dead
            -> processDeaths -> DeathHandler callback
               -> SpawnerSystem.onDeath: death spawn, death projectile
            -> Removed from alive list next processPending
```

## Data Flow (Card Loading)

```
buffs.json       -> BuffLoader    -> BuffRegistry (Map<String, BuffDefinition>)
projectiles.json -> ProjectileLoader -> Map<String, ProjectileStats>
                    (resolves spawnProjectile string refs to ProjectileStats)
units.json       -> UnitLoader    -> Map<String, TroopStats>
                    (resolves projectile + deathSpawn string refs)
cards.json       -> CardLoader    -> List<Card> -> CardRegistry
                    (resolves unit + projectile string refs)
```

Loading order matters: each loader resolves string references against
previously-loaded maps. Schema changes propagate from the card data
generator to the DTOs and loaders here.
