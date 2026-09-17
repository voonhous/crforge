package org.crforge.core.pathfinding.move;

/**
 * The two match-wide clone distances the speed budget reads while an entity is being set up as a
 * clone.
 *
 * @param cloneDistanceX clone offset along the arena's width, in routing cells
 * @param cloneDistanceY clone offset along the arena's length, in routing cells
 */
public record SpeedGlobals(int cloneDistanceX, int cloneDistanceY) {

  /** The standard game's values, both zero. */
  public static SpeedGlobals standard() {
    return new SpeedGlobals(0, 0);
  }
}
