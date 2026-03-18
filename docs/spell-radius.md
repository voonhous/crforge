# Spell Radius Resolution

Spell radius in crforge comes from two independent data sources. This document explains how
they interact and which value is used at runtime.

## Two-Layer Radius System

### Card-level radius (`Card.spellRadius`)

Loaded from `cards.json` field `spellRadius`. Represents the intended spell placement/targeting
area -- the full circle the player sees when aiming the spell.

Example: Arrows has `spellRadius = 3.5` tiles.

### Projectile-level radius (`ProjectileStats.radius`)

Loaded from `projectiles.json` field `radius`. Represents per-impact AOE splash -- the damage
radius of each individual projectile hit.

Example: ArrowsSpell has `radius = 1.4` tiles.

## Resolution Priority

When both values exist, **card-level radius wins**. The logic lives in
`SpellFactory.castSpell()` (line 67):

```java
float radius = card.getSpellRadius() > 0 ? card.getSpellRadius() : proj.getRadius();
```

This is correct because:
- Card radius represents the intended spell area (what the player targets)
- Projectile radius is per-arrow splash, not the overall spell zone
- Without this override, Arrows would only hit a 1.4-tile circle instead of 3.5 tiles

## Data Pipeline Flow

```
cards.json  --[CardLoader]--> Card.spellRadius
                                     |
                                     v
projectiles.json --[ProjectileLoader]--> ProjectileStats.radius
                                              |
                                              v
                              SpellFactory picks: card > 0 ? card : proj
                                              |
                                              v
                              Projectile.aoeRadius (runtime)
```

## Projectile-Based Spells

These spells go through `SpellFactory`'s projectile path and are affected by radius resolution.

| Card | Card spellRadius | Proj radius | Effective Radius | Notes |
|------|-----------------|-------------|-----------------|-------|
| Arrows | 3.5 | 1.4 | **3.5** (card wins) | 3 waves, 10 projectiles each |
| Fireball | -- | 2.5 | 2.5 | Standard traveling spell |
| Rocket | -- | 2.0 | 2.0 | Standard traveling spell |
| Snowball | -- | 2.5 | 2.5 | Standard traveling spell |
| GoblinBarrel | -- | 1.5 | 1.5 | Spawns goblins on impact |
| Log | -- | 1.95 | 1.95 | spellAsDeploy, piercing |
| BarbLog | -- | 1.3 | 1.3 | spellAsDeploy, piercing |

Currently Arrows is the only card where both values exist. If future cards add a `spellRadius`,
the same resolution logic applies automatically.

## Area-Effect Spells

These spells return early from `SpellFactory` via the `AreaEffectFactory` path (line 53-56)
and never reach the projectile radius logic.

| Card | areaEffect radius | Notes |
|------|-------------------|-------|
| Zap | 2.5 | Instant damage + stun |
| Freeze | 3.0 | Damage + freeze buff |
| Poison | 3.5 | DoT over 8s |
| Lightning | 3.5 | Hits biggest targets |
| Earthquake | 3.5 | Ground-only slow |
| Tornado | 5.5 | Pull/knockback control |
| Graveyard | 4.0 | Spawns skeletons |
| Clone | 3.0 | Clones own troops |
| RoyalDelivery | 3.0 | Damage + recruit spawn |
| DarkMagic | 2.5 | Tiered damage |
| GoblinCurse | 3.0 | Damage + buff stacks |
| Vines | 2.5 | Snare, 3 targets max |

## Special Spells

These spells do not use either radius system:

| Card | Mechanism |
|------|-----------|
| Rage | Summons RageBottle (buff unit) |
| Heal | Summons HealSpirit |
| Mirror | Replays last card |
