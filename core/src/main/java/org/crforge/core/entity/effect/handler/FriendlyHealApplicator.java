package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.player.Team;

/**
 * Heals friendly units within the area effect radius. Heal amount is healPerSecond * buffDuration
 * for one-shot effects.
 */
public class FriendlyHealApplicator {

  private final AreaEffectContext ctx;

  FriendlyHealApplicator(AreaEffectContext ctx) {
    this.ctx = ctx;
  }

  public void applyHealToFriendlies(AreaEffect effect, BuffDefinition buffDef) {
    AreaEffectStats stats = effect.getStats();
    float duration = stats.getBuffDuration() > 0 ? stats.getBuffDuration() : 1.0f;
    int healAmount = Math.round(buffDef.getHealPerSecond() * duration);

    Team friendlyTeam = effect.getTeam();

    for (Entity target : ctx.getGameState().getAliveEntities()) {
      if (target.getTeam() != friendlyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      if (!ctx.canHit(stats, target)) {
        continue;
      }
      // Heal only targets troops, not buildings/towers
      if (target.getMovementType() == MovementType.BUILDING) {
        continue;
      }
      if (ctx.isInRadius(effect, target)) {
        target.getHealth().heal(healAmount);
      }
    }
  }
}
