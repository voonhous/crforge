package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.HidingAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;

/**
 * Updates the hiding state machine for Tesla buildings.
 *
 * <p>State transitions:
 *
 * <ul>
 *   <li>HIDDEN: combat disabled, not targetable. If target detected -> REVEALING
 *   <li>REVEALING: targetable, combat still disabled. Timer counts down hideTime -> UP
 *   <li>UP: targetable, can attack. If no target for upTime -> HIDING
 *   <li>HIDING: targetable, combat NOT disabled (allows last-moment attack). If target appears ->
 *       cancel back to UP. Timer counts down hideTime -> HIDDEN
 * </ul>
 */
public class HidingHandler implements AbilityHandler {

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Building building)) {
      return;
    }

    HidingAbility data = (HidingAbility) ability.getData();
    Combat combat = building.getCombat();

    switch (ability.getHidingState()) {
      case HIDDEN -> {
        // Ensure combat is disabled while hidden
        if (combat != null) {
          combat.setCombatDisabled(ModifierSource.ABILITY_HIDING, true);
        }
        // Check if TargetingSystem found an enemy for us
        if (combat != null && combat.hasTarget()) {
          ability.setHidingState(AbilityComponent.HidingState.REVEALING);
          ability.setHidingTimer(data.hideTime());
        }
      }
      case REVEALING -> {
        // Still can't attack while rising from the ground
        if (combat != null) {
          combat.setCombatDisabled(ModifierSource.ABILITY_HIDING, true);
        }
        ability.setHidingTimer(ability.getHidingTimer() - deltaTime);
        if (ability.getHidingTimer() <= 0) {
          // Fully revealed -- transition to UP
          ability.setHidingState(AbilityComponent.HidingState.UP);
          ability.setUpTimer(0f);
          if (combat != null) {
            combat.clearModifiers(ModifierSource.ABILITY_HIDING);
          }
        }
      }
      case UP -> {
        if (combat == null || !combat.hasTarget()) {
          // No target -- count up toward hiding
          ability.setUpTimer(ability.getUpTimer() + deltaTime);
          if (ability.getUpTimer() >= data.upTime()) {
            // Start hiding transition
            ability.setHidingState(AbilityComponent.HidingState.HIDING);
            ability.setHidingTimer(data.hideTime());
          }
        } else {
          // Target exists -- reset upTimer
          ability.setUpTimer(0f);
        }
      }
      case HIDING -> {
        // During hiding transition, combat is NOT disabled -- Tesla can still attack
        // if an enemy appears, which cancels the hide
        if (combat != null && combat.hasTarget()) {
          // Cancel hide -- go back to UP
          ability.setHidingState(AbilityComponent.HidingState.UP);
          ability.setUpTimer(0f);
          return;
        }
        ability.setHidingTimer(ability.getHidingTimer() - deltaTime);
        if (ability.getHidingTimer() <= 0) {
          // Fully hidden
          ability.setHidingState(AbilityComponent.HidingState.HIDDEN);
          if (combat != null) {
            combat.setCombatDisabled(ModifierSource.ABILITY_HIDING, true);
          }
        }
      }
    }
  }

  /**
   * Forces a hiding building to reveal immediately. Called by AreaEffectSystem when Freeze is
   * applied to a hidden building.
   */
  public static void forceRevealHiding(Building building) {
    AbilityComponent ability = building.getAbility();
    if (ability == null || !(ability.getData() instanceof HidingAbility)) {
      return;
    }
    building.forceReveal();
  }
}
