/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

import java.util.Arrays;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The grid route search: a weighted best-first search over a row-major array of cell costs.
 *
 * <p>Cells are identified by their row-major id, {@code row * width + column}. A cost of -1 rejects
 * a cell outright; every other cost is a positive weight. Entering a cell costs that cell's weight
 * times the step factor, 10 for a cardinal step and 14 for a diagonal one, so the search charges
 * for the cell it moves <b>into</b> and never for the one it leaves. Diagonals are taken without
 * checking the two cardinal cells beside them, so a route may cut a corner.
 *
 * <p>Neighbours are always examined in the same order - up, down, left, right, upper-left,
 * lower-left, lower-right, upper-right - and that order, together with the tie-breaking of {@link
 * RouteSearchHeap}, is what makes the result reproducible.
 *
 * <p>Two details of the loop are easy to get wrong and both are deliberate: the start cell is
 * expanded <b>before</b> it is closed, and a node taken off the heap is expanded <b>before</b> the
 * "have we closed the goal" test, so the goal is always expanded exactly once.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled against reference routes compared node for node and, through the positions they"
            + " record, against the walks of the reference battles, every tick of the Knight walks"
            + " smoke-c1/knight, knight_centre_s0 and knight_behind_king_s0,"
            + " golden-gaps-v1/walk_knight_left_inner and walk_knight_right_rear among them:"
            + " neighbour order, step costs, the heuristic, open-node refresh and the termination"
            + " rule. The accumulating heuristic, closed-node reopening and the expansion budget"
            + " are switched off in the standard game and are held by this class's own tests only.")
public final class RouteSearch {

  /** Column step, row step and step factor of each neighbour, in the order they are examined. */
  private static final int[][] NEIGHBOURS = {
    {0, -1, 10},
    {0, 1, 10},
    {-1, 0, 10},
    {1, 0, 10},
    {-1, -1, 14},
    {-1, 1, 14},
    {1, 1, 14},
    {1, -1, 14}
  };

  /** A cell nothing has looked at yet. */
  private static final int UNVISITED = 0;

  /** A cell that is on the heap waiting to be expanded. */
  private static final int OPEN = 1;

  /** A cell that has been expanded. */
  private static final int CLOSED = 2;

  private RouteSearch() {
    // Utility class
  }

  /**
   * The working arrays of a search, kept to serve the next one: the state, parent, carried cost and
   * priority of every cell, the heap of open cells, and room for the cost field. A search clears
   * them before it starts, so one set serves every search of a battle, one search at a time; a
   * battle's grid holds its own ({@link CellGrid#getSearchBuffers()}). Nothing of a search's answer
   * refers to them.
   */
  public static final class Buffers {

    private int cells = -1;
    private int[] states;
    private int[] parents;
    private int[] carried;
    private int[] priority;
    private RouteSearchHeap heap;
    private int[] costField;

    /** Makes the per-cell arrays ready for a search over this many cells: sized and cleared. */
    private void prepare(int cells) {
      if (this.cells != cells) {
        this.cells = cells;
        states = new int[cells];
        parents = new int[cells];
        carried = new int[cells];
        priority = new int[cells];
        heap = new RouteSearchHeap(priority, 16);
        return;
      }
      Arrays.fill(states, 0);
      Arrays.fill(parents, 0);
      Arrays.fill(carried, 0);
      Arrays.fill(priority, 0);
      heap.clear();
    }

    /**
     * An array of this many cells for the cost field the next search reads; the same array each
     * time while the size stays the same.
     */
    public int[] costField(int cells) {
      if (costField == null || costField.length != cells) {
        costField = new int[cells];
      }
      return costField;
    }
  }

  /**
   * The estimated remaining cost from a cell to the goal, scaled the same way step costs are.
   *
   * <p>Method 3 is ten times the straight-line cell distance. Methods 1 and 2 are ten times the
   * longer axis plus four times the shorter one, an octile estimate. Method 0, and every method
   * once the accumulated mode is on, is ten times the longer axis alone.
   *
   * @param dx column separation from the goal, either sign
   * @param dy row separation from the goal, either sign
   * @param method which formula to use, 0 to 3
   * @param accumulatedHeuristic whether the older accumulating mode is in force
   * @throws IllegalArgumentException for a method outside 0 to 3
   */
  public static int heuristic(int dx, int dy, int method, boolean accumulatedHeuristic) {
    if (method == 3 && !accumulatedHeuristic) {
      return 10 * FixedMath.isqrt(dx * dx + dy * dy);
    }
    int ax = Math.abs(dx);
    int ay = Math.abs(dy);
    if (accumulatedHeuristic || method == 0) {
      return 10 * Math.max(ax, ay);
    }
    if (method == 1 || method == 2) {
      return 10 * Math.max(ax, ay) + 4 * Math.min(ax, ay);
    }
    throw new IllegalArgumentException("Unknown heuristic method: " + method);
  }

  /**
   * Searches for a route from one cell to another.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param costs row-major cell costs, {@code width * height} entries, -1 for a rejected cell
   * @param start row-major id of the cell the unit stands on
   * @param goal row-major id of the cell it is heading for
   * @param method which heuristic formula to use, 0 to 3
   * @param weight how strongly the heuristic pulls the search toward the goal
   * @param accumulatedHeuristic whether a node carries its weighted priority rather than its plain
   *     accumulated cost into the next step, the older behaviour
   * @param refresh whether an already open node may be given a cheaper way in
   * @param reopen whether an already closed node may be reopened
   * @param budget how many node expansions the search may spend; zero or less is unlimited
   * @return the route, the four counters and whatever is left of the budget
   */
  public static RouteSearchResult search(
      int width,
      int height,
      int[] costs,
      int start,
      int goal,
      int method,
      int weight,
      boolean accumulatedHeuristic,
      boolean refresh,
      boolean reopen,
      int budget) {
    return search(
        width,
        height,
        costs,
        start,
        goal,
        method,
        weight,
        accumulatedHeuristic,
        refresh,
        reopen,
        budget,
        new Buffers());
  }

  /**
   * The same search, in working arrays kept from an earlier one, which it clears first; the answer
   * is the same as with fresh ones.
   *
   * @param buffers the working arrays, used by one search at a time
   * @see #search(int, int, int[], int, int, int, int, boolean, boolean, boolean, int)
   */
  public static RouteSearchResult search(
      int width,
      int height,
      int[] costs,
      int start,
      int goal,
      int method,
      int weight,
      boolean accumulatedHeuristic,
      boolean refresh,
      boolean reopen,
      int budget,
      Buffers buffers) {
    return new Run(
            width,
            height,
            costs,
            start,
            goal,
            method,
            weight,
            accumulatedHeuristic,
            refresh,
            reopen,
            buffers)
        .run(budget);
  }

  /**
   * One search in progress: the four per-cell arrays and the heap, cleared, and the configuration.
   */
  private static final class Run {

    private final int width;
    private final int height;
    private final int[] costs;
    private final int start;
    private final int goal;
    private final int goalCol;
    private final int goalRow;
    private final int method;
    private final int weight;
    private final boolean accumulatedHeuristic;
    private final boolean refresh;
    private final boolean reopen;

    private final int[] states;
    private final int[] parents;
    private final int[] carried;
    private final int[] priority;
    private final int[] counters = new int[4];
    private final RouteSearchHeap heap;

    Run(
        int width,
        int height,
        int[] costs,
        int start,
        int goal,
        int method,
        int weight,
        boolean accumulatedHeuristic,
        boolean refresh,
        boolean reopen,
        Buffers buffers) {
      int cells = width * height;
      if (width <= 0 || height <= 0 || costs.length != cells) {
        throw new IllegalArgumentException(
            "Cost array of " + costs.length + " does not match a " + width + "x" + height + " map");
      }
      if (start < 0 || start >= cells || goal < 0 || goal >= cells) {
        throw new IllegalArgumentException(
            "Start " + start + " or goal " + goal + " is off the map");
      }
      this.width = width;
      this.height = height;
      this.costs = costs;
      this.start = start;
      this.goal = goal;
      this.goalCol = goal % width;
      this.goalRow = goal / width;
      this.method = method;
      this.weight = weight;
      this.accumulatedHeuristic = accumulatedHeuristic;
      this.refresh = refresh;
      this.reopen = reopen;
      buffers.prepare(cells);
      this.states = buffers.states;
      this.parents = buffers.parents;
      this.carried = buffers.carried;
      this.priority = buffers.priority;
      this.parents[start] = -1;
      this.parents[goal] = -1;
      this.heap = buffers.heap;
    }

    RouteSearchResult run(int budget) {
      int remaining = budget;
      // The start is expanded first and only then closed, so its own neighbours see it open.
      expand(start);
      states[start] = CLOSED;
      if (heap.isEmpty()) {
        // Nothing around the start could be entered: the start is the whole route.
        return new RouteSearchResult(Route.of(start), counters, remaining);
      }
      while (!heap.isEmpty()) {
        int node = heap.pop();
        states[node] = CLOSED;
        // The goal is expanded before the termination test, so it is always expanded once.
        expand(node);
        if (remaining > 0) {
          remaining--;
          if (remaining == 0) {
            break;
          }
        }
        if (states[goal] == CLOSED) {
          break;
        }
      }
      return new RouteSearchResult(buildRoute(), counters, remaining);
    }

    /** Offers every neighbour of a node a way in through that node. */
    private void expand(int node) {
      int col = node % width;
      int row = node / width;
      for (int[] neighbour : NEIGHBOURS) {
        int nextCol = col + neighbour[0];
        int nextRow = row + neighbour[1];
        if (nextCol < 0 || nextCol >= width || nextRow < 0 || nextRow >= height) {
          continue;
        }
        int target = nextRow * width + nextCol;
        int cost = costs[target];
        if (cost == -1) {
          continue;
        }
        int state = states[target];
        if (state == CLOSED && !reopen) {
          continue;
        }
        if (state == OPEN && !refresh) {
          continue;
        }
        int accumulated = carried[node] + cost * neighbour[2];
        int estimate =
            accumulated
                + weight
                    * heuristic(goalCol - nextCol, goalRow - nextRow, method, accumulatedHeuristic);
        if (state != UNVISITED && estimate >= priority[target]) {
          continue;
        }
        parents[target] = node;
        carried[target] = accumulatedHeuristic ? estimate : accumulated;
        priority[target] = estimate;
        if (state == OPEN) {
          heap.siftUp(heap.indexOf(target));
          counters[RouteSearchResult.REFRESHED_NODES]++;
        } else {
          states[target] = OPEN;
          heap.add(target);
          counters[
              state == CLOSED ? RouteSearchResult.REOPENED_NODES : RouteSearchResult.NEW_NODES]++;
          counters[RouteSearchResult.PEAK_OPEN_NODES] =
              Math.max(counters[RouteSearchResult.PEAK_OPEN_NODES], heap.size());
        }
      }
    }

    /** Walks the parents back from the goal, giving a goal-first route that excludes the start. */
    private Route buildRoute() {
      Route route = new Route();
      int node = goal;
      while (parents[node] != -1) {
        route.add(node);
        node = parents[node];
        if (route.size() > states.length) {
          throw new IllegalStateException("Route search produced a cycle of parents");
        }
      }
      return route;
    }
  }
}
