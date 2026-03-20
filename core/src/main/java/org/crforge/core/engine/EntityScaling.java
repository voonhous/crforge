package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.card.AttackSequenceHit;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.AttackStateMachine;
import org.crforge.core.component.Combat;

/**
 * Static utility methods shared across sub-factories for level scaling and component construction.
 */
class EntityScaling {

  private EntityScaling() {}

  /**
   * Builds the shared subset of Combat fields common to both troops and buildings. Callers can
   * chain additional builder calls for troop-specific fields before .build().
   */
  static Combat.CombatBuilder buildCombatComponent(
      TroopStats stats, int scaledDamage, float initialLoad) {
    return Combat.builder()
        .damage(scaledDamage)
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .loadTime(stats.getLoadTime())
        .attackState(AttackStateMachine.withLoad(initialLoad))
        .targetType(stats.getTargetType())
        .hitEffects(stats.getHitEffects())
        .projectileStats(stats.getProjectile())
        .targetOnlyBuildings(stats.isTargetOnlyBuildings())
        .targetOnlyTroops(stats.isTargetOnlyTroops())
        .ignoreTargetsWithBuff(stats.getIgnoreTargetsWithBuff())
        .minimumRange(stats.getMinimumRange())
        .crownTowerDamagePercent(stats.getCrownTowerDamagePercent())
        .selfAsAoeCenter(stats.isSelfAsAoeCenter())
        .kamikaze(stats.isKamikaze())
        .attackDashTime(stats.getAttackDashTime())
        .attackPushBack(stats.getAttackPushBack())
        .areaEffectOnHit(stats.getAreaEffectOnHit());
  }

  /** Scales the damage of a death spawn projectile by card level and preserves spawn character. */
  static ProjectileStats scaleDeathProjectile(ProjectileStats deathProj, int level) {
    if (deathProj == null) {
      return null;
    }
    ProjectileStats scaled =
        deathProj.withDamage(LevelScaling.scaleCard(deathProj.getDamage(), level));
    if (deathProj.getSpawnCharacter() != null) {
      scaled = scaled.withSpawnCharacter(deathProj.getSpawnCharacter());
    }
    return scaled;
  }

  /** Returns true if the unit has any death-triggered mechanics. */
  static boolean hasDeathMechanics(TroopStats stats) {
    return stats.getDeathDamage() > 0
        || !stats.getDeathSpawns().isEmpty()
        || stats.getDeathAreaEffect() != null
        || stats.getManaOnDeathForOpponent() > 0
        || stats.getDeathSpawnProjectile() != null;
  }

  /** Scales all hits in an attack sequence by card level. */
  static List<AttackSequenceHit> scaleAttackSequence(List<AttackSequenceHit> sequence, int level) {
    return sequence.stream()
        .map(hit -> new AttackSequenceHit(LevelScaling.scaleCard(hit.damage(), level)))
        .toList();
  }

  static float resolveInitialSpawnerTimer(org.crforge.core.card.LiveSpawnConfig ls) {
    return ls.spawnStartTime();
  }
}
