package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.RangedAttackAbility;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;

/**
 * Updates the independent ranged attack state machine. The ranged attack operates completely
 * independently from the primary CombatSystem attack, enabling dual-attack behavior (e.g. Goblin
 * Machine's melee punch + rocket).
 *
 * <p>State transitions: IDLE -> WINDING_UP (loadTime) -> ATTACK_DELAY (attackDelay) -> fire
 * projectile -> COOLDOWN (attackCooldown) -> IDLE
 */
public class RangedAttackHandler implements AbilityHandler {

  private final GameState gameState;

  public RangedAttackHandler(GameState gameState) {
    this.gameState = gameState;
  }

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    RangedAttackAbility data = (RangedAttackAbility) ability.getData();

    // Stun/freeze disrupts the ranged attack -- reset to IDLE
    if (troop.getCombat() != null && troop.getCombat().isCombatDisabled()) {
      ability.setRangedAttackState(AbilityComponent.RangedAttackState.IDLE);
      ability.setRangedAttackTimer(0f);
      ability.setRangedAttackTargetId(-1);
      return;
    }

    switch (ability.getRangedAttackState()) {
      case IDLE -> {
        Entity target = findRangedAttackTarget(troop, data);
        if (target != null) {
          ability.setRangedAttackState(AbilityComponent.RangedAttackState.WINDING_UP);
          ability.setRangedAttackTimer(data.loadTime());
          ability.setRangedAttackTargetId(target.getId());
        }
      }
      case WINDING_UP -> {
        ability.setRangedAttackTimer(ability.getRangedAttackTimer() - deltaTime);
        // Validate target is still alive
        Entity target = findEntityById(ability.getRangedAttackTargetId());
        if (target == null || !target.isAlive()) {
          ability.setRangedAttackState(AbilityComponent.RangedAttackState.IDLE);
          ability.setRangedAttackTimer(0f);
          ability.setRangedAttackTargetId(-1);
          return;
        }
        if (ability.getRangedAttackTimer() <= 0) {
          ability.setRangedAttackState(AbilityComponent.RangedAttackState.ATTACK_DELAY);
          ability.setRangedAttackTimer(data.attackDelay());
        }
      }
      case ATTACK_DELAY -> {
        ability.setRangedAttackTimer(ability.getRangedAttackTimer() - deltaTime);
        if (ability.getRangedAttackTimer() <= 0) {
          Entity target = findEntityById(ability.getRangedAttackTargetId());
          if (target != null && target.isAlive()) {
            fireRangedAttackProjectile(troop, target, ability, data);
          }
          ability.setRangedAttackState(AbilityComponent.RangedAttackState.COOLDOWN);
          ability.setRangedAttackTimer(data.attackCooldown());
          ability.setRangedAttackTargetId(-1);
        }
      }
      case COOLDOWN -> {
        ability.setRangedAttackTimer(ability.getRangedAttackTimer() - deltaTime);
        if (ability.getRangedAttackTimer() <= 0) {
          ability.setRangedAttackState(AbilityComponent.RangedAttackState.IDLE);
          ability.setRangedAttackTimer(0f);
        }
      }
    }
  }

  /**
   * Finds the nearest enemy entity within the ranged attack's [minimumRange, range] window that
   * matches the ability's targetType.
   */
  private Entity findRangedAttackTarget(Troop source, RangedAttackAbility data) {
    Entity best = null;
    float bestDistSq = Float.MAX_VALUE;

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() == source.getTeam()) {
        continue;
      }
      if (!entity.isTargetable()) {
        continue;
      }
      // Skip invisible troops
      if (entity instanceof Troop t && t.isInvisible()) {
        continue;
      }
      // Check movement type compatibility with ability's target type
      if (!TargetingSystem.canTargetMovementType(data.targetType(), entity.getMovementType())) {
        continue;
      }

      // Edge-to-edge distance (include collision radii)
      float centerDist = source.getPosition().distanceTo(entity.getPosition());
      float edgeDist = centerDist - source.getCollisionRadius() - entity.getCollisionRadius();

      // Must be within [minimumRange, range]
      if (edgeDist < data.minimumRange() || edgeDist > data.range()) {
        continue;
      }

      float distSq = source.getPosition().distanceToSquared(entity.getPosition());
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        best = entity;
      }
    }
    return best;
  }

  /**
   * Fires the ranged attack projectile from the source toward the target. The projectile is
   * non-homing (fires at the target's position at fire time) and uses the scaled damage from the
   * ability component.
   */
  private void fireRangedAttackProjectile(
      Troop source, Entity target, AbilityComponent ability, RangedAttackAbility data) {
    ProjectileStats projStats = data.projectile();
    if (projStats == null) {
      return;
    }

    Projectile projectile =
        new Projectile(
            source,
            target,
            ability.getScaledRangedDamage(),
            projStats.getRadius(),
            projStats.getSpeed(),
            projStats.getHitEffects(),
            projStats.getCrownTowerDamagePercent());

    // Non-homing: rocket fires at target's position at the moment of firing
    if (!projStats.isHoming()) {
      projectile.setHoming(false);
    }

    gameState.spawnProjectile(projectile);
  }

  private Entity findEntityById(long id) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getId() == id) {
        return entity;
      }
    }
    return null;
  }
}
