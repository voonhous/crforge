package org.crforge.core.ability;

/**
 * Dash ability data (Bandit, MegaKnight). Troop dashes toward a target within range, dealing damage
 * on landing.
 *
 * @param dashDamage damage dealt on dash landing
 * @param dashMinRange minimum edge-to-edge distance to trigger dash acquisition, in game units
 * @param dashMaxRange maximum edge-to-edge distance for dash to remain valid, in game units
 * @param dashRadius AOE radius for dash landing damage in game units (0 = single target)
 * @param dashCooldown seconds between dashes
 * @param dashImmuneTime seconds of invulnerability during dash flight
 * @param dashLandingTime seconds spent in landing animation after arriving
 * @param dashConstantTime fixed flight duration (0 = use default dash speed)
 * @param dashPushback knockback distance applied to targets hit, in game units
 */
public record DashAbility(
    int dashDamage,
    int dashMinRange,
    int dashMaxRange,
    int dashRadius,
    float dashCooldown,
    float dashImmuneTime,
    float dashLandingTime,
    float dashConstantTime,
    int dashPushback)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.DASH;
  }
}
