package org.crforge.core.physics;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;

public interface Pathfinder {

  /**
   * Calculates the next movement angle (in radians) for an entity to reach a target.
   *
   * @param startPos Current position of the entity
   * @param moveType The movement type (GROUND, AIR, etc.)
   * @param targetX Target X coordinate
   * @param targetY Target Y coordinate
   * @param arena The game arena
   * @return The angle in radians to move towards
   */
  float getNextMovementAngle(
      Position startPos, MovementType moveType, float targetX, float targetY, Arena arena);

  /**
   * Calculates the next movement angle with entity context for path caching and occlusions.
   *
   * <p>The default implementation delegates to the basic overload, ignoring the entity. Pathfinder
   * implementations that need entity identity (e.g. for per-entity path caching or team-based
   * occlusions) should override this method.
   *
   * @param entity The entity being moved (provides ID, team, position for caching/occlusion)
   */
  default float getNextMovementAngle(
      Position startPos,
      MovementType moveType,
      float targetX,
      float targetY,
      Arena arena,
      Entity entity) {
    return getNextMovementAngle(startPos, moveType, targetX, targetY, arena);
  }
}
