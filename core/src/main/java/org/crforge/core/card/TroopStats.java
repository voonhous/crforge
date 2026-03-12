package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;

/** Defines the base statistics for a unit (Troop or Building turret) spawned by a {@link Card}. */
@Getter
@Builder
public class TroopStats {

  public static final Float DEFAULT_DEPLOY_TIME = 1.0f;

  private final String name;
  @Builder.Default private final int health = 100;
  @Builder.Default private final int damage = 50;
  @Builder.Default private final float speed = 1.0f;
  @Builder.Default private final float mass = 1.0f;

  @Builder.Default private final float collisionRadius = 0.5f;
  @Builder.Default private final float visualRadius = 0.5f;

  @Builder.Default private final float range = 1.0f;
  @Builder.Default private final float sightRange = 5.5f;
  @Builder.Default private final float attackCooldown = 1.0f;
  @Builder.Default private final float loadTime = 0f;

  @Builder.Default
  private final boolean noPreload = false; // Sparky exception: does not enter preloaded

  @Builder.Default private final float aoeRadius = 0f;
  @Builder.Default private final MovementType movementType = MovementType.GROUND;
  @Builder.Default private final TargetType targetType = TargetType.ALL;
  @Builder.Default private final float deployTime = DEFAULT_DEPLOY_TIME;
  @Builder.Default private final List<EffectStats> hitEffects = new ArrayList<>();

  private final ProjectileStats projectile;

  // Building lifetime (seconds); 0 means no lifetime limit
  @Builder.Default private final float lifeTime = 0f;

  // Live spawn configuration (for spawner troops/buildings like Witch, Tombstone)
  private final LiveSpawnConfig liveSpawn;

  // Death mechanics
  @Builder.Default private final int deathDamage = 0;
  @Builder.Default private final float deathDamageRadius = 0f;
  @Builder.Default private final float deathPushback = 0f;
  @Builder.Default private final List<DeathSpawnEntry> deathSpawns = new ArrayList<>();

  // Shield
  @Builder.Default private final int shieldHitpoints = 0;

  // Combat modifiers
  @Builder.Default private final int multipleTargets = 0;
  @Builder.Default private final int multipleProjectiles = 0;
  @Builder.Default private final boolean selfAsAoeCenter = false;

  // Buff applied to target when dealing damage (e.g. EWiz stun, Mother Witch curse)
  private final EffectStats buffOnDamage;

  // Ability (Charge, Variable Damage, etc.)
  private final AbilityData ability;

  // Targeting and combat modifiers
  @Builder.Default private final boolean targetOnlyBuildings = false;
  @Builder.Default private final float minimumRange = 0f;
  @Builder.Default private final int crownTowerDamagePercent = 0;
  @Builder.Default private final boolean ignorePushback = false;

  // Kamikaze: unit dies after delivering its attack (e.g. Battle Ram)
  @Builder.Default private final boolean kamikaze = false;

  // River jump: unit can leap over the river instead of routing through bridges
  @Builder.Default private final boolean jumpEnabled = false;

  // Underground tunnel travel speed in tiles/sec (converted from spawnPathfindSpeed / 60)
  @Builder.Default private final float spawnPathfindSpeed = 0f;

  public boolean isRanged() {
    return range >= 2.0f;
  }
}
