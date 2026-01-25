package org.crforge.core.physics;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;

public interface Pathfinder {

  /**
   * Calculates the next movement angle (in radians) for an entity to reach a target.
   *
   * @param startPos Current position of the entity
   * @param moveType The movement type (GROUND, AIR, etc.)
   * @param targetX  Target X coordinate
   * @param targetY  Target Y coordinate
   * @param arena    The game arena
   * @return The angle in radians to move towards
   */
  float getNextMovementAngle(
      Position startPos, MovementType moveType, float targetX, float targetY, Arena arena);
}
