package org.crforge.core.pathfinding.grid;

/**
 * What one run of {@link RouteSearch} produced.
 *
 * @param route the cells to walk, goal first, with the next waypoint last. It excludes the start
 *     cell, is empty when the goal was never reached, and holds the start cell alone in the
 *     degenerate case where the start had no usable neighbour at all.
 * @param counters four tallies of the run: how many nodes were opened for the first time, how many
 *     already open nodes were given a cheaper way in, how many closed nodes were reopened, and the
 *     largest number of open nodes held at once
 * @param remainingBudget what is left of the expansion budget the run was given. A budget of zero
 *     or less means the run was unlimited and the value is handed back unchanged.
 */
public record RouteSearchResult(Route route, int[] counters, int remainingBudget) {

  /** Index in {@link #counters()} of the nodes opened for the first time. */
  public static final int NEW_NODES = 0;

  /** Index in {@link #counters()} of the open nodes given a cheaper way in. */
  public static final int REFRESHED_NODES = 1;

  /** Index in {@link #counters()} of the closed nodes that were reopened. */
  public static final int REOPENED_NODES = 2;

  /** Index in {@link #counters()} of the largest number of open nodes held at once. */
  public static final int PEAK_OPEN_NODES = 3;
}
