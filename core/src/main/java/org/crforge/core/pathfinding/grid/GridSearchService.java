/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One whole route query, the way route preparation asks for it.
 *
 * <p>Three steps run per query, in this order:
 *
 * <ol>
 *   <li>a cost lookup is built for the unit, from the grid's live cell map and overlay;
 *   <li>the wrapper validates the start and, when asked, moves an unusable goal to the nearest
 *       usable cell. If it gives up, the query answers an empty route and no search runs;
 *   <li>the search runs over the cost field the lookup gives with the standard game's settings: the
 *       published heuristic method and weight, the accumulating mode off while the newer route
 *       search is in force, open nodes refreshed, closed nodes not reopened, and no expansion
 *       budget.
 * </ol>
 *
 * <p>The search prices each cell from the lookup the first time it reads the cell, so only the
 * cells around the route are priced; nothing changes the cell map or the overlay while it runs, so
 * the answer is the one over the whole field. The costs are priced again on every query rather than
 * cached, because the overlay they read changes from tick to tick.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: cost field, wrapper and search composed with the standard settings, held by the"
            + " walks of the reference battles; the river jump's water permission held by the"
            + " reference battles card_HogRider and grid_zap_on_hog_river_jump. Supplied: the"
            + " water permissions are passed in by the caller, and no caller passes a hovering"
            + " unit's.")
public final class GridSearchService {

  private GridSearchService() {
    // Utility class
  }

  /**
   * Routes a unit from one cell to another.
   *
   * @param grid the arena's routing state, read as it stands
   * @param costs the six cost weights
   * @param state the unit's state, which decides whether roads are priced at all
   * @param lane the road id the unit is assigned to
   * @param waterPermission whether the unit hovers, which lets it enter water
   * @param alternateWaterPermission whether the unit's row jumps the river, which also lets it
   *     enter water
   * @param startCol the cell the unit stands on
   * @param startRow the cell the unit stands on
   * @param goalCol the cell it is heading for
   * @param goalRow the cell it is heading for
   * @param adjust when its low bit is set, an unusable goal is moved to the nearest usable cell
   * @return the route, goal first with the next waypoint last, or an empty route when there is none
   */
  public static Route route(
      CellGrid grid,
      CellCosts costs,
      int state,
      int lane,
      boolean waterPermission,
      boolean alternateWaterPermission,
      int startCol,
      int startRow,
      int goalCol,
      int goalRow,
      int adjust) {
    return search(
            grid,
            costs,
            state,
            lane,
            waterPermission,
            alternateWaterPermission,
            startCol,
            startRow,
            goalCol,
            goalRow,
            adjust)
        .route();
  }

  /**
   * The same query as {@link #route}, keeping the search's counters and remaining budget as well.
   * When the wrapper gives up, the result holds an empty route, zeroed counters and the budget
   * unspent.
   */
  public static RouteSearchResult search(
      CellGrid grid,
      CellCosts costs,
      int state,
      int lane,
      boolean waterPermission,
      boolean alternateWaterPermission,
      int startCol,
      int startRow,
      int goalCol,
      int goalRow,
      int adjust) {
    CellCostLookup lookup =
        CellCostField.costLookup(
            grid, costs, state, lane, waterPermission, alternateWaterPermission);
    RouteSearchWrapper.Nodes nodes =
        RouteSearchWrapper.searchWrapper(
            grid.getWidth(),
            grid.getHeight(),
            lookup,
            startCol,
            startRow,
            goalCol,
            goalRow,
            adjust);
    if (nodes == null) {
      return new RouteSearchResult(new Route(), new int[4], UNLIMITED_BUDGET);
    }
    // The grid's own working arrays: a search clears them first, and nothing it answers refers to
    // them, so every search of the battle reuses them.
    RouteSearch.Buffers buffers = grid.getSearchBuffers();
    return RouteSearch.search(
        grid.getWidth(),
        grid.getHeight(),
        lookup,
        nodes.startNode(),
        nodes.goalNode(),
        PathfindingGlobals.PATHFINDING_HEURISTIC_METHOD,
        PathfindingGlobals.PATHFINDING_DEFAULTHEURISTIC_COST,
        !PathfindingGlobals.NEW_PATHFINDING_CODE,
        PathfindingGlobals.PATHFINDING_REFRESH_OPENNODES,
        PathfindingGlobals.PATHFINDING_REOPEN_CLOSEDNODES,
        UNLIMITED_BUDGET,
        buffers);
  }

  /** The budget route preparation passes: none, so the search expands as many nodes as it needs. */
  private static final int UNLIMITED_BUDGET = 0;
}
