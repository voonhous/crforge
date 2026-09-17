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
    boolean entersWaterWhileSpawnPathfinding) {

  /**
   * The configuration of a plain ground unit: no attachment, no flight, no charge, no jump, no dash
   * and no movement pause. Every value is zero or absent, which is what a Knight carries in the
   * recorded trajectories.
   */
  public static MovementConfig forGroundUnit() {
    return new MovementConfig(0, 0, 0, 0, 0, false, 0, null, null, false, 0, 0, 0, 0, 0, 0, false);
  }
}
