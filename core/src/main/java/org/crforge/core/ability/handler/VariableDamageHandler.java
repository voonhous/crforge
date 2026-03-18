package org.crforge.core.ability.handler;

import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.ability.VariableDamageStage;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;

/** Handles the VARIABLE_DAMAGE ability (Inferno Tower, Inferno Dragon). */
public class VariableDamageHandler implements AbilityHandler {

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    Combat combat;
    if (entity instanceof Troop troop) {
      combat = troop.getCombat();
    } else if (entity instanceof Building building) {
      combat = building.getCombat();
    } else {
      return;
    }

    updateVariableDamage(ability, combat, deltaTime);
  }

  private void updateVariableDamage(AbilityComponent ability, Combat combat, float deltaTime) {
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

    // Target went out of attack range (entity is chasing, not fighting) -- reset to stage 0.
    // In Clash Royale, the inferno beam resets whenever the unit has to move to reach its target.
    // We detect this as: target was previously locked (actively fighting) but is no longer locked.
    if (ability.isWasTargetLocked() && !combat.isTargetLocked()) {
      ability.setCurrentStage(0);
      ability.setStageTimer(0f);
      combat.setDamageOverride(ability.getCurrentStageDamage());
    }
    ability.setWasTargetLocked(combat.isTargetLocked());

    // Accumulate time and advance stages
    List<VariableDamageStage> stages = ((VariableDamageAbility) ability.getData()).stages();
    if (stages.isEmpty()) {
      return;
    }

    ability.setStageTimer(ability.getStageTimer() + deltaTime);

    // Check if we should advance to the next stage
    int stage = ability.getCurrentStage();
    if (stage < stages.size() - 1) {
      VariableDamageStage nextStage = stages.get(stage + 1);
      if (nextStage.durationSeconds() > 0
          && ability.getStageTimer() >= nextStage.durationSeconds()) {
        ability.setStageTimer(ability.getStageTimer() - nextStage.durationSeconds());
        ability.setCurrentStage(stage + 1);
      }
    }

    // Apply current stage damage to combat component
    combat.setDamageOverride(ability.getCurrentStageDamage());
  }

  /**
   * Resets variable damage (inferno) back to stage 0. Called when the troop is stunned or frozen --
   * a core counter-play mechanic (see src_og.js line 2279).
   */
  public static void resetVariableDamage(Troop troop) {
    resetVariableDamage(troop.getAbility(), troop.getCombat());
  }

  /**
   * Resets variable damage (inferno) back to stage 0 using raw components. Works for both Troops
   * and Buildings (e.g. Inferno Tower).
   */
  public static void resetVariableDamage(AbilityComponent ability, Combat combat) {
    if (ability != null && ability.getData() instanceof VariableDamageAbility) {
      ability.setCurrentStage(0);
      ability.setStageTimer(0f);
      ability.setLastTargetId(-1);
      if (combat != null) {
        combat.setDamageOverride(ability.getCurrentStageDamage());
      }
    }
  }
}
