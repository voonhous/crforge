package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.effect.AreaEffect;

/**
 * Routes {@code applyToTargets} to the correct applicator based on the effect's stats flags. This
 * avoids duplicating the Axis B dispatch between OneShot and Ticking handlers.
 */
public class TargetApplicationRouter {

  private final CloneApplicator cloneApplicator;
  private final BiggestTargetApplicator biggestTargetApplicator;
  private final EnemyDamageApplicator enemyDamageApplicator;
  private final FriendlyHealApplicator friendlyHealApplicator;
  private final FriendlyBuffApplicator friendlyBuffApplicator;

  public TargetApplicationRouter(AreaEffectContext ctx) {
    GameState gameState = ctx.getGameState();
    this.cloneApplicator = new CloneApplicator(ctx, gameState);
    this.biggestTargetApplicator = new BiggestTargetApplicator(ctx);
    this.enemyDamageApplicator = new EnemyDamageApplicator(ctx);
    this.friendlyHealApplicator = new FriendlyHealApplicator(ctx);
    this.friendlyBuffApplicator = new FriendlyBuffApplicator(ctx);
  }

  public void applyToTargets(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();

    // Clone spell: duplicate friendly troops in the area
    if (stats.isClone()) {
      cloneApplicator.applyClone(effect);
      return;
    }

    // Use the onlyEnemies field from the data to determine targeting
    if (stats.isOnlyEnemies()) {
      // Enemy targeting
      if (stats.isHitBiggestTargets()) {
        biggestTargetApplicator.applyToBiggestTarget(effect);
      } else {
        enemyDamageApplicator.applyDamageToEnemies(effect);
      }
    } else {
      // Friendly targeting (heals, buffs like Rage)
      BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
      if (buffDef != null && buffDef.getHealPerSecond() > 0) {
        friendlyHealApplicator.applyHealToFriendlies(effect, buffDef);
      } else {
        friendlyBuffApplicator.applyBuffToFriendlies(effect);
      }
    }
  }
}
