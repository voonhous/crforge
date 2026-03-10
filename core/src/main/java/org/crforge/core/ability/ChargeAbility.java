package org.crforge.core.ability;

/**
 * Charge ability data (Prince, Dark Prince, Battle Ram).
 * Troop builds charge while moving; once fully charged, the next attack deals bonus damage
 * and the troop moves faster.
 *
 * @param chargeDamage    damage dealt on charged impact
 * @param speedMultiplier movement speed multiplier while charged
 */
public record ChargeAbility(int chargeDamage, float speedMultiplier) implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.CHARGE;
  }
}
