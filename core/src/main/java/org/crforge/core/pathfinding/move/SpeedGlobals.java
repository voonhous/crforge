package org.crforge.core.pathfinding.move;

/**
 * The two match-wide clone distances the speed budget reads while an entity is being set up as a
 * clone. A clone and its original each move toward a point that many steps of 500 away along each
 * axis, and the budget is a quarter cell of the larger, 125 a visit for the standard game.
 *
 * @param cloneDistanceX the clone distance along the arena's width
 * @param cloneDistanceY the clone distance along the arena's length
 */
public record SpeedGlobals(int cloneDistanceX, int cloneDistanceY) {

  /** The standard game's values: 0 along the width, 250 along the length. */
  public static SpeedGlobals standard() {
    return new SpeedGlobals(0, 250);
  }
}
