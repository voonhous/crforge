# Physics & Status Effects

> Part of the [Architecture Reference](architecture.md).

---

## Physics

`PhysicsSystem` handles movement, lane-based pathfinding, river jumping, knockback, collisions, and
arena bounds.

### Movement Pipeline (per tick)

For each movable entity (not BUILDING):

1. Skip attached units (position set by `AttachedUnitSystem`)
2. Knockback active -> apply knockback displacement, return
3. Attack dash active -> apply dash displacement, return
4. Cannot move or deploying -> return
5. Update river jump state
6. Dashing ability active -> skip (handled by `AbilitySystem`)
7. Tunneling -> skip (handled by `AbilitySystem`)
8. Returning projectile in flight (non-pingpong) -> freeze movement
9. Already in attack range of target -> stop
10. Has target -> `moveTowardTarget()`, else -> `moveTowardEnemySide()`

### Lane-Based Pathfinding

`BasePathfinder` implements `Pathfinder` interface:

- **Air units**: straight line to target (ignore terrain)
- **Jump-enabled and hovering troops**: use AIR pathfinding
- **Ground units**: route via bridges when crossing the river
    - Determines left/right lane based on current X position
    - Routes to nearest bridge entrance, crosses, then exits toward target
    - Targets princess tower in lane if alive, otherwise crown tower

### River Jump

For troops with `jumpEnabled=true` (HogRider, Prince, DarkPrince, Ram, RoyalHog):

- Entering river zone (Y 15-17) outside bridge tiles activates jump
- `JUMP_SPEED_MULTIPLIER = 4/3` applied via `ModifierSource.ABILITY_JUMP`
- Exiting river zone or entering bridge deactivates jump
- Jumping troops use AIR pathfinding (straight line over river)

### Knockback

- `KNOCKBACK_DURATION = 0.5f` seconds (15 frames)
- `Movement.startKnockback(dirX, dirY, distance, duration, maxTime)`
- Knockback speed = `distance / maxTime` tiles/second
- Overrides pathfinding; knocked-back entities skip collision resolution
- Immune: buildings, entities with `ignorePushback=true`

### Collision Resolution

Circle-circle collision detection using `collisionRadius`:

- Quick squared-distance check first for early rejection
- Push ratios based on mass: `ratioA = massB / (massA + massB)`
- Buildings have mass 0 (infinite mass, immovable)
- Air units collide only with other air units
- No collision for: attached units, dashing entities, knocked-back entities, invisible entities

### Sliding

When a troop collides with a building:

- Calculate tangent vector perpendicular to collision normal
- Apply slide adjustment in direction matching troop's intended movement
- `SLIDE_FACTOR = 0.5f` prevents troops from getting permanently stuck on building corners

### Attack Dash (Two-Phase Lunge)

- Phase 1 (LUNGING): move toward target for `attackDashDuration` seconds
- Phase 2 (RETURNING): return to origin for same duration
- Used by melee units like Bat that lunge when attacking

### Bounds Enforcement

Every frame: clamp position within `[collisionRadius, dimension - collisionRadius]`.

**Key files:**

- `core/.../physics/PhysicsSystem.java`
- `core/.../physics/BasePathfinder.java`
- `core/.../component/Movement.java`
- `core/.../component/ModifierSource.java`

---

## Status Effects

`StatusEffectSystem` applies multiplier-based stacking from `BuffDefinition` data.

### Effect Types

| Type          | Effect                                             | Example Source   |
|---------------|----------------------------------------------------|------------------|
| STUN          | Prevents movement + attacking, resets attack state | Zap              |
| SLOW          | Reduces movement + attack speed                    | Ice Wizard       |
| RAGE          | Increases movement + attack speed                  | Rage spell       |
| FREEZE        | Total halt (movement + combat + regen)             | Freeze spell     |
| BURN          | Damage over time                                   | --               |
| KNOCKBACK     | Forced displacement                                | Fireball         |
| POISON        | DOT + prevents invisibility                        | Poison spell     |
| VULNERABILITY | Increases damage taken                             | --               |
| CURSE         | Deaths spawn unit for opponent                     | Mother Witch     |
| EARTHQUAKE    | Increased damage to buildings                      | Earthquake spell |
| TORNADO       | Drags entities toward effect center                | Tornado spell    |

### Multiplier Stacking

Multipliers stack **multiplicatively**, not additively.

Raw value conversion (`BuffDefinition.convertRawMultiplier()`):

- `raw > 0`: `raw / 100.0` (e.g. 130 -> 1.3x for Rage)
- `raw < 0`: `1.0 - abs(raw) / 100.0` (e.g. -30 -> 0.7x for Slow)
- `raw == 0`: 1.0 (no effect)

Example: Rage (130) + Slow (-30) = 1.3 * 0.7 = 0.91x speed.

Zero or negative computed multiplier triggers stun behavior (e.g. Freeze -100 = 0.0x).

### Application

- Effects applied to entities via `AoeDamageService.applyEffects()` or `CombatSystem` buff-on-damage
- `AppliedEffect` stores: type, duration, buffName, intensity, spawnSpecies
- `BuffDefinition` looked up from `BuffRegistry` by buffName at runtime
- Computed multipliers applied to `Movement` and `Combat` components via
  `ModifierSource.STATUS_EFFECT`
- Only STATUS_EFFECT source can clear STATUS_EFFECT modifiers (prevents cross-system trampling)

### Stun/Freeze Reset

On application of STUN or FREEZE:

- `Combat.resetAttackState()` -- resets windup, cooldown, accumulatedLoadTime, targetLocked
- `ChargeHandler.consumeCharge()` -- resets charge state
- `VariableDamageHandler.resetVariableDamage()` -- resets inferno damage to stage 0

### Data-Driven Path

1. `buffs.json` loaded by `BuffLoader` into `BuffRegistry`
2. `BuffDefinition` stores raw multiplier values: `speedMultiplier`, `hitSpeedMultiplier`,
   `spawnSpeedMultiplier`, plus `damagePerSecond`, `healPerSecond`, `buildingDamagePercent`,
   `hitFrequency`
3. `AppliedEffect.getBuffDefinition()` resolves at runtime
4. `StatusEffectSystem.processEntityEffects()` iterates effects, multiplies multipliers, applies to
   components
5. `controlledByParent` buffs (Tornado, persistent zones) don't self-expire; duration managed by
   parent `AreaEffect`

### Attached Unit Propagation

Attached units (Ram Rider on Ram) skip `StatusEffectSystem` processing. Instead,
`AttachedUnitSystem` copies parent's STATUS_EFFECT modifiers (speed, combat disable, attack speed)
to child each tick.

**Key files:**

- `core/.../effect/StatusEffectSystem.java`
- `core/.../effect/StatusEffectType.java`
- `core/.../effect/AppliedEffect.java`
- `core/.../effect/BuffDefinition.java`
- `core/.../effect/BuffRegistry.java`
- `core/.../entity/AttachedUnitSystem.java`
