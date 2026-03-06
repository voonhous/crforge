package org.crforge.core.ability;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/**
 * Processes ability updates each tick. Handles charge buildup and
 * variable damage (inferno) stage escalation.
 *
 * <p>Should run before CombatSystem in the tick loop so damage modifications
 * take effect on the current tick's attacks.
 */
public class AbilitySystem {

  private final GameState gameState;

  public AbilitySystem(GameState gameState) {
    this.gameState = gameState;
  }

  public void update(float deltaTime) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (!(entity instanceof Troop troop)) {
        continue;
      }
      AbilityComponent ability = troop.getAbility();
      if (ability == null) {
        continue;
      }
      if (troop.isDeploying()) {
        continue;
      }

      switch (ability.getData().getType()) {
        case CHARGE -> updateCharge(troop, ability, deltaTime);
        case VARIABLE_DAMAGE -> updateVariableDamage(troop, ability, deltaTime);
        default -> {
          // DASH, HOOK, REFLECT handled in future tiers
        }
      }
    }
  }

  private void updateCharge(Troop troop, AbilityComponent ability, float deltaTime) {
    Combat combat = troop.getCombat();
    if (combat == null) {
      return;
    }

    boolean hasTarget = combat.hasTarget();
    boolean isAttacking = combat.isAttacking();

    // Charge only builds while moving toward target (has target, not yet attacking)
    if (hasTarget && !isAttacking) {
      ability.setChargeTimer(ability.getChargeTimer() + deltaTime);

      if (!ability.isCharged() && ability.getChargeTimer() >= ability.getChargeTime()) {
        ability.setCharged(true);
        // Apply speed multiplier
        troop.getMovement().setSpeedMultiplier(ability.getData().getSpeedMultiplier());
      }
    }

    // If no target, reset charge
    if (!hasTarget) {
      if (ability.isCharged() || ability.getChargeTimer() > 0) {
        ability.reset();
        troop.getMovement().setSpeedMultiplier(1.0f);
      }
    }
  }

  /**
   * Called by CombatSystem after a charge unit deals its attack.
   * Returns the damage to use for this attack (charge damage if charged).
   */
  public static int getChargeDamage(AbilityComponent ability, int baseDamage) {
    if (ability != null && ability.getData().getType() == AbilityType.CHARGE
        && ability.isCharged()) {
      return ability.getData().getChargeDamage();
    }
    return baseDamage;
  }

  /**
   * Called after a charge attack lands. Resets the charge state.
   */
  public static void consumeCharge(Troop troop) {
    AbilityComponent ability = troop.getAbility();
    if (ability != null && ability.getData().getType() == AbilityType.CHARGE) {
      ability.reset();
      troop.getMovement().setSpeedMultiplier(1.0f);
    }
  }

  private void updateVariableDamage(Troop troop, AbilityComponent ability, float deltaTime) {
    Combat combat = troop.getCombat();
    if (combat == null) {
      return;
    }

    // Check if target changed -- reset on retarget
    long currentTargetId = combat.hasTarget() ? combat.getCurrentTarget().getId() : -1;
    if (currentTargetId != ability.getLastTargetId()) {
      ability.setCurrentStage(0);
      ability.setStageTimer(0f);
      ability.setLastTargetId(currentTargetId);
    }

    // No target -- stay at stage 0
    if (!combat.hasTarget()) {
      return;
    }

    // Accumulate time and advance stages
    List<VariableDamageStage> stages = ability.getData().getStages();
    if (stages.isEmpty()) {
      return;
    }

    ability.setStageTimer(ability.getStageTimer() + deltaTime);

    // Check if we should advance to the next stage
    int stage = ability.getCurrentStage();
    if (stage < stages.size() - 1) {
      VariableDamageStage nextStage = stages.get(stage + 1);
      if (nextStage.durationSeconds() > 0 && ability.getStageTimer() >= nextStage.durationSeconds()) {
        ability.setStageTimer(ability.getStageTimer() - nextStage.durationSeconds());
        ability.setCurrentStage(stage + 1);
      }
    }

    // Apply current stage damage to combat component
    combat.setDamageOverride(ability.getCurrentStageDamage());
  }
}
