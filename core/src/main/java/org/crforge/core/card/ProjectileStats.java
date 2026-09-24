package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Defines the configuration for a projectile. Used by Troops (ranged attacks) and Spells (e.g.
 * Fireball).
 */
@Getter
@Builder
public class ProjectileStats {

  private final String name;
  @Builder.Default private final int damage = 0;
  // Travel speed in game units per second (15,000 = 15 tiles per second)
  @Builder.Default private final float speed = 15000f;

  // Travel speed as published: game units per 50 ms step. The battle core reads this directly.
  @Builder.Default private final int rawSpeed = 0;

  // Ballistic arc: the flight's height follows a parabola under this gravity; 0 flies straight
  @Builder.Default private final int gravity = 0;

  // The row's own rarity; a projectile's damage is scaled at the launcher's level re-based on it
  @Builder.Default private final Rarity rarity = Rarity.UNKNOWN;

  // "KingTower" or "PrincessTower" when the damage scales as a crown tower's, else null
  private final String damageScalingMode;

  // Homing that lasts this many milliseconds, taken up only from at least homingMinDistance away
  @Builder.Default private final int homingTime = 0;
  @Builder.Default private final int homingMinDistance = 0;

  // Spatial fields are in integer game units (1,000 per tile)
  @Builder.Default private final int radius = 0; // AOE radius
  @Builder.Default private final int radiusY = 0; // Elliptical AOE depth (Log/BarbLog)
  @Builder.Default private final boolean homing = true;
  @Builder.Default private final List<EffectStats> hitEffects = new ArrayList<>();

  // Chain lightning: after primary hit, chain to N more targets within radius
  @Builder.Default private final int chainedHitRadius = 0;
  @Builder.Default private final int chainedHitCount = 0;

  // AOE targeting flags
  @Builder.Default private final boolean aoeToAir = false;
  @Builder.Default private final boolean aoeToGround = false;

  // The area damage spares the owner's own side; without it the owner's side is hit too
  @Builder.Default private final boolean onlyEnemies = false;

  // Non-homing scatter projectiles expire at this range
  @Builder.Default private final int projectileRange = 0;

  // Scatter pattern type (e.g. "Line" for Hunter shotgun fan)
  private final String scatter;

  // Returning: piercing projectile reverses at max range and travels back (e.g. Executioner axe)
  @Builder.Default private final boolean returning = false;

  // Pingpong moving shooter: thrower keeps moving while returning projectile is in flight
  @Builder.Default private final float pingpongMovingShooter = 0f;

  // Spawn sub-projectile on impact
  private final ProjectileStats spawnProjectile;
  @Builder.Default private final int spawnCount = 0;
  @Builder.Default private final int spawnRadius = 0;

  // Knockback distance on hit (game units)
  @Builder.Default private final int pushback = 0;
  @Builder.Default private final boolean pushbackAll = false;

  // Piercing hit detection radius (distinct from AOE splash radius, e.g. Log rolling projectile)
  @Builder.Default private final int projectileRadius = 0;

  // Min travel distance before hits register (e.g. Log must roll past deploy point)
  @Builder.Default private final int minDistance = 0;

  // Projectile stops on first hit instead of piercing through (e.g. Hunter pellets)
  @Builder.Default private final boolean checkCollisions = false;

  // Crown tower damage reduction (e.g. -70 = 30% damage to towers)
  @Builder.Default private final int crownTowerDamagePercent = 0;

  // Spawn area effect on impact (Heal Spirit heal zone, etc.)
  private final AreaEffectStats spawnAreaEffect;

  // Spawn character on impact (e.g. PhoenixFireball spawns PhoenixEgg)
  private final String spawnCharacterName; // Unresolved name from JSON
  private final TroopStats spawnCharacter; // Resolved reference
  @Builder.Default private final int spawnCharacterCount = 1;

  // Deploy time for spawned characters (e.g. Goblin Barrel goblins deploy over 1.1s)
  @Builder.Default private final float spawnDeployTime = 0f;

  /**
   * Returns a copy of this ProjectileStats with a different damage value. All other fields are
   * preserved.
   */
  public ProjectileStats withDamage(int newDamage) {
    return copyBuilder().damage(newDamage).build();
  }

  /**
   * Returns a copy of this ProjectileStats with a resolved spawn character. All other fields are
   * preserved.
   */
  public ProjectileStats withSpawnCharacter(TroopStats resolvedSpawnCharacter) {
    return copyBuilder().spawnCharacter(resolvedSpawnCharacter).build();
  }

  /**
   * Returns a copy of this ProjectileStats with a different spawnProjectile. All other fields are
   * preserved.
   */
  public ProjectileStats withSpawnProjectile(ProjectileStats newSpawnProjectile) {
    return copyBuilder().spawnProjectile(newSpawnProjectile).build();
  }

  private ProjectileStats.ProjectileStatsBuilder copyBuilder() {
    return ProjectileStats.builder()
        .name(name)
        .damage(damage)
        .speed(speed)
        .rawSpeed(rawSpeed)
        .gravity(gravity)
        .rarity(rarity)
        .damageScalingMode(damageScalingMode)
        .homingTime(homingTime)
        .homingMinDistance(homingMinDistance)
        .radius(radius)
        .radiusY(radiusY)
        .homing(homing)
        .hitEffects(hitEffects)
        .chainedHitRadius(chainedHitRadius)
        .chainedHitCount(chainedHitCount)
        .aoeToAir(aoeToAir)
        .aoeToGround(aoeToGround)
        .projectileRange(projectileRange)
        .scatter(scatter)
        .returning(returning)
        .pingpongMovingShooter(pingpongMovingShooter)
        .spawnProjectile(spawnProjectile)
        .spawnCount(spawnCount)
        .spawnRadius(spawnRadius)
        .projectileRadius(projectileRadius)
        .minDistance(minDistance)
        .pushback(pushback)
        .pushbackAll(pushbackAll)
        .checkCollisions(checkCollisions)
        .crownTowerDamagePercent(crownTowerDamagePercent)
        .spawnAreaEffect(spawnAreaEffect)
        .spawnCharacterName(spawnCharacterName)
        .spawnCharacter(spawnCharacter)
        .spawnCharacterCount(spawnCharacterCount)
        .spawnDeployTime(spawnDeployTime);
  }
}
