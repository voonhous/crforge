package org.crforge.core.entity.effect.handler;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Processes a targeted area effect (Vines). Selects up to targetCount enemies with the highest
 * effective HP (current HP + shield) within radius, applying effects with staggered delays. After
 * all targets are selected, applies DOT damage ticks via the buff's hitFrequency.
 */
public class TargetedEffectHandler {

  private final AreaEffectContext ctx;
  private final GameState gameState;

  public TargetedEffectHandler(AreaEffectContext ctx) {
    this.ctx = ctx;
    this.gameState = ctx.getGameState();
  }

  public void process(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();

    // Phase 1: Target selection with initial delay + staggered per-target delays
    if (effect.getNextTargetSelectionIndex() < stats.getTargetDelays().size()) {
      effect.setTargetSelectionAccumulator(effect.getTargetSelectionAccumulator() + deltaTime);
      float acc = effect.getTargetSelectionAccumulator();

      while (effect.getNextTargetSelectionIndex() < stats.getTargetDelays().size()) {
        float threshold =
            stats.getInitialDelay()
                + stats.getTargetDelays().get(effect.getNextTargetSelectionIndex());
        if (acc < threshold) {
          break;
        }

        // Select the next highest-HP target not yet selected
        Entity bestTarget = findHighestHpTarget(effect);
        if (bestTarget != null) {
          effect.getHitEntityIds().add(bestTarget.getId());

          // Apply freeze buff
          ctx.applyBuff(effect, bestTarget);

          // Air-to-ground conversion
          if (stats.isAirToGround()
              && bestTarget instanceof Troop troop
              && troop.getMovementType() == MovementType.AIR) {
            // Check the base movement type (before grounding), not the override
            if (troop.getMovement() != null && troop.getMovement().getType() == MovementType.AIR) {
              troop.setGroundedTimer(stats.getAirToGroundDuration());
            }
          }

          // Mark DOT as active on first target selection
          if (!effect.isDotActive()) {
            effect.setDotActive(true);
          }
        }

        effect.setNextTargetSelectionIndex(effect.getNextTargetSelectionIndex() + 1);
      }
    }

    // Phase 2: DOT ticking
    if (effect.isDotActive()) {
      BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
      float hitFrequency =
          (buffDef != null && buffDef.getHitFrequency() > 0) ? buffDef.getHitFrequency() : 1.0f;

      effect.setDotTickAccumulator(effect.getDotTickAccumulator() + deltaTime);
      effect.setDotElapsedTime(effect.getDotElapsedTime() + deltaTime);

      while (effect.getDotTickAccumulator() >= hitFrequency) {
        effect.setDotTickAccumulator(effect.getDotTickAccumulator() - hitFrequency);

        // Apply DOT damage to all selected targets still alive
        for (long targetId : effect.getHitEntityIds()) {
          Entity target = gameState.getEntityById(targetId).orElse(null);
          if (target == null || !target.isAlive()) {
            continue;
          }

          int damage = effect.getScaledDotDamage();
          if (target instanceof Tower) {
            damage =
                effect.getScaledCrownTowerDotDamage() > 0
                    ? effect.getScaledCrownTowerDotDamage()
                    : damage;
          }
          if (damage > 0) {
            target.getHealth().takeDamage(damage);
          }
        }
      }

      // Stop DOT when buff duration is exceeded
      if (effect.getDotElapsedTime() >= stats.getBuffDuration()) {
        effect.setDotActive(false);
      }
    }
  }

  /**
   * Finds the enemy entity with the highest effective HP (current HP + shield HP) within the
   * effect's radius that has not already been selected. Excludes buildings (EntityType.BUILDING)
   * but includes towers.
   */
  private Entity findHighestHpTarget(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    Entity bestTarget = null;
    int bestEffectiveHp = -1;

    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      // Exclude deployed buildings (spawners, etc.) but include towers
      if (target.getEntityType() == EntityType.BUILDING) {
        continue;
      }
      if (effect.getHitEntityIds().contains(target.getId())) {
        continue;
      }

      // Check within radius (regardless of hitsGround/hitsAir since Vines has both false
      // but uses targeted selection instead)
      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      int effectiveHp = target.getHealth().getCurrent() + target.getHealth().getShield();
      if (effectiveHp > bestEffectiveHp) {
        bestEffectiveHp = effectiveHp;
        bestTarget = target;
      }
    }

    return bestTarget;
  }
}
