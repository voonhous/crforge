# Card Data Schema

The card data consists of 4 JSON files. Cards reference units by name, units reference projectiles by name, and projectiles/units reference buffs by name.

```
cards.json  ->  units.json  ->  projectiles.json
                             ->  buffs.json
```

## Damage resolution convention

Projectile `damage` stores the intrinsic CSV value. When a unit fires a projectile:

- If the projectile's damage > 0, the **unit's** `damage` field is overridden to match (already resolved at parse time).
- If the projectile's damage = 0, the unit's CSV damage applies. The simulator uses `unit.damage` directly.

For spell cards, the projectile's `damage` is the spell's damage (always > 0).

---

## cards.json

Array of card objects in `data/src/main/resources/cards/cards.json`. Each card has a `type` field that determines which optional fields are present.

### Common fields (all card types)

| Field    | Type   | Description                                       |
|----------|--------|---------------------------------------------------|
| `id`     | string | Lowercase identifier, e.g. `"knight"`             |
| `name`   | string | Display name, e.g. `"Knight"`                     |
| `type`   | string | `"TROOP"`, `"BUILDING"`, or `"SPELL"`             |
| `rarity` | string | `"Common"`, `"Rare"`, `"Epic"`, `"Legendary"`, `"Champion"`, or `""` |
| `cost`   | int    | Elixir cost (0 for non-playable buildings)         |

### TROOP cards

| Field          | Type   | Required | Description                                          |
|----------------|--------|----------|------------------------------------------------------|
| `unit`         | string | yes      | Unit name -> lookup in `units.json`            |
| `count`        | int    | no       | Number of units deployed (omitted when 1)             |
| `summonRadius` | float  | no       | Spread radius for multi-unit deploy                   |
| `deployEffect` | object | no       | Area effect triggered on deploy (inline, see Area Effect) |

### BUILDING cards

| Field  | Type   | Required | Description                                    |
|--------|--------|----------|------------------------------------------------|
| `unit` | string | yes      | Building unit name -> lookup in `units.json` |

### SPELL cards

| Field             | Type   | Required | Description                                            |
|-------------------|--------|----------|--------------------------------------------------------|
| `projectile`      | string | no       | Projectile name -> lookup in `projectiles.json` |
| `areaEffect`      | object | no       | Area effect (inline, see Area Effect)                  |
| `summonCharacter` | string | no       | Character spawned by the spell -> lookup in `units.json` |

A spell card has at most one of `projectile`, `areaEffect`, or `summonCharacter`.

---

## units.json

Dict keyed by unit name. Contains all unique unit stat blocks: troop units, building units, and transitively spawned units (death spawns, live spawns).

### Core fields (always present)

| Field             | Type   | Description                                    |
|-------------------|--------|------------------------------------------------|
| `name`            | string | Unit name, matches the dict key                |
| `health`          | int    | Hit points                                     |
| `damage`          | int    | Damage per attack (resolved with projectile, see convention above) |
| `speed`           | float  | Movement speed (0 for buildings)               |
| `mass`            | float  | Physics mass                                   |
| `collisionRadius` | float  | Collision circle radius in tiles               |
| `sightRange`      | float  | Aggro detection range in tiles                 |
| `range`           | float  | Attack range in tiles                          |
| `attackCooldown`  | float  | Seconds between attacks                        |
| `loadTime`        | float  | Wind-up time before attack lands               |
| `deployTime`      | float  | Post-deploy stun duration                      |
| `targetType`      | string | `"GROUND"` or `"ALL"`                          |
| `movementType`    | string | `"GROUND"` or `"AIR"`                          |

### Optional fields

| Field                    | Type         | Description                                                     |
|--------------------------|--------------|-----------------------------------------------------------------|
| `projectile`             | string       | Projectile name -> lookup in `projectiles.json`          |
| `visualRadius`           | float        | Visual radius for buildings (from building_visual_radius.json)  |
| `targetOnlyBuildings`    | bool         | Only targets buildings (e.g. Giant, Golem)                      |
| `targetOnlyTroops`       | bool         | Only targets troops (e.g. RamRider)                             |
| `ignorePushback`         | bool         | Immune to knockback effects                                     |
| `ignoreTargetsWithBuff`  | string       | Ignores targets that have this buff active                      |
| `ignoreBuff`             | string       | Immune to this buff (e.g. `"VoodooCurse"`)                     |
| `minimumRange`           | float        | Minimum attack range in tiles (e.g. Mortar)                     |
| `areaDamageRadius`       | float        | Melee splash radius in tiles (e.g. Valkyrie)                   |
| `multipleTargets`        | int          | Number of targets hit per attack (e.g. ElectroWizard = 2)      |
| `multipleProjectiles`    | int          | Projectiles fired per attack (e.g. Hunter = 10)                |
| `crownTowerDamagePercent`| int          | Crown tower damage modifier percentage                          |
| `shieldHitpoints`        | int          | Shield HP (e.g. DarkPrince)                                    |
| `manaOnDeathForOpponent` | int          | Elixir given to opponent on death (e.g. ElixirGolem)           |
| `lifeTime`               | float        | Unit lifespan in seconds (buildings, temporary summons)         |
| `burst`                  | int          | Number of projectiles in a burst (e.g. Hunter)                 |
| `burstDelay`             | float        | Delay between burst projectiles in seconds                     |
| `deathSpawnProjectile`   | string       | Projectile name fired on death -> lookup in `projectiles.json` |

### Nested objects on units

**`deathDamage`** -- explosion damage on death (e.g. Golem, GiantSkeleton)

| Field    | Type  | Description              |
|----------|-------|--------------------------|
| `damage` | int   | Explosion damage         |
| `radius` | float | Explosion radius (tiles) |

**`deathSpawn`** -- array of units spawned on death (e.g. Golem -> Golemite)

| Field            | Type   | Required | Description                                |
|------------------|--------|----------|--------------------------------------------|
| `spawnCharacter` | string | yes      | Unit name -> lookup in `units.json` |
| `spawnNumber`    | int    | yes      | Number of units spawned                    |
| `spawnRadius`    | float  | no       | Spread radius for spawned units            |

**`liveSpawn`** -- periodic spawn while alive (e.g. Witch -> Skeletons)

| Field             | Type   | Required | Description                                |
|-------------------|--------|----------|--------------------------------------------|
| `spawnCharacter`  | string | yes      | Unit name -> lookup in `units.json` |
| `spawnNumber`     | int    | yes      | Number of units per spawn wave             |
| `spawnPauseTime`  | float  | no       | Seconds between spawn waves                |
| `spawnInterval`   | float  | no       | Seconds between individual spawns in a wave|
| `spawnStartTime`  | float  | no       | Delay before first spawn                   |
| `spawnRadius`     | float  | no       | Spread radius for spawned units            |
| `spawnAngleShift` | int    | no       | Angle offset between spawns (degrees)      |
| `spawnMaxAngle`   | int    | no       | Maximum spawn angle                        |
| `spawnLimit`      | int    | no       | Maximum number of spawned units alive       |
| `destroyAtLimit`  | bool   | no       | Self-destructs when spawn limit is reached |

**`buffOnDamage`** -- buff applied on each hit (e.g. Ice Wizard slows)

| Field      | Type   | Description           |
|------------|--------|-----------------------|
| `buff`     | string | Buff name -> lookup in `buffs.json` |
| `duration` | float  | Buff duration (seconds) |

**`startingBuff`** -- buff active at spawn

| Field      | Type   | Description           |
|------------|--------|-----------------------|
| `buff`     | string | Buff name -> lookup in `buffs.json` |
| `duration` | float  | Buff duration (seconds) |

**`stealth`** -- invisibility mechanic (e.g. Ghost, Tesla)

| Field               | Type   | Required | Description                              |
|---------------------|--------|----------|------------------------------------------|
| `hideTimeMs`        | int    | no       | Hide/reveal animation duration (ms)      |
| `notAttackingTimeMs`| int    | no       | Idle time before stealth activates (ms)  |
| `buff`              | string | no       | Buff name -> lookup in `buffs.json` |

**`deathAreaEffect`** -- area effect triggered on death (e.g. IceGolemite freeze)

See Area Effect schema below.

**`abilities`** -- array of special ability objects. Each has a `type` discriminator:

`CHARGE` -- charge attack (e.g. Prince, BattleRam)

| Field             | Type  | Description                          |
|-------------------|-------|--------------------------------------|
| `type`            | string| `"CHARGE"`                           |
| `damage`          | int   | Charge hit damage                    |
| `triggerRange`    | float | Range at which charge activates      |
| `speedMultiplier` | float | Speed multiplier during charge (1.0 = 100%) |

`DASH` -- dash/leap attack (e.g. MegaKnight, Bandit, GoldenKnight)

| Field                | Type  | Required | Description                          |
|----------------------|-------|----------|--------------------------------------|
| `type`               | string| yes      | `"DASH"`                             |
| `damage`             | int   | yes      | Dash hit damage                      |
| `minRange`           | float | no       | Minimum range to trigger dash        |
| `maxRange`           | float | no       | Maximum range to trigger dash        |
| `radius`             | float | no       | Impact splash radius                 |
| `cooldown`           | float | no       | Cooldown between dashes (seconds)    |
| `pushback`           | float | no       | Knockback distance                   |
| `immuneTimeMs`       | int   | no       | Damage immunity duration (ms)        |
| `landingTime`        | float | no       | Landing animation time (seconds)     |
| `constantTime`       | float | no       | Constant flight time (seconds)       |
| `dashCount`          | int   | no       | Maximum chain dash count             |
| `dashSecondaryRange` | float | no       | Chain target detection range         |
| `dashToTargetRadius` | bool  | no       | Dash targets the unit's radius       |

`HOOK` -- ranged pull attack (Fisherman)

| Field           | Type   | Required | Description                         |
|-----------------|--------|----------|-------------------------------------|
| `type`          | string | yes      | `"HOOK"`                            |
| `range`         | float  | yes      | Hook range                          |
| `minimumRange`  | float  | yes      | Minimum hook range                  |
| `loadTime`      | float  | yes      | Hook wind-up time                   |
| `dragBackSpeed` | int    | no       | Speed pulling target back           |
| `dragSelfSpeed` | int    | no       | Speed pulling self toward target    |
| `targetBuff`    | string | no       | Buff applied to hooked target       |
| `buffDuration`  | float  | no       | Buff duration (seconds)             |

`REFLECT` -- counter-attack on hit (ElectroGiant)

| Field                    | Type   | Required | Description                  |
|--------------------------|--------|----------|------------------------------|
| `type`                   | string | yes      | `"REFLECT"`                  |
| `damage`                 | int    | yes      | Reflect damage               |
| `radius`                 | float  | yes      | Reflect radius               |
| `buff`                   | string | yes      | Buff applied to attacker     |
| `buffDuration`           | float  | yes      | Buff duration (seconds)      |
| `crownTowerDamagePercent`| int    | no       | Crown tower damage modifier  |

`VARIABLE_DAMAGE` -- ramp-up damage over time (InfernoDragon, InfernoTower)

| Field    | Type         | Description                                      |
|----------|--------------|--------------------------------------------------|
| `type`   | string       | `"VARIABLE_DAMAGE"`                              |
| `stages` | list[object] | `{damage: int, timeMs: int}` -- damage at each stage |

---

## projectiles.json

Dict keyed by projectile name. Contains all unique projectile stat blocks.

### Core fields (always present)

| Field        | Type | Description                                  |
|--------------|------|----------------------------------------------|
| `name`       | string | Projectile name, matches the dict key      |
| `damage`     | int  | Intrinsic damage (0 = use firing unit's damage) |
| `speed`      | int  | Travel speed                                 |
| `homing`     | bool | Whether projectile tracks its target         |
| `aoeToAir`   | bool | Whether splash hits air units                |
| `aoeToGround`| bool | Whether splash hits ground units             |

### Optional fields

| Field               | Type         | Description                                             |
|---------------------|--------------|---------------------------------------------------------|
| `radius`            | float        | Splash radius in tiles                                  |
| `targetBuff`        | string       | Buff applied to target on hit -> lookup in `buffs.json` |
| `buffDuration`      | float        | Duration of targetBuff (seconds)                        |
| `chainedHitRadius`  | float        | Chain lightning range (tiles)                           |
| `chainedHitCount`   | int          | Number of chain bounces                                 |
| `scatter`           | string       | Scatter pattern (e.g. `"Line"`)                         |
| `projectileRange`   | float        | Maximum travel distance before expiring (tiles)         |
| `spawnProjectile`   | string       | Sub-projectile name -> lookup in `projectiles.json` |
| `spawnCount`        | int          | Number of sub-projectiles spawned                       |
| `spawnRadius`       | float        | Spread radius for sub-projectiles                       |
| `deflectBehaviours` | list[string] | Deflection flags (e.g. `"InvertDirection"`, `"UseSpellsTowerDamageMul"`) |
| `spawn`             | object       | Character spawned by the projectile (see below)         |

**`spawn`** -- character deployed by the projectile (e.g. GoblinBarrel -> Goblin)

| Field            | Type   | Required | Description                                |
|------------------|--------|----------|--------------------------------------------|
| `spawnCharacter` | string | yes      | Unit name -> lookup in `units.json` |
| `spawnNumber`    | int    | no       | Number of units spawned                    |
| `deployTime`     | float  | no       | Deploy delay (seconds)                     |

---

## buffs.json

Dict keyed by buff name. Contains gameplay stat modifiers referenced by units and projectiles.

### Core fields (always present)

| Field  | Type   | Description                      |
|--------|--------|----------------------------------|
| `name` | string | Buff name, matches the dict key  |

### Optional fields

| Field                    | Type   | Description                                       |
|--------------------------|--------|---------------------------------------------------|
| `speedMultiplier`        | int    | Movement speed modifier (percentage, e.g. -100 = frozen) |
| `hitSpeedMultiplier`     | int    | Attack speed modifier (percentage)                |
| `spawnSpeedMultiplier`   | int    | Spawn speed modifier (percentage)                 |
| `damagePerSecond`        | int    | Damage over time                                  |
| `damageReduction`        | int    | Damage reduction modifier (percentage)            |
| `healPerSecond`          | int    | Healing over time                                 |
| `crownTowerDamagePercent`| int    | Crown tower damage modifier (percentage)          |
| `buildingDamagePercent`  | int    | Building damage modifier (percentage)             |
| `hitFrequency`           | float  | Tick rate for DoT/HoT (seconds)                   |
| `attractPercentage`      | int    | Pull strength (e.g. Tornado = 360)                |
| `attractMinAngle`        | int    | Minimum pull angle                                |
| `attractMaxAngle`        | int    | Maximum pull angle                                |
| `lateralPushPercentage`  | int    | Lateral push strength                             |
| `shield`                 | int    | Shield HP granted                                 |
| `deathSpawnCount`        | int    | Number of units spawned on death of buffed unit   |
| `deathSpawn`             | string | Character spawned on death of buffed unit         |
| `chainedBuff`            | string | Secondary buff applied after this one             |
| `chainedBuffTime`        | float  | Duration of chained buff (seconds)                |
| `noEffectToCrownTowers`  | bool   | Buff has no effect on crown towers                |
| `invisible`              | bool   | Grants invisibility                               |
| `removeOnAttack`         | bool   | Buff removed when unit attacks                    |
| `removeOnHeal`           | bool   | Buff removed when unit is healed                  |
| `removeOnHit`            | bool   | Buff removed when unit is hit                     |
| `ignorePushback`         | bool   | Grants knockback immunity                         |
| `deathSpawnIsEnemy`      | bool   | Death spawn belongs to the opponent               |
| `enableStacking`         | bool   | Buff stacks with itself                           |
| `clone`                  | bool   | Buff is a clone effect                            |
| `lockTarget`             | bool   | Locks unit's current target                       |
| `hitTickFromSource`      | bool   | Damage ticks originate from buff source position  |
| `damageMultiplier`       | int    | Damage multiplier (percentage)                    |
| `damageOnHit`            | int    | Flat damage dealt on application                  |

---

## Area Effect (inline object)

Area effects appear inline on cards (`deployEffect`, `areaEffect`) and units (`deathAreaEffect`). They are not deduplicated into a separate file.

### Core fields (always present)

| Field          | Type  | Description                      |
|----------------|-------|----------------------------------|
| `name`         | string| Area effect name                 |
| `radius`       | float | Effect radius (tiles)            |
| `lifeDuration` | float | Duration the effect persists (seconds) |
| `hitsGround`   | bool  | Affects ground units             |
| `hitsAir`      | bool  | Affects air units                |

### Optional fields

| Field                    | Type   | Description                                    |
|--------------------------|--------|------------------------------------------------|
| `damage`                 | int    | Direct damage on application                   |
| `hitSpeed`               | float  | Tick rate for repeated damage (seconds)         |
| `buff`                   | string | Buff applied -> lookup in `buffs.json`  |
| `buffDuration`           | float  | Duration of the applied buff (seconds)         |
| `crownTowerDamagePercent`| int    | Crown tower damage modifier (percentage)       |
| `spawn`                  | object | Character spawn config (see below)             |
| `projectileSpawn`        | object | Character spawned via projectile (see below)   |

**`spawn`** -- periodic character spawn (e.g. Graveyard -> Skeletons)

| Field               | Type   | Required | Description                                |
|---------------------|--------|----------|--------------------------------------------|
| `spawnCharacter`    | string | yes      | Unit name -> lookup in `units.json` |
| `spawnInterval`     | float  | no       | Seconds between spawns                     |
| `spawnInitialDelay` | float  | no       | Delay before first spawn                   |
| `spawnMaxCount`     | int    | no       | Maximum total spawns                       |
| `spawnMaxRadius`    | float  | no       | Maximum spawn distance from center         |
| `spawnMinRadius`    | float  | no       | Minimum spawn distance from center         |

**`projectileSpawn`** -- character deployed via a projectile (e.g. RoyalDelivery)

| Field            | Type   | Required | Description                                |
|------------------|--------|----------|--------------------------------------------|
| `spawnCharacter` | string | yes      | Unit name -> lookup in `units.json` |
| `spawnNumber`    | int    | no       | Number of units spawned                    |
| `deployTime`     | float  | no       | Deploy delay (seconds)                     |

---

## Example: resolving a card to full data

### Troop card (Archer)

```
cards.json:   {"unit": "Archer", "count": 2, ...}
                          |
units.json:   {"name": "Archer", "damage": 44, "projectile": "ArcherArrow", ...}
                                                            |
projectiles.json: {"name": "ArcherArrow", "damage": 44, "homing": true, ...}
```

The simulator deploys 2 Archer units. Each fires ArcherArrow projectiles. Since ArcherArrow.damage > 0, it was used to override Archer.damage at parse time (both are 44 here).

### Building card (GoblinHut)

```
cards.json:   {"unit": "GoblinHut", ...}
                          |
units.json:   {"name": "GoblinHut", "liveSpawn": {"spawnCharacter": "SpearGoblin", ...}, ...}
                                                              |
units.json:   {"name": "SpearGoblin", "projectile": "SpearGoblinProjectile", ...}
                                                                  |
projectiles.json: {"name": "SpearGoblinProjectile", ...}
```

### Spell card (Fireball)

```
cards.json:       {"projectile": "FireballSpell", ...}
                              |
projectiles.json: {"name": "FireballSpell", "damage": 269, "radius": 2.5, ...}
```

### Death spawn chain (Golem)

```
units.json:   {"name": "Golem", "deathSpawn": [{"spawnCharacter": "Golemite", "spawnNumber": 2}], ...}
                                                            |
units.json:   {"name": "Golemite", "deathDamage": {"damage": 39, "radius": 2.0}, ...}
```

Golemite exists in `units.json` even though no card directly references it -- it was resolved transitively from Golem's `deathSpawn`.
