package org.crforge.core.physics;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;

/**
 * Basic pathfinder that handles lane-based movement and river crossing via bridges.
 */
public class BasePathfinder implements Pathfinder {

  // Bridge center X coordinates (derived from Arena constants)
  private static final float LEFT_BRIDGE_CENTER_X = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
  private static final float RIGHT_BRIDGE_CENTER_X = Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;

  // River boundaries in logical coordinates.
  // The river occupies Y rows 15 and 16 (indices).
  // We use a buffer to ensure units don't "clip" the corners of the water.
  private static final float RIVER_Y_MIN = Arena.RIVER_Y - 1.0f; // 15.0
  private static final float RIVER_Y_MAX = Arena.RIVER_Y + 1.0f; // 17.0
  private static final float RIVER_CENTER_Y = Arena.RIVER_Y;    // 16.0

  @Override
  public float getNextMovementAngle(
      Position startPos, MovementType moveType, float targetX, float targetY, Arena arena) {

    float curX = startPos.getX();
    float curY = startPos.getY();

    // 1. Air units always fly in a straight line to the target.
    if (moveType == MovementType.AIR) {
      return (float) Math.atan2(targetY - curY, targetX - curX);
    }

    // 2. Determine river status
    boolean isNorthOfRiver = curY > RIVER_Y_MAX;
    boolean isSouthOfRiver = curY < RIVER_Y_MIN;

    // We are "In River Zone" if our Y coordinate overlaps the river's range
    boolean inRiverZone = !isNorthOfRiver && !isSouthOfRiver;

    boolean targetIsNorth = targetY > RIVER_Y_MAX;
    boolean targetIsSouth = targetY < RIVER_Y_MIN;

    // 3. Determine if we need to cross a bridge
    // Condition: We are on one side, and the target is on the other.
    boolean needsToCrossNorth = isSouthOfRiver && targetIsNorth;
    boolean needsToCrossSouth = isNorthOfRiver && targetIsSouth;

    if (needsToCrossNorth || needsToCrossSouth) {
      // Find the closest bridge based on current X position
      float distToLeft = Math.abs(curX - LEFT_BRIDGE_CENTER_X);
      float distToRight = Math.abs(curX - RIGHT_BRIDGE_CENTER_X);

      float bridgeX = (distToLeft < distToRight) ? LEFT_BRIDGE_CENTER_X : RIGHT_BRIDGE_CENTER_X;

      // Aim for the bridge center on our side of the river first to ensure a straight approach
      float approachY = needsToCrossNorth ? RIVER_Y_MIN : RIVER_Y_MAX;

      // If we haven't reached the bridge approach Y yet, move towards that point
      if ((needsToCrossNorth && curY < approachY - 0.2f) || (needsToCrossSouth && curY > approachY + 0.2f)) {
        return (float) Math.atan2(approachY - curY, bridgeX - curX);
      }

      // Otherwise, aim for the center of the river on the bridge
      return (float) Math.atan2(RIVER_CENTER_Y - curY, bridgeX - curX);
    }

    // 4. If we are currently inside the river zone (i.e., on a bridge),
    // keep moving towards the bridge exit before turning towards the final target.
    if (inRiverZone) {
      float exitY = (targetY > curY) ? RIVER_Y_MAX : RIVER_Y_MIN;

      // Find which bridge we are currently on
      float distToLeft = Math.abs(curX - LEFT_BRIDGE_CENTER_X);
      float bridgeX = (distToLeft < (Arena.WIDTH / 4f)) ? LEFT_BRIDGE_CENTER_X : RIGHT_BRIDGE_CENTER_X;

      // Move straight across the bridge until we clear the river zone
      return (float) Math.atan2(exitY - curY, bridgeX - curX);
    }

    // 5. Default: move straight to target (same side of river or no river obstacles)
    return (float) Math.atan2(targetY - curY, targetX - curX);
  }
}
