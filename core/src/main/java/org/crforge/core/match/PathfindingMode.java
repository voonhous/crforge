package org.crforge.core.match;

/**
 * Which movement and target-acquisition rules a match runs its ground troops under.
 *
 * <p>The two modes are not variations of one another: they are separate implementations that own
 * different parts of a troop's tick. Picking a mode is a match-level decision, made once when the
 * match is created and never changed while it runs.
 */
public enum PathfindingMode {

  /**
   * The simulator's own rules: a per-tick steering angle from the bridge and river geometry,
   * fractional speed integrated through the position's sub-unit carry, circle collision separation
   * and arena bounds clamping. This is the default, and every troop is handled by it.
   */
  WAYPOINTS,

  /**
   * The standard game's grid rules: ground troops route cell by cell over the arena's routing grid
   * with a building footprint overlay, follow the route with an integer per-tick budget, and pick
   * their targets through the spatial index and the tower default selection.
   *
   * <p>Only ground troops that do not jump, hover, dash or tunnel are handled this way; everything
   * else keeps the {@link #WAYPOINTS} behaviour in the same match.
   */
  GRID
}
