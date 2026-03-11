package org.crforge.core.ability;

/**
 * Dash ability data (Bandit, MegaKnight). Troop dashes toward a target within range, dealing damage
 * on landing.
 *
 * @param dashDamage damage dealt on dash landing
 * @param dashMinRange minimum distance to trigger dash acquisition
 * @param dashMaxRange maximum distance for dash to remain valid
 * @param dashRadius AOE radius for dash landing damage (0 = single target)
 * @param dashCooldown seconds between dashes
 * @param dashImmuneTime seconds of invulnerability during dash flight
 * @param dashLandingTime seconds spent in landing animation after arriving
 * @param dashConstantTime fixed flight duration (0 = use default dash speed)
 * @param dashPushback knockback distance applied to targets hit
 */
public record DashAbility(
    int dashDamage,
    float dashMinRange,
    float dashMaxRange,
    float dashRadius,
    float dashCooldown,
    float dashImmuneTime,
    float dashLandingTime,
    float dashConstantTime,
    float dashPushback)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.DASH;
  }
}
