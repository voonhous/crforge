package org.crforge.core.effect;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/**
 * Handles the calculation of final attributes based on active status effects. Only touches the
 * STATUS_EFFECT modifier source, leaving ability sources untouched.
 */
public class StatusEffectSystem {

  public void update(GameState state, float deltaTime) {
    for (Entity entity : state.getEntities()) {
      processEntityEffects(entity, deltaTime);
    }
  }

  private void processEntityEffects(Entity entity, float deltaTime) {
    // Skip attached units -- their modifiers are propagated from the parent by AttachedUnitSystem
    if (entity instanceof Troop troop && troop.isAttached()) {
      return;
    }

    List<AppliedEffect> effects = entity.getAppliedEffects();
    if (effects.isEmpty()) {
      resetMultipliers(entity);
      return;
    }

    // Update durations and remove expired (skip countdown for controlledByParent buffs)
    effects.removeIf(
        effect -> {
          BuffDefinition buffDef = effect.getBuffDefinition();
          if (buffDef != null && buffDef.isControlledByParent()) {
            return false; // Duration managed by parent AreaEffect, not self-expired
          }
          effect.update(deltaTime);
          return effect.isExpired();
        });

    // Calculate multipliers
    float moveSpeedMult = 1.0f;
    float attackSpeedMult = 1.0f;
    boolean isStunned = false;

    for (AppliedEffect effect : effects) {
      // STUN/FREEZE always stun regardless of buff definition
      if (effect.getType() == StatusEffectType.STUN
          || effect.getType() == StatusEffectType.FREEZE) {
        isStunned = true;
        continue;
      }

      // Data-driven path: use BuffDefinition multipliers
      BuffDefinition buffDef = effect.getBuffDefinition();
      if (buffDef != null) {
        float speedMult = buffDef.computeSpeedMultiplier();
        float hitSpeedMult = buffDef.computeHitSpeedMultiplier();
        moveSpeedMult *= speedMult;
        attackSpeedMult *= hitSpeedMult;
        // If computed multiplier is zero or negative, treat as stun (e.g. Freeze buff)
        if (speedMult <= 0f || hitSpeedMult <= 0f) {
          isStunned = true;
        }
      }
    }

    // Apply to components -- only STATUS_EFFECT source
    Movement move = entity.getMovement();
    if (move != null) {
      move.setMovementDisabled(ModifierSource.STATUS_EFFECT, isStunned);
      move.setSpeedMultiplier(ModifierSource.STATUS_EFFECT, moveSpeedMult);
    }

    Combat combat = entity.getCombat();
    if (combat != null) {
      combat.setCombatDisabled(ModifierSource.STATUS_EFFECT, isStunned);
      combat.setAttackSpeedMultiplier(ModifierSource.STATUS_EFFECT, attackSpeedMult);
    }
  }

  private void resetMultipliers(Entity entity) {
    // Only clear STATUS_EFFECT source, leaving ability sources untouched
    Movement move = entity.getMovement();
    if (move != null) {
      move.clearModifiers(ModifierSource.STATUS_EFFECT);
    }
    Combat combat = entity.getCombat();
    if (combat != null) {
      combat.clearModifiers(ModifierSource.STATUS_EFFECT);
    }
  }
}
