package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.player.Team;

/**
 * Applies a buff to friendly units within the area effect radius. Used for positive buffs like Rage
 * that should affect same-team entities.
 */
public class FriendlyBuffApplicator {

  private final AreaEffectContext ctx;

  FriendlyBuffApplicator(AreaEffectContext ctx) {
    this.ctx = ctx;
  }

  public void applyBuffToFriendlies(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
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
      if (ctx.isInRadius(effect, target)) {
        ctx.applyBuff(effect, target);
      }
    }
  }
}
