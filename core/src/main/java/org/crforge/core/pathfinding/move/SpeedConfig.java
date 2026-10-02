package org.crforge.core.pathfinding.move;

/**
 * The card configuration columns the speed budget and the push gate read.
 *
 * <p>Every speed is raw game units per tick: at 20 ticks per second a speed of 60 is 1200 game
 * units per second, or 1.2 arena tiles per second.
 *
 * @param speed budget of an ordinary moving state, before the percent modifiers are applied
 * @param jumpSpeed budget while jumping or dashing
 * @param spawnPathfindSpeed budget while routing to a spawn destination
 * @param ingamePathfindSpeed budget while routing to a mid-match destination
 * @param chargeSpeedMultiplier percent the budget is scaled by once the charge is complete; 100
 *     leaves it unchanged
 * @param ingamePathfindVisible whether a unit routing to a mid-match destination takes part in
 *     pushing
 */
public record SpeedConfig(
    int speed,
    int jumpSpeed,
    int spawnPathfindSpeed,
    int ingamePathfindSpeed,
    int chargeSpeedMultiplier,
    boolean ingamePathfindVisible) {

  /** Percent multiplier that leaves the budget unchanged. */
  public static final int NEUTRAL_CHARGE_MULTIPLIER = 100;

  /**
   * The speeds of a plain ground unit: its own raw speed, no jump, no pathfind speeds and a neutral
   * charge multiplier.
   *
   * @param rawSpeed the unit's speed column, in game units per tick
   */
  public static SpeedConfig forGroundUnit(int rawSpeed) {
    return new SpeedConfig(rawSpeed, 0, 0, 0, NEUTRAL_CHARGE_MULTIPLIER, false);
  }

  /**
   * These speeds with a charge multiplier, which scales the ordinary budget once the charge is
   * complete.
   *
   * @param multiplier the percent the budget is scaled by
   */
  public SpeedConfig withChargeMultiplier(int multiplier) {
    return new SpeedConfig(
        speed,
        jumpSpeed,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        multiplier,
        ingamePathfindVisible);
  }

  /**
   * These speeds with a jump speed, the budget of the jumping and dashing states.
   *
   * @param speed the budget while jumping or dashing
   */
  public SpeedConfig withJumpSpeed(int speed) {
    return new SpeedConfig(
        this.speed,
        speed,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        chargeSpeedMultiplier,
        ingamePathfindVisible);
  }

  /**
   * These speeds with a spawn-pathfinding speed, the budget of a unit walking to its placement.
   *
   * @param speed the budget in the spawn-pathfinding state
   */
  public SpeedConfig withSpawnPathfindSpeed(int speed) {
    return new SpeedConfig(
        this.speed,
        jumpSpeed,
        speed,
        ingamePathfindSpeed,
        chargeSpeedMultiplier,
        ingamePathfindVisible);
  }

  /**
   * These speeds with the in-game pathfinding columns: the budget of a unit routing to a point its
   * ability sent it to, and whether it takes part in pushing meanwhile.
   *
   * @param speed the budget in the in-game pathfinding state
   * @param visible true for a unit that stays visible, and pushable, while it routes there
   */
  public SpeedConfig withIngamePathfind(int speed, boolean visible) {
    return new SpeedConfig(
        this.speed, jumpSpeed, spawnPathfindSpeed, speed, chargeSpeedMultiplier, visible);
  }
}
