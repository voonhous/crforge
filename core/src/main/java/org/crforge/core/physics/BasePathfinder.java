package org.crforge.core.physics;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.MovementType;

public class BasePathfinder implements Pathfinder {

  // Bridge center X coordinates (derived from Arena constants)
  private static final float LEFT_BRIDGE_CENTER_X = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
  private static final float RIGHT_BRIDGE_CENTER_X = Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;

  // River boundaries (in float coordinates)
  // River spans 2 tiles at RIVER_Y and RIVER_Y - 1
  private static final float RIVER_BOTTOM_Y = Arena.RIVER_Y - 1;
  private static final float RIVER_TOP_Y = Arena.RIVER_Y + 1;
  private static final float RIVER_CENTER_Y = Arena.RIVER_Y;

  @Override
  public float getNextMovementAngle(
      Position startPos, MovementType moveType, float targetX, float targetY, Arena arena) {

    // Air units always fly straight
    if (moveType == MovementType.AIR) {
      return (float) Math.atan2(targetY - startPos.getY(), targetX - startPos.getX());
    }

    // Determine which side of the river the entity and target are on
    boolean isSouth = startPos.getY() < RIVER_BOTTOM_Y;
    boolean isNorth = startPos.getY() > RIVER_TOP_Y;

    boolean targetIsSouth = targetY < RIVER_BOTTOM_Y;
    boolean targetIsNorth = targetY > RIVER_TOP_Y;

    // If we need to cross the river (One is North, One is South)
    if ((isSouth && targetIsNorth) || (isNorth && targetIsSouth)) {
      // Find the closest bridge
      float distToLeft = Math.abs(startPos.getX() - LEFT_BRIDGE_CENTER_X);
      float distToRight = Math.abs(startPos.getX() - RIGHT_BRIDGE_CENTER_X);

      float bridgeX = (distToLeft < distToRight) ? LEFT_BRIDGE_CENTER_X : RIGHT_BRIDGE_CENTER_X;
      float bridgeY = RIVER_CENTER_Y;

      // Move toward the bridge center
      return (float) Math.atan2(bridgeY - startPos.getY(), bridgeX - startPos.getX());
    }

    // If position is in the river zone (crossing) or on the same side, go straight
    return (float) Math.atan2(targetY - startPos.getY(), targetX - startPos.getX());
  }
}