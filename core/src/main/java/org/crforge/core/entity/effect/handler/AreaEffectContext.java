package org.crforge.core.entity.effect.handler;

import org.crforge.core.ability.handler.ChargeHandler;
import org.crforge.core.ability.handler.HidingHandler;
import org.crforge.core.ability.handler.VariableDamageHandler;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Movement;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;

/**
 * Shared utilities for area effect handlers. Holds a {@link GameState} reference and provides
 * common operations: radius checks, buff application (with stun/freeze reset logic), knockback, and
 * movement type filtering.
 */
public class AreaEffectContext {

  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  private final GameState gameState;

  public AreaEffectContext(GameState gameState) {
    this.gameState = gameState;
  }

  public GameState getGameState() {
    return gameState;
  }

  /** Returns true if the entity's movement type is compatible with the effect's targeting flags. */
  public boolean canHit(AreaEffectStats stats, Entity target) {
    MovementType mt = target.getMovementType();
    if (mt == MovementType.AIR) {
      return stats.isHitsAir();
    }
    // GROUND and BUILDING
    return stats.isHitsGround();
  }

  /** Returns true if the entity is within the effect's radius (accounting for collision radius). */
  public boolean isInRadius(AreaEffect effect, Entity target) {
    float distanceSq =
        target
            .getPosition()
            .distanceToSquared(effect.getPosition().getX(), effect.getPosition().getY());
    float effectiveRadius = effect.getStats().getRadius() + target.getCollisionRadius();
    return distanceSq <= effectiveRadius * effectiveRadius;
  }

  /** Returns true if the entity currently has a STUN or FREEZE effect active. */
  public boolean hasStunOrFreeze(Entity target) {
    for (AppliedEffect effect : target.getAppliedEffects()) {
      if (effect.getType() == StatusEffectType.STUN
          || effect.getType() == StatusEffectType.FREEZE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Applies all buff applications from the effect's stats to the target entity. Handles stun/freeze
   * reset logic (attack windup, charge, variable damage) and freeze-reveal for hidden buildings.
   */
  public void applyBuff(AreaEffect effect, Entity target) {
    AreaEffectStats stats = effect.getStats();

    boolean appliedStunOrFreeze = false;
    boolean appliedFreeze = false;

    for (BuffApplication buffApp : stats.getBuffApplications()) {
      StatusEffectType effectType = StatusEffectType.fromBuffName(buffApp.buffName());
      if (effectType == null) {
        continue;
      }

      // Buildings cannot be Cursed (GoblinCurse, VoodooCurse convention)
      if (effectType == StatusEffectType.CURSE
          && target.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      float duration = buffApp.duration() > 0 ? buffApp.duration() : 1.0f;
      if (stats.isCapBuffTimeToAreaEffectTime()) {
        duration = Math.min(duration, effect.getRemainingLifetime());
      }

      // Pass spawnSpecies for CURSE buffs so death-spawn triggers correctly
      if (effectType == StatusEffectType.CURSE && buffApp.curseSpawnStats() != null) {
        target.addEffect(
            new AppliedEffect(effectType, duration, buffApp.buffName(), buffApp.curseSpawnStats()));
      } else {
        target.addEffect(new AppliedEffect(effectType, duration, buffApp.buffName()));
      }

      if (effectType == StatusEffectType.STUN || effectType == StatusEffectType.FREEZE) {
        appliedStunOrFreeze = true;
      }
      if (effectType == StatusEffectType.FREEZE) {
        appliedFreeze = true;
      }
    }

    // Handle Stun/Freeze Reset Logic (Reset attack windup and charge ability)
    if (appliedStunOrFreeze) {
      Combat combat = target.getCombat();
      if (combat != null) {
        combat.resetAttackState();
      }
      // Reset charge ability state (Prince, Dark Prince, Battle Ram, Ram Rider)
      // Reset variable damage state (Inferno Dragon, Inferno Tower)
      if (target instanceof Troop troop) {
        ChargeHandler.consumeCharge(troop);
        VariableDamageHandler.resetVariableDamage(troop);
      } else if (target instanceof Building building && building.getAbility() != null) {
        VariableDamageHandler.resetVariableDamage(building.getAbility(), building.getCombat());
      }
    }

    // Freeze forces hidden buildings (Tesla) to reveal
    if (appliedFreeze && target instanceof Building building) {
      HidingHandler.forceRevealHiding(building);
    }
  }

  /**
   * Applies knockback to an entity hit by an area effect. Buildings and entities with
   * ignorePushback are immune.
   */
  public void applyAreaEffectKnockback(
      Entity target, float centerX, float centerY, float pushback) {
    Movement movement = target.getMovement();
    if (movement == null) {
      return;
    }
    if (movement.isBuilding() || movement.isIgnorePushback()) {
      return;
    }

    float dx = target.getPosition().getX() - centerX;
    float dy = target.getPosition().getY() - centerY;
    float dist = (float) Math.sqrt(dx * dx + dy * dy);
    float dirX = dist > 0.001f ? dx / dist : 0f;
    float dirY = dist > 0.001f ? dy / dist : 1f;

    movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
  }
}
