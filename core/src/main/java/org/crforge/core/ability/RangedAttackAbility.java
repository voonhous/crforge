package org.crforge.core.ability;

import org.crforge.core.card.ProjectileStats;
import org.crforge.core.entity.base.TargetType;

/**
 * RANGED_ATTACK ability data (Goblin Machine). An independent secondary ranged attack that fires
 * projectiles at targets within [minimumRange, range], operating completely independently from the
 * primary CombatSystem attack. This enables dual-attack units (e.g. melee punch + ranged rocket).
 *
 * @param projectile the projectile stats to fire (resolved from projectiles.json)
 * @param range maximum range for the ranged attack (edge-to-edge, in tiles)
 * @param minimumRange minimum range -- targets closer than this are ignored (dead zone)
 * @param loadTime wind-up time before the attack can fire (seconds)
 * @param attackDelay delay between wind-up completion and projectile firing (seconds)
 * @param attackCooldown cooldown between attack cycles (seconds)
 * @param targetType targeting filter for this attack (e.g. ALL targets air+ground, GROUND only)
 */
public record RangedAttackAbility(
    ProjectileStats projectile,
    float range,
    float minimumRange,
    float loadTime,
    float attackDelay,
    float attackCooldown,
    TargetType targetType)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.RANGED_ATTACK;
  }
}
