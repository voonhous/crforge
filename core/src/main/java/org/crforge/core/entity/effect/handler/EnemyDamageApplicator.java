package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.combat.DamageUtil;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;

/**
 * Applies damage and buffs to enemy entities within the area effect radius. Handles building damage
 * bonuses, crown tower damage reduction, and hidden building bypass.
 */
public class EnemyDamageApplicator {

  private final AreaEffectContext ctx;

  EnemyDamageApplicator(AreaEffectContext ctx) {
    this.ctx = ctx;
  }

  public void applyDamageToEnemies(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();
    int damage = effect.getEffectiveDamage();
    int ctdp = effect.getEffectiveCrownTowerDamagePercent();

    // Determine if this effect can bypass hidden buildings (Earthquake, Freeze)
    boolean bypassesHidden = stats.isAffectsHidden();
    if (!bypassesHidden) {
      // Fallback for legacy data that may not have the affectsHidden field set
      StatusEffectType buffType =
          stats.getBuff() != null ? StatusEffectType.fromBuffName(stats.getBuff()) : null;
      bypassesHidden =
          buffType == StatusEffectType.EARTHQUAKE || buffType == StatusEffectType.FREEZE;
    }

    for (Entity target : ctx.getGameState().getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      // Hidden buildings are skipped by most effects, but Earthquake and Freeze bypass
      if (!target.isTargetable()) {
        if (bypassesHidden && target instanceof Building building && building.isHidden()) {
          // Allow this effect to hit the hidden building
        } else {
          continue;
        }
      }
      if (!ctx.canHit(stats, target)) {
        continue;
      }
      // Skip deployed buildings when ignoreBuildings is set (Royal Delivery).
      // Crown towers (EntityType.TOWER) are not affected by this flag.
      if (stats.isIgnoreBuildings() && target.getEntityType() == EntityType.BUILDING) {
        continue;
      }

      if (!ctx.isInRadius(effect, target)) {
        continue;
      }

      // Apply buff first (before damage, consistent with CombatSystem convention)
      ctx.applyBuff(effect, target);

      // Apply damage with building bonus and crown tower modifiers
      if (damage > 0) {
        int effectiveDamage = damage;

        // Apply building damage percent bonus first (e.g. Earthquake deals 4.5x to buildings)
        // Towers are buildings in Clash Royale, so BUILDING and TOWER both qualify
        if (effect.getBuildingDamagePercent() > 0
            && (target.getEntityType() == EntityType.BUILDING
                || target.getEntityType() == EntityType.TOWER)) {
          effectiveDamage = effectiveDamage * (100 + effect.getBuildingDamagePercent()) / 100;
        }

        // Absolute crown tower damage for ticking AEOs (GoblinCurse)
        if (target instanceof Tower && effect.getScaledCrownTowerDotDamage() > 0) {
          effectiveDamage = effect.getScaledCrownTowerDotDamage();
        } else {
          // Then apply crown tower damage reduction
          effectiveDamage = DamageUtil.adjustForCrownTower(effectiveDamage, target, ctdp);
        }

        target.getHealth().takeDamage(effectiveDamage);
      }

      // Apply knockback if the effect has pushback (e.g. GoblinDrillDamage)
      if (stats.getPushback() > 0) {
        ctx.applyAreaEffectKnockback(target, centerX, centerY, stats.getPushback());
      }
    }
  }
}
