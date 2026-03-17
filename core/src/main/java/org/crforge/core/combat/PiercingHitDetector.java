package org.crforge.core.combat;

import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Per-tick collision detection for piercing projectiles. Checks all alive enemy entities within the
 * projectile's AOE radius, applies damage and effects, and triggers directional knockback.
 */
class PiercingHitDetector {

  private final GameState gameState;
  private final AoeDamageService aoeDamageService;
  private final KnockbackHelper knockbackHelper;

  PiercingHitDetector(
      GameState gameState, AoeDamageService aoeDamageService, KnockbackHelper knockbackHelper) {
    this.gameState = gameState;
    this.aoeDamageService = aoeDamageService;
    this.knockbackHelper = knockbackHelper;
  }

  void processPiercingHits(Projectile projectile) {
    // Don't register hits until projectile has traveled its minimum distance
    if (projectile.getMinDistance() > 0
        && projectile.getDistanceTraveled() < projectile.getMinDistance()) {
      return;
    }

    Team enemyTeam = projectile.getTeam().opposite();
    float projX = projectile.getPosition().getX();
    float projY = projectile.getPosition().getY();
    float aoeRadius = projectile.getAoeRadius();
    int baseDamage = projectile.getDamage();
    int ctdp = projectile.getCrownTowerDamagePercent();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam || !entity.isTargetable()) {
        continue;
      }
      if (entity instanceof Troop t && t.isInvisible()) {
        continue;
      }
      if (projectile.hasHitEntity(entity.getId())) {
        continue;
      }
      if (!canPiercingHit(projectile, entity)) {
        continue;
      }

      float distSq = entity.getPosition().distanceToSquared(projX, projY);
      float effectiveRadius = aoeRadius + entity.getCollisionRadius();
      if (distSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      projectile.recordHitEntity(entity.getId());

      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, entity, ctdp);
      aoeDamageService.applyEffects(
          entity, aoeDamageService.filterEffects(projectile.getEffects(), false));
      aoeDamageService.dealDamage(entity, effectiveDamage);
      aoeDamageService.applyEffects(
          entity, aoeDamageService.filterEffects(projectile.getEffects(), true));

      // Apply directional knockback along the projectile's travel direction
      if (projectile.getPushback() > 0) {
        knockbackHelper.applyDirectionalKnockback(projectile, entity);
      }

      // checkCollisions: projectile stops on first hit (e.g. Hunter pellets)
      if (projectile.isCheckCollisions()) {
        projectile.deactivate();
        break;
      }
    }
  }

  /**
   * Returns true if the entity's movement type is compatible with the piercing projectile's
   * aoeToGround/aoeToAir targeting flags.
   */
  private boolean canPiercingHit(Projectile projectile, Entity entity) {
    MovementType mt = entity.getMovementType();
    if (mt == MovementType.AIR) {
      return projectile.isAoeToAir();
    }
    // GROUND and BUILDING are ground-level targets
    return projectile.isAoeToGround();
  }
}
