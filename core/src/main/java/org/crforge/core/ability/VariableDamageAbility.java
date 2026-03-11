package org.crforge.core.ability;

import java.util.List;

/**
 * Variable damage (inferno) ability data (Inferno Tower, Inferno Dragon). Damage escalates through
 * stages the longer the troop attacks the same target.
 *
 * @param stages ordered list of damage stages with durations
 */
public record VariableDamageAbility(List<VariableDamageStage> stages) implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.VARIABLE_DAMAGE;
  }
}
