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
  @Builder.Default private final float speed = 15.0f;
  @Builder.Default private final float radius = 0f; // AOE radius
  @Builder.Default private final boolean homing = true;
  @Builder.Default private final List<EffectStats> hitEffects = new ArrayList<>();

  // Chain lightning: after primary hit, chain to N more targets within radius
  @Builder.Default private final float chainedHitRadius = 0f;
  @Builder.Default private final int chainedHitCount = 0;

  // AOE targeting flags
  @Builder.Default private final boolean aoeToAir = false;
  @Builder.Default private final boolean aoeToGround = false;

  // Non-homing scatter projectiles expire at this range
  @Builder.Default private final float projectileRange = 0f;

  // Scatter pattern type (e.g. "Line" for Hunter shotgun fan)
  private final String scatter;

  // Returning: piercing projectile reverses at max range and travels back (e.g. Executioner axe)
  @Builder.Default private final boolean returning = false;

  // Pingpong moving shooter: thrower keeps moving while returning projectile is in flight
  @Builder.Default private final float pingpongMovingShooter = 0f;

  // Spawn sub-projectile on impact
  private final ProjectileStats spawnProjectile;
  @Builder.Default private final int spawnCount = 0;
  @Builder.Default private final float spawnRadius = 0f;

  // Knockback on hit (in tile units)
  @Builder.Default private final float pushback = 0f;
  @Builder.Default private final boolean pushbackAll = false;

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

  // Volley: fire multiple copies with staggered delay (e.g. Arrows fires 3 volleys)
  @Builder.Default private final int volleyCount = 1;
  @Builder.Default private final int volleyFrameDelay = 0;

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

  private ProjectileStats.ProjectileStatsBuilder copyBuilder() {
    return ProjectileStats.builder()
        .name(name)
        .damage(damage)
        .speed(speed)
        .radius(radius)
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
        .pushback(pushback)
        .pushbackAll(pushbackAll)
        .checkCollisions(checkCollisions)
        .crownTowerDamagePercent(crownTowerDamagePercent)
        .spawnAreaEffect(spawnAreaEffect)
        .spawnCharacterName(spawnCharacterName)
        .spawnCharacter(spawnCharacter)
        .spawnCharacterCount(spawnCharacterCount)
        .volleyCount(volleyCount)
        .volleyFrameDelay(volleyFrameDelay);
  }
}
