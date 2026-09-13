package org.crforge.core.physics;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.util.GameUnits;

/** Basic pathfinder that handles lane-based movement and river crossing via bridges. */
public class BasePathfinder implements Pathfinder {

  // Bridge center X coordinates in game units (derived from Arena constants)
  private static final int LEFT_BRIDGE_CENTER_X = Arena.LEFT_BRIDGE_CENTER_X; // 3500
  private static final int RIGHT_BRIDGE_CENTER_X = Arena.RIGHT_BRIDGE_CENTER_X; // 14500

  // River boundaries in game units.
  // The river occupies Y rows 15 and 16 (indices).
  // We use a buffer to ensure units don't "clip" the corners of the water.
  private static final int RIVER_Y_MIN = (Arena.RIVER_Y - 1) * GameUnits.UNITS_PER_TILE; // 15000
  private static final int RIVER_Y_MAX = (Arena.RIVER_Y + 1) * GameUnits.UNITS_PER_TILE; // 17000
  private static final int RIVER_CENTER_Y = Arena.RIVER_Y * GameUnits.UNITS_PER_TILE; // 16000

  // Epsilon to ensure units cross the boundary instead of stopping exactly on it (0.1 tiles)
  private static final int BOUNDARY_EPSILON = 100;

  // Distance before the bridge approach line at which units switch to the bridge center (0.2 tiles)
  private static final int APPROACH_TOLERANCE = 200;

  // Units within this X distance of a bridge center walk straight instead of re-centering (1 tile)
  private static final int BRIDGE_ALIGNED_DISTANCE = GameUnits.UNITS_PER_TILE;

  @Override
  public float getNextMovementAngle(
      Position startPos, MovementType moveType, int targetX, int targetY, Arena arena) {

    int curX = startPos.getX();
    int curY = startPos.getY();

    // 1. Air units always fly in a straight line to the target.
    if (moveType == MovementType.AIR) {
      return angle(targetY - curY, targetX - curX);
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
      int distToLeft = Math.abs(curX - LEFT_BRIDGE_CENTER_X);
      int distToRight = Math.abs(curX - RIGHT_BRIDGE_CENTER_X);

      int bridgeX = (distToLeft < distToRight) ? LEFT_BRIDGE_CENTER_X : RIGHT_BRIDGE_CENTER_X;

      // Aim for the bridge center on our side of the river first to ensure a straight approach
      int approachY = needsToCrossNorth ? RIVER_Y_MIN : RIVER_Y_MAX;

      // If we haven't reached the bridge approach Y yet, move towards that point
      // (The approach point is essentially the entrance to the bridge)
      if ((needsToCrossNorth && curY < approachY - APPROACH_TOLERANCE)
          || (needsToCrossSouth && curY > approachY + APPROACH_TOLERANCE)) {
        // Optimization: If unit is already aligned with bridge (within safe width),
        // don't force it to center X.
        int distFromCenter = Math.abs(curX - bridgeX);
        int targetBridgeX = bridgeX;

        if (distFromCenter < BRIDGE_ALIGNED_DISTANCE) {
          targetBridgeX = curX;
        }

        return angle(approachY - curY, targetBridgeX - curX);
      }

      // Otherwise, aim for the center of the river on the bridge
      // Apply similar logic here as well for entering the bridge
      int distFromCenter = Math.abs(curX - bridgeX);
      int targetBridgeX = bridgeX;

      if (distFromCenter < BRIDGE_ALIGNED_DISTANCE) {
        targetBridgeX = curX;
      }

      return angle(RIVER_CENTER_Y - curY, targetBridgeX - curX);
    }

    // 4. If we are currently inside the river zone (i.e., on a bridge),
    // keep moving towards the bridge exit before turning towards the final target.
    if (inRiverZone) {
      // BOUNDARY_EPSILON to ensure we aim PAST the boundary.
      // If we aim exactly for RIVER_Y_MAX (17000) and curY is 17000, we stop moving Y.
      // By aiming for 17100, we force the unit to cross into the North zone (Y > 17000).
      int exitY =
          (targetY > curY) ? RIVER_Y_MAX + BOUNDARY_EPSILON : RIVER_Y_MIN - BOUNDARY_EPSILON;

      // Find which bridge we are currently on (same logic as pre-crossing selection)
      int distToLeft = Math.abs(curX - LEFT_BRIDGE_CENTER_X);
      int distToRight = Math.abs(curX - RIGHT_BRIDGE_CENTER_X);
      int bridgeX = (distToLeft < distToRight) ? LEFT_BRIDGE_CENTER_X : RIGHT_BRIDGE_CENTER_X;

      int targetBridgeX = bridgeX;

      // Optimization: If unit is safely on the bridge, move straight forward.
      // This prevents diagonal movement (which reduces forward speed) when slightly off-center.
      // Bridge width is 3 tiles. Safe zone is roughly center +/- 1 tile (leaving 0.5 buffer for
      // radius).
      int distFromCenter = Math.abs(curX - bridgeX);

      if (distFromCenter < BRIDGE_ALIGNED_DISTANCE) {
        // We are safely in the middle of the bridge. Don't correct X.
        // This stops units from rotating towards each other when side-by-side on bridge.
        targetBridgeX = curX;
      }

      // Move straight across the bridge until we clear the river zone
      return angle(exitY - curY, targetBridgeX - curX);
    }

    // 5. Default: move straight to target (same side of river or no river obstacles)
    return angle(targetY - curY, targetX - curX);
  }

  /** Angle in radians of a game-unit displacement. */
  private static float angle(int dy, int dx) {
    return (float) Math.atan2(dy, dx);
  }
}
