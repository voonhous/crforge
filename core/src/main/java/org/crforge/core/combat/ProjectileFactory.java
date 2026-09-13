package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.util.GameUnits;

/**
 * Creates projectiles for ranged attacks and scatter (shotgun) patterns. Handles stat resolution,
 * piercing/returning configuration, and advanced projectile features.
 */
class ProjectileFactory {

  // Projectile ranges below one tile are sentinel values (e.g. 0.001 tiles = 1 game unit for
  // WallbreakerProjectile), not real piercing travel distances
  private static final int MIN_PIERCING_RANGE = GameUnits.UNITS_PER_TILE;

  // Scatter pellets spawn this far ahead of the attacker (0.65 tiles)
  private static final int SCATTER_START_OFFSET = 650;

  // Scatter pellet range when the projectile has no projectileRange (6.5 tiles)
  private static final int DEFAULT_SCATTER_RANGE = 6500;

  private final GameState gameState;

  ProjectileFactory(GameState gameState) {
    this.gameState = gameState;
  }

  /**
   * Creates a projectile for a ranged attack, resolving stats from the combat component's
   * ProjectileStats with fallback to combat-level values.
   */
  Projectile createAttackProjectile(Entity attacker, Entity target, int damage, Combat combat) {
    ProjectileStats stats = combat.getProjectileStats();

    float speed = (stats != null) ? stats.getSpeed() : 0;
    int aoeRadius = (stats != null) ? stats.getRadius() : combat.getAoeRadius();
    // For piercing projectiles, use projectileRadius for hit detection if available
    // (e.g. Bowler projectileRadius=1000 vs AOE radius=1800 game units)
    if (stats != null
        && stats.getProjectileRadius() > 0
        && !stats.isHoming()
        && stats.getProjectileRange() >= MIN_PIERCING_RANGE) {
      aoeRadius = stats.getProjectileRadius();
    }
    List<EffectStats> effects =
        new ArrayList<>((stats != null) ? stats.getHitEffects() : combat.getHitEffects());

    // Merge buffOnDamage as a post-damage effect if no projectile-level post-damage effect exists
    boolean hasPostDamageEffect = false;
    for (EffectStats e : effects) {
      if (e.isApplyAfterDamage()) {
        hasPostDamageEffect = true;
        break;
      }
    }
    if (!hasPostDamageEffect && combat.getBuffOnDamage() != null) {
      EffectStats bod = combat.getBuffOnDamage();
      effects.add(
          EffectStats.builder()
              .type(bod.getType())
              .duration(bod.getDuration())
              .buffName(bod.getBuffName())
              .applyAfterDamage(true)
              .build());
    }

    Projectile projectile =
        new Projectile(
            attacker,
            target,
            damage,
            aoeRadius,
            speed,
            effects,
            combat.getCrownTowerDamagePercent());

    // Wire advanced projectile features from stats
    if (stats != null) {
      projectile.setChainedHitRadius(stats.getChainedHitRadius());
      projectile.setChainedHitCount(stats.getChainedHitCount());
      projectile.setSpawnProjectile(stats.getSpawnProjectile());

      // Propagate attacker level for sub-projectile damage scaling (e.g. Firecracker shrapnel)
      if (stats.getSpawnProjectile() != null && projectile.getSource() != null) {
        projectile.setSpellLevel(projectile.getSource().getLevel());
      }

      projectile.setPushback(stats.getPushback());
      projectile.setPushbackAll(stats.isPushbackAll());
      projectile.setSpawnAreaEffect(stats.getSpawnAreaEffect());

      // Returning projectiles (e.g. Executioner axe) are piercing: they travel out, reverse,
      // and return to the source. Configure piercing + returning.
      if (stats.isReturning() && stats.getProjectileRange() >= MIN_PIERCING_RANGE) {
        float dx = target.getPosition().getX() - attacker.getPosition().getX();
        float dy = target.getPosition().getY() - attacker.getPosition().getY();
        float dist = attacker.getPosition().distance(target.getPosition());
        float dirX = dist > 0f ? dx / dist : 0f;
        float dirY = dist > 0f ? dy / dist : 1f;
        projectile.configurePiercing(
            dirX, dirY, stats.getProjectileRange(), stats.isAoeToGround(), stats.isAoeToAir());
        projectile.configureReturning(attacker);
      } else if (!stats.isHoming() && stats.getProjectileRange() >= MIN_PIERCING_RANGE) {
        // Non-homing projectiles with meaningful projectileRange travel in a line,
        // hitting all entities along their path (e.g. Bowler boulder, Magic Archer arrow).
        // Threshold of one tile excludes sentinel values like 1 unit (WallbreakerProjectile).
        float dx = target.getPosition().getX() - attacker.getPosition().getX();
        float dy = target.getPosition().getY() - attacker.getPosition().getY();
        float dist = attacker.getPosition().distance(target.getPosition());
        float dirX = dist > 0f ? dx / dist : 0f;
        float dirY = dist > 0f ? dy / dist : 1f;
        projectile.configurePiercing(
            dirX, dirY, stats.getProjectileRange(), stats.isAoeToGround(), stats.isAoeToAir());
      } else if (!stats.isHoming()) {
        // Non-homing projectiles fly to the target's position at fire time
        projectile.setHoming(false);
      }
    }

    return projectile;
  }

  /**
   * Fires scatter projectiles in a fan pattern (Hunter shotgun). Each pellet is a piercing
   * projectile that travels in a fixed direction for the projectile's range.
   */
  void fireScatterProjectiles(Entity attacker, Entity target, int damage, Combat combat) {
    ProjectileStats stats = combat.getProjectileStats();
    int count = combat.getMultipleProjectiles();

    int attackerX = attacker.getPosition().getX();
    int attackerY = attacker.getPosition().getY();
    int targetX = target.getPosition().getX();
    int targetY = target.getPosition().getY();

    // Base angle toward the target
    float baseAngle = (float) Math.atan2(targetY - attackerY, targetX - attackerX);

    // Spread angle between pellets (10 degrees, matching JS reference)
    float spreadDegrees = 10f;
    float spreadRadians = (float) Math.toRadians(spreadDegrees);

    // Start position: 0.65 tiles ahead of attacker in the base direction (rounded to game units)
    int startX = attackerX + GameUnits.round(SCATTER_START_OFFSET * Math.cos(baseAngle));
    int startY = attackerY + GameUnits.round(SCATTER_START_OFFSET * Math.sin(baseAngle));
    int range = stats.getProjectileRange() > 0 ? stats.getProjectileRange() : DEFAULT_SCATTER_RANGE;

    List<EffectStats> effects = new ArrayList<>(stats.getHitEffects());

    for (int i = 0; i < count; i++) {
      // Offset angle: centered around base angle
      float offsetAngle = (i - count / 2.0f) * spreadRadians;
      float pelletAngle = baseAngle + offsetAngle;

      // Direction for this pellet
      float dirX = (float) Math.cos(pelletAngle);
      float dirY = (float) Math.sin(pelletAngle);

      // End position: range game units from start in the pellet direction
      int endX = startX + GameUnits.round(range * (double) dirX);
      int endY = startY + GameUnits.round(range * (double) dirY);

      Projectile pellet =
          new Projectile(
              attacker.getTeam(),
              startX,
              startY,
              endX,
              endY,
              damage,
              stats.getRadius(),
              stats.getSpeed(),
              effects,
              combat.getCrownTowerDamagePercent());

      // Configure as piercing so pellets travel through enemies
      pellet.configurePiercing(dirX, dirY, range, stats.isAoeToGround(), stats.isAoeToAir());
      pellet.setCheckCollisions(stats.isCheckCollisions());

      gameState.spawnProjectile(pellet);
    }
  }
}
