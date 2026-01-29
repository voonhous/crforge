package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;

/**
 * Defines the base statistics for a unit (Troop or Building turret) spawned by a {@link Card}.
 */
@Getter
@Builder
public class TroopStats {

  public static final Float DEFAULT_DEPLOY_TIME = 1.0f;

  private final String name;
  @Builder.Default
  private final int health = 100;
  @Builder.Default
  private final int damage = 50;
  @Builder.Default
  private final float speed = 1.0f;
  @Builder.Default
  private final float mass = 1.0f;

  @Builder.Default
  private final float collisionRadius = 0.5f;
  @Builder.Default
  private final float visualRadius = 0.5f;

  @Builder.Default
  private final float range = 1.0f;
  @Builder.Default
  private final float sightRange = 5.5f;
  @Builder.Default
  private final float attackCooldown = 1.0f;
  @Builder.Default
  private final float firstAttackCooldown = 0f;
  @Builder.Default
  private final float aoeRadius = 0f;
  @Builder.Default
  private final MovementType movementType = MovementType.GROUND;
  @Builder.Default
  private final TargetType targetType = TargetType.ALL;
  @Builder.Default
  private final float deployTime = DEFAULT_DEPLOY_TIME;
  @Builder.Default
  private final float offsetX = 0f;
  @Builder.Default
  private final float offsetY = 0f;
  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();

  // Spawn Mechanics (Enter the Arena effects)
  @Builder.Default
  private final int spawnDamage = 0;
  @Builder.Default
  private final float spawnRadius = 0f;
  @Builder.Default
  private final List<EffectStats> spawnEffects = new ArrayList<>();

  private final ProjectileStats projectile;

  public boolean isRanged() {
    return range >= 2.0f;
  }

  public boolean hasSpawnEffect() {
    return spawnDamage > 0 || !spawnEffects.isEmpty();
  }
}
