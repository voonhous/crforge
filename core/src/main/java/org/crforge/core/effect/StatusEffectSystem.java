package org.crforge.core.effect;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Movement;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.Entity;

/**
 * Handles the calculation of final attributes based on active status effects.
 */
public class StatusEffectSystem {

  public void update(GameState state, float deltaTime) {
    for (Entity entity : state.getEntities()) {
      processEntityEffects(entity, deltaTime);
    }
  }

  private void processEntityEffects(Entity entity, float deltaTime) {
    List<AppliedEffect> effects = entity.getAppliedEffects();
    if (effects.isEmpty()) {
      resetMultipliers(entity);
      return;
    }

    // Update durations and remove expired
    effects.removeIf(effect -> {
      effect.update(deltaTime);
      return effect.isExpired();
    });

    // Calculate multipliers
    float moveSpeedMult = 1.0f;
    float attackSpeedMult = 1.0f;
    boolean isStunned = false;

    for (AppliedEffect effect : effects) {
      switch (effect.getType()) {
        case STUN:
        case FREEZE:
          isStunned = true;
          break;
        case SLOW:
          moveSpeedMult *= (1.0f - effect.getIntensity());
          attackSpeedMult *= (1.0f - effect.getIntensity());
          break;
        case RAGE:
          moveSpeedMult *= (1.0f + effect.getIntensity());
          attackSpeedMult *= (1.0f + effect.getIntensity());
          break;
      }
    }

    // Apply to components
    Movement move = entity.getMovement();
    if (move != null) {
      move.setMovementDisabled(isStunned);
      move.setSpeedMultiplier(moveSpeedMult);
    }

    Combat combat = entity.getCombat();
    if (combat != null) {
      combat.setCombatDisabled(isStunned);
      combat.setAttackSpeedMultiplier(attackSpeedMult);
    }
  }

  private void resetMultipliers(Entity entity) {
    Movement move = entity.getMovement();
    if (move != null) {
      move.setMovementDisabled(false);
      move.setSpeedMultiplier(1.0f);
    }
    Combat combat = entity.getCombat();
    if (combat != null) {
      combat.setCombatDisabled(false);
      combat.setAttackSpeedMultiplier(1.0f);
    }
  }
}