package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;

/**
 * Applies continuous pull toward the effect center for Tornado-like effects. Runs every game tick
 * (not gated by hitSpeed). Pull speed is mass-dependent: lighter units are pulled faster.
 *
 * <p>Stunned/frozen entities skip pull (stagger effect). Buildings cannot be pulled.
 */
public class PullProcessor {

  // Entities closer than this to the center are not pulled (0.01 tiles, in game units)
  private static final float CENTER_EPSILON = 10f;

  private final AreaEffectContext ctx;

  public PullProcessor(AreaEffectContext ctx) {
    this.ctx = ctx;
  }

  public void applyPull(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();
    BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
    if (buffDef == null || buffDef.getAttractPercentage() <= 0) {
      return;
    }

    float attractPercentage = buffDef.getAttractPercentage();
    Team enemyTeam = effect.getTeam().opposite();
    int centerX = effect.getPosition().getX();
    int centerY = effect.getPosition().getY();

    for (Entity target : ctx.getGameState().getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      // Buildings cannot be pulled
      if (target.getMovementType() == MovementType.BUILDING) {
        continue;
      }
      if (!ctx.canHit(stats, target)) {
        continue;
      }

      long distanceSq = target.getPosition().distanceSquaredTo(centerX, centerY);
      long effectiveRadius = (long) stats.getRadius() + target.getCollisionRadius();
      if (!GameUnits.withinRadius(distanceSq, effectiveRadius)) {
        continue;
      }

      // Stunned/frozen entities skip pull (stagger effect)
      if (ctx.hasStunOrFreeze(target)) {
        continue;
      }

      float distance = (float) Math.sqrt(distanceSq);
      if (distance < CENTER_EPSILON) {
        continue; // Already at center
      }
      // Pull speed: attractPercentage / (30.0 * mass) tiles/sec, converted to game units/sec
      float mass = target.getMovement() != null ? target.getMovement().getMass() : 6.0f;
      if (mass <= 0) {
        mass = 6.0f; // Fallback to Knight mass
      }
      float pullSpeed = attractPercentage / (30.0f * mass) * GameUnits.UNITS_PER_TILE;

      // Direction toward center
      float dx = centerX - target.getPosition().getX();
      float dy = centerY - target.getPosition().getY();
      float ndx = dx / distance;
      float ndy = dy / distance;

      // Displacement this tick, capped to prevent overshooting center
      float displacement = Math.min(pullSpeed * deltaTime, distance);
      target.getPosition().move(ndx * displacement, ndy * displacement);
    }
  }
}
