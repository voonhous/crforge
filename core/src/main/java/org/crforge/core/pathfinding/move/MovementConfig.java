package org.crforge.core.pathfinding.move;

/**
 * The card configuration columns the movement visit, the follower and the displacement helper read.
 *
 * <p>Distances are game units, times are milliseconds and speeds are game units per tick. A plain
 * ground unit such as a Knight carries none of these columns, which is what {@link
 * #forGroundUnit()} returns; every branch they guard is then skipped.
 *
 * @param spawnAngleShift angle, in degrees, added to an attached entity's share of the spawn arc
 * @param spawnMaxAngle width, in degrees, of the arc an attached entity's share is taken from
 * @param spawnAttachMaxRotation largest rotation, in degrees, an attached entity may turn per visit
 * @param spawnRadius distance, in game units, an attached entity is placed at from its parent
 * @param flyingHeight height above the ground, in game units, a flying entity is held at
 * @param flyDirectPaths whether a flying entity walks straight at its reference instead of routing
 * @param chargeRange distance, in game units, the entity must cover to complete a charge
 * @param onStartChargingAction action run once the charge completes, or null
 * @param attackPushbackEndAction action run when an attack pushback ends, or null
 * @param jumpEnabled whether the entity jumps over water rather than walking around it
 * @param jumpHeight peak height, in game units, of a jump or dash arc
 * @param dashConstantTime duration, in milliseconds, of a dash with a fixed height profile
 * @param stopMovementAfterMs milliseconds of movement after which the entity pauses
 * @param waitMs milliseconds the entity pauses for once that limit is passed
 * @param spawnPathfindSpeed speed, in game units per tick, while routing to a spawn destination
 * @param ingamePathfindSpeed speed, in game units per tick, while routing to a mid-match
 *     destination
 * @param entersWaterWhileSpawnPathfinding whether the entity may enter water while spawn
 *     pathfinding
 * @param hovering whether the entity hovers: its route may cross water, at the water cost, and it
 *     is not moved off water after a push
 */
public record MovementConfig(
    int spawnAngleShift,
    int spawnMaxAngle,
    int spawnAttachMaxRotation,
    int spawnRadius,
    int flyingHeight,
    boolean flyDirectPaths,
    int chargeRange,
    String onStartChargingAction,
    String attackPushbackEndAction,
    boolean jumpEnabled,
    int jumpHeight,
    int dashConstantTime,
    int stopMovementAfterMs,
    int waitMs,
    int spawnPathfindSpeed,
    int ingamePathfindSpeed,
    boolean entersWaterWhileSpawnPathfinding,
    boolean hovering) {

  /**
   * The configuration of a plain ground unit: no attachment, no flight, no charge, no jump, no dash
   * and no movement pause. Every value is zero or absent, which is what a Knight carries in the
   * recorded trajectories.
   */
  public static MovementConfig forGroundUnit() {
    return forGroundUnit(0, 0);
  }

  /**
   * The configuration of a unit that rides on a parent, as its attached placement reads it.
   *
   * @param spawnAngleShift degrees its place around the parent is turned by
   * @param spawnMaxAngle the arc its share of the parent's ring is taken from
   * @param spawnAttachMaxRotation how far it may face away from its parent's heading while it
   *     attacks; 0 for no limit
   * @param flyingHeight the height it is held at
   */
  public static MovementConfig forRider(
      int spawnAngleShift, int spawnMaxAngle, int spawnAttachMaxRotation, int flyingHeight) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        0,
        flyingHeight,
        false,
        0,
        null,
        null,
        false,
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        false);
  }

  /**
   * The configuration of a parent as its riders' placement reads it: the radius they sit at.
   *
   * @param spawnRadius the parent's spawn radius
   */
  public static MovementConfig forParent(int spawnRadius) {
    return new MovementConfig(
        0, 0, 0, spawnRadius, 0, false, 0, null, null, false, 0, 0, 0, 0, 0, 0, false, false);
  }

  /**
   * The configuration of a plain ground unit that walks in bursts: after every stretch of walking
   * it stands still for a while, as the Giant and the Golem do.
   *
   * @param stopMovementAfterMs how long it walks before each stop; 0 for never
   * @param waitMs how long each stop lasts
   */
  public static MovementConfig forGroundUnit(int stopMovementAfterMs, int waitMs) {
    return new MovementConfig(
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        null,
        null,
        false,
        0,
        0,
        stopMovementAfterMs,
        waitMs,
        0,
        0,
        false,
        false);
  }

  /**
   * This configuration with a charge: the entity builds a charge as it walks, complete after ten
   * times the range.
   *
   * @param range a tenth of the distance a full charge takes; 0 for none
   */
  public MovementConfig withCharge(int range) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        flyingHeight,
        flyDirectPaths,
        range,
        onStartChargingAction,
        attackPushbackEndAction,
        jumpEnabled,
        jumpHeight,
        dashConstantTime,
        stopMovementAfterMs,
        waitMs,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        entersWaterWhileSpawnPathfinding,
        hovering);
  }

  /**
   * This configuration with a dash of fixed length in time: it flies for that long, its height
   * following the dash's profile up to the jump height and back.
   *
   * @param constantTime how long the dash flies, in milliseconds; 0 for a dash that flies until it
   *     reaches its target
   */
  public MovementConfig withDashConstantTime(int constantTime) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        flyingHeight,
        flyDirectPaths,
        chargeRange,
        onStartChargingAction,
        attackPushbackEndAction,
        jumpEnabled,
        jumpHeight,
        constantTime,
        stopMovementAfterMs,
        waitMs,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        entersWaterWhileSpawnPathfinding,
        hovering);
  }

  /**
   * This configuration with the river jump: the entity leaps over the water in its way.
   *
   * @param enabled true for a unit that jumps
   * @param height the height of its jump arc
   */
  public MovementConfig withJump(boolean enabled, int height) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        flyingHeight,
        flyDirectPaths,
        chargeRange,
        onStartChargingAction,
        attackPushbackEndAction,
        enabled,
        height,
        dashConstantTime,
        stopMovementAfterMs,
        waitMs,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        entersWaterWhileSpawnPathfinding,
        hovering);
  }

  /**
   * This configuration with a spawn-pathfinding speed, which is also the radius at which such a
   * unit counts a route node as reached.
   *
   * @param speed the speed in the spawn-pathfinding state
   */
  public MovementConfig withSpawnPathfindSpeed(int speed) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        flyingHeight,
        flyDirectPaths,
        chargeRange,
        onStartChargingAction,
        attackPushbackEndAction,
        jumpEnabled,
        jumpHeight,
        dashConstantTime,
        stopMovementAfterMs,
        waitMs,
        speed,
        ingamePathfindSpeed,
        entersWaterWhileSpawnPathfinding,
        hovering);
  }

  /**
   * This configuration with the water let through while spawn pathfinding, as a unit that morphs as
   * it surfaces is.
   *
   * @param enters true to let the grid move enter water in the spawn-pathfinding state
   */
  public MovementConfig withEntersWaterWhileSpawnPathfinding(boolean enters) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        flyingHeight,
        flyDirectPaths,
        chargeRange,
        onStartChargingAction,
        attackPushbackEndAction,
        jumpEnabled,
        jumpHeight,
        dashConstantTime,
        stopMovementAfterMs,
        waitMs,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        enters,
        hovering);
  }

  /**
   * This configuration with hovering: the entity's route may cross water, at the water cost, and a
   * push never moves it off water.
   *
   * @param hovers true for a unit whose row hovers
   */
  public MovementConfig withHovering(boolean hovers) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        flyingHeight,
        flyDirectPaths,
        chargeRange,
        onStartChargingAction,
        attackPushbackEndAction,
        jumpEnabled,
        jumpHeight,
        dashConstantTime,
        stopMovementAfterMs,
        waitMs,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        entersWaterWhileSpawnPathfinding,
        hovers);
  }

  /**
   * This configuration with a flight: the height the entity flies at and whether it flies direct
   * paths, which the waypoint selector reads together.
   *
   * @param height the row's flying height; 0 for a ground unit
   * @param directPaths true for a row that flies straight at the point at its attack range from its
   *     reference
   */
  public MovementConfig withFlight(int height, boolean directPaths) {
    return new MovementConfig(
        spawnAngleShift,
        spawnMaxAngle,
        spawnAttachMaxRotation,
        spawnRadius,
        height,
        directPaths,
        chargeRange,
        onStartChargingAction,
        attackPushbackEndAction,
        jumpEnabled,
        jumpHeight,
        dashConstantTime,
        stopMovementAfterMs,
        waitMs,
        spawnPathfindSpeed,
        ingamePathfindSpeed,
        entersWaterWhileSpawnPathfinding,
        hovering);
  }
}
