# Deployment, Spawning & Transformation

> Part of the [Architecture Reference](architecture.md).

---

## Deployment

`DeploymentSystem` processes card play requests through a two-phase pipeline.

### Constants

- `PLACEMENT_SYNC_DELAY = 1.0f` seconds -- server sync delay before spawning
- `STAGGER_DELAY = 0.1f` seconds -- delay between multi-unit troop spawns

### Pipeline

1. **Request phase**: `queueAction(player, action)` adds to thread-safe `ConcurrentLinkedQueue`. On
   drain, `player.tryPlayCard()` spends elixir and cycles hand.
2. **Sync phase**: `PendingDeployment.remainingDelay` counts down from 1.0s.
3. **Stagger phase** (multi-unit troops only): One unit spawned per stagger tick (0.1s apart).
   Deploy effects and spawn projectiles fire only on the first unit.
4. Buildings and spells spawn all at once after sync (no stagger).

### Special Deployment Types

- **Deploy effects**: `Card.deployEffect` fires an `AreaEffect` at deploy position on first unit
  spawn (e.g. ElectroWizard zap on landing)
- **Spawn projectiles**: `Card.spawnProjectile` fires a projectile at deploy position (e.g.
  MegaKnight landing damage)
- **Tunnel buildings**: `DeploymentSystem` calls `entityFactory.spawnTunnelBuilding()` for
  Miner/Goblin Drill; the tunneling troop morphs into a building on arrival via `TunnelMorphHandler`
- **Variant resolution**: Some cards resolve to different forms based on game state (e.g.
  MergeMaiden mounted vs. normal, determined by pre-spend elixir)

**Key files:**

- `core/.../engine/DeploymentSystem.java`

---

## Spawning and Death

Two systems handle spawning: `SpawnerSystem` for live/periodic spawning, `DeathHandler` for
death-triggered mechanics.

### Periodic Spawning (Live Spawn)

`SpawnerComponent` uses a two-state FSM:

- **WAITING_FOR_WAVE**: counts down `spawnPauseTime` (or `spawnStartTime` for initial delay)
- **SPAWNING_WAVE**: counts down `spawnInterval` between units within a wave

Configuration: `spawnPauseTime` (delay between waves), `spawnInterval` (delay within wave),
`unitsPerWave`, `spawnStartTime` (initial delay).

Pausing behavior:

- Stunned/frozen buildings: timer pauses but does NOT reset; resumes from same point
- `spawnOnAggro=true`: timer only ticks when enemies detected within `aggroDetectionRange`
- Deploying entities: timer does not tick

Spawn limits: `spawnLimit` caps total spawned units; `destroyAtLimit=true` kills parent when limit
reached.

Clone propagation: if parent is clone, spawned children are also clones (1 HP).

### Bomb Entities

Units with `health=0` in stats are treated as bombs:

- Spawned with 1 HP and `selfDestruct=true`
- Deploy timer counts down (bomb fall animation)
- After deployment completes, `SpawnerSystem` kills the bomb
- Death triggers AOE damage via `DeathHandler`
- Examples: BalloonBomb (3.0s deploy), GiantSkeletonBomb

### Death Processing Order

`DeathHandler.onDeath()` executes in this order:

1. **Elixir grants (owner)**: Building's `manaOnDeath` via `ElixirCollectorComponent`
2. **Death damage AOE**: `AoeDamageService.applySpellDamage()` in radius + knockback
3. **Death spawns**: immediate or delayed via `DeathSpawnEntry` list
    - Immediate spawns (`spawnDelay=0`): fire via `SpawnFactory.doSpawn()`
    - Delayed spawns (`spawnDelay>0`): queued to `pendingDeathSpawns`, ticked by
      `processDelayedSpawns()`
    - Formation positioning via `FormationLayout.calculateOffset()` (circular layout)
    - Clone status propagated from parent to children
4. **Death area effect**: spawns `AreaEffect` entity (e.g. RageBarbarianBottle drops Rage zone)
5. **Elixir grants (opponent)**: `manaOnDeathForOpponent` grants elixir to enemy (Elixir Golem)
6. **Death projectile**: fires projectile at death location (Phoenix -> PhoenixFireball), may spawn
   character on impact
7. **Curse spawns**: CURSE effects trigger character spawn for the applying team (Mother Witch ->
   Cursed Hog)

### SpawnFactory

`SpawnFactory.doSpawn()` constructs the entity:

- Level scales HP, damage, shield via `LevelScaling.scaleCard()`
- Clone handling: 1 HP, shield capped to 1
- Combat component: built if damage > 0 or projectile exists
- Preload: `accumulatedLoadTime = noPreload ? 0 : loadTime`
- Deploy time: from TroopStats for bombs, from parameter for death spawns, else 0
- Wires `SpawnerComponent` for death mechanics, bomb behavior, live spawn capability

### Formation Layout

`FormationLayout.calculateOffset()` for N units in a circle:

- N == 1: offset (0, 0)
- N > 1: evenly spaced on circle with `spawnRadius`; odd N starts at angle PI/2 (top), even N at
  angle 0 (right)
- `TILE_SCALE = 355.0f` converts raw CSV summonRadius to tile units

**Key files:**

- `core/.../entity/SpawnerSystem.java`
- `core/.../entity/SpawnFactory.java`
- `core/.../entity/DeathHandler.java`
- `core/.../component/SpawnerComponent.java`
- `core/.../util/FormationLayout.java`

---

## HP-Threshold Transformation

`TransformationSystem` checks alive troops each tick for HP thresholds and replaces them with a new
form.

### Process

1. For each `Troop` with `transformConfig != null` and `!transformed`:
    - Skip if deploying
    - Check `health.percentage() * 100 <= config.healthPercent()`
2. On threshold: `transform(troop, config)`:
    - Capture old position, current HP, level, rarity
    - `old.markDead()` -- suppresses death handlers
    - Remove old entity from `GameState`
    - Scale new form's stats by level/rarity
    - Build replacement `Troop` with `transformed=true`
    - Proportional HP carryover (capped to new form's max)
    - Reset attack cooldown/load time
    - New form's movement stats and death spawns apply

Example: GoblinDemolisher at 50% HP -> kamikaze form with shorter lifetime, different movement
stats, and its own death spawns.

**Key file:** `core/.../engine/TransformationSystem.java`
