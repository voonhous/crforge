# Targeting, Combat & Abilities

> Part of the [Architecture Reference](architecture.md).

---

## Targeting

`TargetingSystem` implements two-phase target locking with support for building pull, sight ranges,
minimum range blind spots, and invisibility.

### Two-Phase Locking

- **Unlocked phase** (moving): scans for closest enemy within sight range. Retargets freely every
  tick.
- **Locked phase** (in attack range, `combat.targetLocked=true`): stays locked on current target.
  Only unlocks when target becomes invalid (dead, out of retention range, invisible, etc.).
- Retention range = `sightRange * 1.5f + collisionRadii` -- target stays valid at 1.5x sight range
  before being dropped
- Building-targeting troops (`targetOnlyBuildings=true`) always retarget to closest building
  regardless of lock state

### Range Calculation

All ranges use edge-to-edge distance (center-to-center minus collision radii):

```
effectiveSightRange = sightRange + attacker.collisionRadius + target.collisionRadius
effectiveMinRange   = minimumRange + attacker.collisionRadius + target.collisionRadius
```

Default sight range: 5.5 tiles (per `Combat` component).

### Target Filtering

`canTarget()` applies these filters in order:

1. `targetOnlyBuildings` -- ignores non-building entities (Giant, Hog Rider)
2. `targetOnlyTroops` -- ignores buildings (Ram Rider bola)
3. `ignoreTargetsWithBuff` -- skips targets with specific buff (Ram Rider + BolaSnare)
4. `TargetType` vs `MovementType` matching:
    - `ALL` -> any target
    - `GROUND` -> GROUND or BUILDING
    - `AIR` -> AIR only
    - `BUILDINGS` -> BUILDING only

### Invisibility

- Invisible troops (Royal Ghost in stealth) cannot be targeted by units
- Still hit by AOE spells and piercing projectiles
- `StealthAbility` controls visibility via fade timer

### Skipped Entities

Targeting skips: inactive towers, entities without combat, deploying troops, tunneling troops,
deploying buildings.

**Key file:** `core/.../combat/TargetingSystem.java`

---

## Combat

`CombatSystem` handles attack execution, projectile management, and damage application.

### Attack Pipeline

For each entity with a `Combat` component:

1. Skip inactive/waking towers, deploying entities, knocked-back entities
2. If no target: reset attack state, return
3. If out of attack range: unlock target, cancel ongoing attack
4. Lock target (in range)
5. If cooldown > 0: wait
6. If charged (via `ChargeAbility`): skip windup, execute immediately
7. Start attack: initiate windup timer
    - If `attackDashTime > 0` (Bat): lunge toward target during windup
8. Windup complete: execute attack

### Melee vs. Ranged

Threshold: `RANGED_THRESHOLD = 2.0f` tiles. Range >= 2.0 uses projectiles.

**Melee attack:**

- Apply pre-damage effects (e.g. CURSE)
- Deal damage (adjusted for crown tower via `crownTowerDamagePercent`)
- If `aoeRadius > 0`: `AoeDamageService.applySpellDamage()` centered on attacker or target (
  `selfAsAoeCenter`)
- Apply `buffOnDamage` (melee only)
- Check REFLECT ability on target

**Ranged attack:**

- `scatter != null` + `multipleProjectiles > 1`: `fireScatterProjectiles()` (Hunter shotgun)
- Otherwise: `createAttackProjectile()` via `ProjectileFactory`, spawn into `GameState.projectiles`
- If returning projectile (Executioner): disable combat until projectile returns

### Damage Calculation

```
baseDamage = damageOverride > 0 ? damageOverride : effectiveDamage
baseDamage += ChargeHandler.getChargeDamage(ability, baseDamage)
baseDamage += BuffAllyHandler.processGiantBuffHit(attacker, target, combat)
```

Crown tower adjustment via `DamageUtil.adjustForCrownTower()`:

```
effectiveDamage = max(1, baseDamage * (100 + crownTowerDamagePercent) / 100)
```

Example: Miner with `crownTowerDamagePercent = -75` deals 25% damage to towers.

### Multiple Targets

Units with `multipleTargets > 1` (ElectroWizard hits 2):

- After primary attack, find extra targets within attack range (closest first)
- Each extra target receives full damage + buff-on-damage
- If not enough extra targets, fallback shots hit primary again

### Kamikaze

Units with `kamikaze=true` (Battle Ram): die immediately after executing their attack.

### Load Time and Windup

- `loadTime` -- troops accumulate charge while idle/moving/deploying, capped at `loadTime`
- First attack windup: `max(0, attackCooldown - accumulatedLoadTime)`
- Load time preloading: spawned troops start with `accumulatedLoadTime = loadTime` (fully charged)
- Exception: `noPreload=true` (Sparky) starts at 0
- Combat timer ticking (cooldown, windup, load time accumulation) is handled by `CombatSystem`,
  not by entity update methods

### Attack Sequences

`Combat.attackSequence` enables multi-hit combos (Berserker). `attackSequenceIndex` cycles through
the sequence, each entry may override damage.

### Post-Attack Actions

1. `areaEffectOnHit`: spawn heal zone (BattleHealer)
2. `attackPushBack > 0`: recoil knockback on attacker (Firecracker)
3. `kamikaze`: attacker dies

**Key files:**

- `core/.../combat/CombatSystem.java`
- `core/.../combat/AoeDamageService.java`
- `core/.../combat/ProjectileSystem.java`
- `core/.../combat/KnockbackHelper.java`
- `core/.../combat/DamageUtil.java`
- `core/.../component/Combat.java`

---

## Abilities

`AbilitySystem` dispatches to type-specific `AbilityHandler` implementations via a registry map.
Runs before `CombatSystem` so damage modifications apply on the current tick.

Runtime state for all ability types lives in `AbilityComponent`.

### Ability Registry

| Type            | Record Class            | Handler                 | Example Units                  |
|-----------------|-------------------------|-------------------------|--------------------------------|
| CHARGE          | `ChargeAbility`         | `ChargeHandler`         | Prince, Dark Prince, Ram Rider |
| VARIABLE_DAMAGE | `VariableDamageAbility` | `VariableDamageHandler` | Inferno Tower, Inferno Dragon  |
| DASH            | `DashAbility`           | `DashHandler`           | Bandit, Mega Knight            |
| HOOK            | `HookAbility`           | `HookHandler`           | Fisherman                      |
| REFLECT         | `ReflectAbility`        | `ReflectHandler`        | Electro Giant                  |
| TUNNEL          | `TunnelAbility`         | `TunnelHandler`         | Miner, Goblin Drill            |
| STEALTH         | `StealthAbility`        | `StealthHandler`        | Royal Ghost                    |
| HIDING          | `HidingAbility`         | `HidingHandler`         | Tesla                          |
| BUFF_ALLY       | `BuffAllyAbility`       | `BuffAllyHandler`       | Rune Giant (GiantBuffer)       |
| RANGED_ATTACK   | `RangedAttackAbility`   | `RangedAttackHandler`   | Goblin Machine                 |

### Per-Type Details

**CHARGE** -- Builds charge from continuous uninterrupted movement. Charged first attack deals
`chargeDamage` bonus and applies `speedMultiplier` while moving. Resets on attack, stun, knockback,
or freeze. `ChargeHandler.getChargeDamage()` and `consumeCharge()` are static helpers called by
`CombatSystem`.

**VARIABLE_DAMAGE** -- Damage escalates through ordered stages while attacking the same target. Each
`VariableDamageStage` has a duration and damage value. Resets to stage 0 when target changes or on
stun/freeze. Sets `Combat.damageOverride`.

**DASH** -- States: IDLE -> DASHING -> LANDING. Acquisition range `[dashMinRange, dashMaxRange]`.
Provides invulnerability during flight (`dashImmuneTime`). DASH_SPEED = 15 tiles/sec. Landing deals
`dashDamage` in `dashRadius` with optional `dashPushback`.

**HOOK** -- States: IDLE -> WINDING_UP -> PULLING -> DRAGGING_SELF. Pulls target toward Fisherman (
buildings cannot be pulled, skips to DRAGGING_SELF). Speed base: raw speed / 60.0.
`ModifierSource.ABILITY_HOOK` persists through StatusEffect resets.

**REFLECT** -- Passive. Called by `CombatSystem` on melee hit. Reflects `reflectDamage` back to
attacker within `reflectRadius`, with optional status effect (`reflectBuff`). Adjusts for
`reflectCrownTowerDamagePercent`.

**TUNNEL** -- States: INACTIVE -> TUNNELING -> EMERGED. 8-directional underground movement with
waypoint-based routing. Runs during deployment (unlike other abilities). `TunnelMorphHandler`
converts tunneling troop into building on arrival.

**STEALTH** -- While not attacking: `fadeTime` ticks toward invisibility. While attacking:
`attackGracePeriod` before becoming visible. Spawns invisible with pre-filled fade timer. Invisible
troops are untargetable but hittable by AOE.

**HIDING** -- States: HIDDEN -> REVEALING -> UP -> HIDING -> HIDDEN. HIDDEN: combat disabled, not
targetable. REVEALING: targetable, combat disabled. UP: normal operation. Target detection cancels
hide transition back to UP.

**BUFF_ALLY** -- Every `cooldown` seconds (after initial `actionDelay`), targets `maxTargets`
closest friendly troops within `searchRange`. Applies damage buff; buffed troops deal `addedDamage`
bonus every `attackAmount`th attack. `persistAfterDeath` seconds keeps buff active after source
dies. `damageMultipliers` list provides per-projectile/unit overrides.

**RANGED_ATTACK** -- Independent secondary ranged attack for dual-attack units. States: IDLE ->
WINDING_UP -> ATTACK_DELAY -> COOLDOWN. Fires projectiles independently from primary `CombatSystem`
attack with its own `targetType` filtering.

### State Enums

- `DashState`: IDLE, DASHING, LANDING
- `HookState`: IDLE, WINDING_UP, PULLING, DRAGGING_SELF
- `TunnelState`: INACTIVE, TUNNELING, EMERGED
- `HidingState`: HIDDEN, REVEALING, UP, HIDING
- `RangedAttackState`: IDLE, WINDING_UP, ATTACK_DELAY, COOLDOWN

**Key files:**

- `core/.../ability/AbilitySystem.java`
- `core/.../ability/AbilityComponent.java`
- `core/.../ability/AbilityType.java`
- `core/.../ability/AbilityData.java` (sealed interface with 10 record permits)
- `core/.../ability/*Handler.java` (10 handler files)
