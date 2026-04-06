package org.crforge.core.physics.astar;

/**
 * Pathfinding cost parameters extracted from game binary analysis.
 *
 * <p>All costs are integer values used with the cost formula: {@code (distance * tileCost + 50) /
 * 100} where distance is 100 for cardinal moves and 141 for diagonal moves.
 *
 * <p>Source: scratch/pathfinding/PATHFINDING_SPEC.md Section 2 (verified against libg.so).
 */
public final class CostTable {

  private CostTable() {}

  // -- Tile costs --

  /** Default ground tile cost. */
  public static final int DEFAULT_COST = 7;

  /** Road/bridge tile cost (lower than default = units prefer bridges). */
  public static final int ROAD_COST = 5;

  /** Matching-lane road cost (same as ROAD_COST; applied when unit is in its preferred lane). */
  public static final int MATCHING_ROAD_COST = 5;

  /** Water tile cost for units that can cross water (flying/swimming). */
  public static final int WATER_COST = 5;

  /** Building-occupied tile cost (very high = path around buildings). */
  public static final int BUILDING_COST = 100;

  /** Impassable tile cost (river for ground units, banned zones). */
  public static final int BLOCKED_COST = 100;

  // -- Heuristic --

  /** Manhattan distance multiplier for the A* heuristic. */
  public static final int HEURISTIC_WEIGHT = 5;

  // -- Distance constants (fixed-point, 100 = 1.0 tile) --

  /** Cardinal move distance (1.0 tile = 100). */
  public static final int CARDINAL_DISTANCE = 100;

  /** Diagonal move distance (sqrt(2) tiles ~= 141). */
  public static final int DIAGONAL_DISTANCE = 141;

  // -- Algorithm limits --

  /** Maximum A* node expansions before giving up. */
  public static final int MAX_STEPS = 1000;

  /** Maximum waypoints in a cached path chain. */
  public static final int MAX_WAYPOINTS = 18;

  /** Path cache invalidation threshold: recalculate when target moves this many tiles. */
  public static final int SAMEPATH_EPSILON = 3;
}
