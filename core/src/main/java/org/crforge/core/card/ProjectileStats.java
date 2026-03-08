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
  @Builder.Default
  private final int damage = 0;
  @Builder.Default
  private final float speed = 15.0f;
  @Builder.Default
  private final float radius = 0f; // AOE radius
  @Builder.Default
  private final boolean homing = true;
  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();

  // Chain lightning: after primary hit, chain to N more targets within radius
  @Builder.Default
  private final float chainedHitRadius = 0f;
  @Builder.Default
  private final int chainedHitCount = 0;

  // AOE targeting flags
  @Builder.Default
  private final boolean aoeToAir = false;
  @Builder.Default
  private final boolean aoeToGround = false;

  // Non-homing scatter projectiles expire at this range
  @Builder.Default
  private final float projectileRange = 0f;

  // Spawn sub-projectile on impact
  private final ProjectileStats spawnProjectile;
  @Builder.Default
  private final int spawnCount = 0;
  @Builder.Default
  private final float spawnRadius = 0f;

  /**
   * Returns a copy of this ProjectileStats with a different damage value.
   * All other fields are preserved.
   */
  public ProjectileStats withDamage(int newDamage) {
    return ProjectileStats.builder()
        .name(name)
        .damage(newDamage)
        .speed(speed)
        .radius(radius)
        .homing(homing)
        .hitEffects(hitEffects)
        .chainedHitRadius(chainedHitRadius)
        .chainedHitCount(chainedHitCount)
        .aoeToAir(aoeToAir)
        .aoeToGround(aoeToGround)
        .projectileRange(projectileRange)
        .spawnProjectile(spawnProjectile)
        .spawnCount(spawnCount)
        .spawnRadius(spawnRadius)
        .build();
  }
}
