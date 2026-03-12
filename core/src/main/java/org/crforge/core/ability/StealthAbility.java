package org.crforge.core.ability;

/**
 * Stealth ability data (Royal Ghost). The unit turns invisible after not attacking for a period,
 * making it untargetable by units but still hittable by position-based AOE spells. When it attacks
 * while invisible, a grace period keeps it invisible briefly before revealing.
 *
 * @param fadeTime seconds of not attacking before becoming invisible (notAttackingTimeMs / 1000)
 * @param attackGracePeriod seconds to stay invisible after starting an attack while invisible
 *     (hideTimeMs / 1000)
 */
public record StealthAbility(float fadeTime, float attackGracePeriod) implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.STEALTH;
  }
}
