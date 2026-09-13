package org.crforge.core.combat;

import org.crforge.core.component.Movement;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;

/**
 * Applies knockback displacement from projectile impacts. Handles both radial knockback (AOE
 * projectiles) and directional knockback (piercing projectiles).
 */
class KnockbackHelper {

  // Knockback: 15 active frames at 30fps = 0.5s displacement duration
  static final float KNOCKBACK_DURATION = 0.5f;
  // Speed calculation base: distance / 1.0s (reference JS uses 30 frames for speed calc)
  static final float KNOCKBACK_MAX_TIME = 1.0f;

  private final GameState gameState;

  KnockbackHelper(GameState gameState) {
    this.gameState = gameState;
  }

  /**
   * Applies knockback displacement to entities hit by a projectile. AOE projectiles push all
   * enemies within radius; non-AOE pushes only the direct target. Buildings and entities with
   * ignorePushback are immune.
   */
  void applyKnockback(Projectile projectile) {
    int pushback = projectile.getPushback();
    // Determine impact center (game units)
    int centerX, centerY;
    if (projectile.isPositionTargeted()) {
      centerX = projectile.getTargetX();
      centerY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      centerX = projectile.getTarget().getPosition().getX();
      centerY = projectile.getTarget().getPosition().getY();
    } else {
      return;
    }

    int radius = projectile.getAoeRadius();
    Team projectileTeam = projectile.getTeam();
    Team enemyTeam = projectileTeam.opposite();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam || !entity.isTargetable()) {
        continue;
      }
      if (entity.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      Movement movement = entity.getMovement();
      if (movement == null || movement.isIgnorePushback()) {
        continue;
      }

      // AOE: check radius. Non-AOE (pushbackAll=false): only the direct target.
      if (radius > 0 || projectile.isPushbackAll()) {
        long distSq = entity.getPosition().distanceSquaredTo(centerX, centerY);
        long effectiveRadius = (long) radius + entity.getCollisionRadius();
        if (radius > 0 && !GameUnits.withinRadius(distSq, effectiveRadius)) {
          continue;
        }
      } else if (projectile.getTarget() != entity) {
        continue;
      }

      // Direction from impact center to entity
      float dx = entity.getPosition().getX() - centerX;
      float dy = entity.getPosition().getY() - centerY;
      float dist = entity.getPosition().distance(centerX, centerY);
      // Coincident with the impact center: push along +Y (integer positions: dist is 0 or >= 1)
      float dirX = dist > 0f ? dx / dist : 0f;
      float dirY = dist > 0f ? dy / dist : 1f;

      movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
    }
  }

  /**
   * Applies knockback to an entity in the piercing projectile's travel direction, rather than
   * radially from the impact point. Buildings and ignorePushback entities are immune.
   */
  void applyDirectionalKnockback(Projectile projectile, Entity entity) {
    if (entity.getMovementType() == MovementType.BUILDING) {
      return;
    }
    Movement movement = entity.getMovement();
    if (movement == null || movement.isIgnorePushback()) {
      return;
    }
    movement.startKnockback(
        projectile.getPiercingDirX(),
        projectile.getPiercingDirY(),
        projectile.getPushback(),
        KNOCKBACK_DURATION,
        KNOCKBACK_MAX_TIME);
  }
}
