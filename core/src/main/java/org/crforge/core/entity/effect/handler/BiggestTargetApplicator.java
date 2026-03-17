package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.combat.DamageUtil;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.player.Team;

/**
 * Applies damage and buff to the single highest-HP enemy in range that has not been hit yet. Used
 * by Lightning to strike up to 3 different targets across its ticking lifetime.
 */
public class BiggestTargetApplicator {

  private final AreaEffectContext ctx;

  BiggestTargetApplicator(AreaEffectContext ctx) {
    this.ctx = ctx;
  }

  public void applyToBiggestTarget(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team enemyTeam = effect.getTeam().opposite();
    int damage = effect.getEffectiveDamage();
    int ctdp = effect.getEffectiveCrownTowerDamagePercent();

    Entity bestTarget = null;
    int bestEffectiveHp = -1;

    for (Entity target : ctx.getGameState().getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      if (!ctx.canHit(stats, target)) {
        continue;
      }
      if (effect.getHitEntityIds().contains(target.getId())) {
        continue;
      }
      if (!ctx.isInRadius(effect, target)) {
        continue;
      }

      // Effective HP = current HP + shield (higher total gets struck first)
      int effectiveHp = target.getHealth().getCurrent() + target.getHealth().getShield();
      if (effectiveHp > bestEffectiveHp) {
        bestEffectiveHp = effectiveHp;
        bestTarget = target;
      }
    }

    if (bestTarget == null) {
      return;
    }

    effect.getHitEntityIds().add(bestTarget.getId());
    ctx.applyBuff(effect, bestTarget);

    if (damage > 0) {
      int effectiveDamage = damage;

      // Apply building damage percent bonus first
      if (effect.getBuildingDamagePercent() > 0
          && (bestTarget.getEntityType() == EntityType.BUILDING
              || bestTarget.getEntityType() == EntityType.TOWER)) {
        effectiveDamage = effectiveDamage * (100 + effect.getBuildingDamagePercent()) / 100;
      }

      // Then apply crown tower damage reduction
      effectiveDamage = DamageUtil.adjustForCrownTower(effectiveDamage, bestTarget, ctdp);

      bestTarget.getHealth().takeDamage(effectiveDamage);
    }
  }
}
