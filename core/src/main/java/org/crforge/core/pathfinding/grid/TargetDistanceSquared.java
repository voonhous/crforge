package org.crforge.core.pathfinding.grid;

/**
 * Squared distance from a point to the target a unit is heading for, in game units.
 *
 * <p>It measures to the target's own position and does not subtract the target's radius, so a
 * caller comparing it against an attack range is comparing centre to centre.
 */
@FunctionalInterface
public interface TargetDistanceSquared {

  /**
   * Squared distance from the given point to the target.
   *
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   */
  int distanceSquared(int x, int y);
}
