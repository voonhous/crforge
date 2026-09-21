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
 *   <li>the whole cost field is materialised and the search runs over it with the standard game's
 *       settings: the published heuristic method and weight, the accumulating mode off while the
 *       newer route search is in force, open nodes refreshed, closed nodes not reopened, and no
 *       expansion budget.
 * </ol>
 *
 * <p>The cost field is rebuilt on every query rather than cached, because the overlay it reads
 * changes from tick to tick.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: cost field, wrapper and search composed with the standard settings,"
            + " held by the 53 reference walks. Supplied: both water permissions are passed in"
            + " by the caller, and every caller passes false.")
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
   * @param waterPermission whether the unit may enter water
   * @param alternateWaterPermission the second per-unit water permission
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
    int[] field =
        CellCostField.costField(
            grid, costs, state, lane, waterPermission, alternateWaterPermission);
    return RouteSearch.search(
        grid.getWidth(),
        grid.getHeight(),
        field,
        nodes.startNode(),
        nodes.goalNode(),
        PathfindingGlobals.PATHFINDING_HEURISTIC_METHOD,
        PathfindingGlobals.PATHFINDING_DEFAULTHEURISTIC_COST,
        !PathfindingGlobals.NEW_PATHFINDING_CODE,
        PathfindingGlobals.PATHFINDING_REFRESH_OPENNODES,
        PathfindingGlobals.PATHFINDING_REOPEN_CLOSEDNODES,
        UNLIMITED_BUDGET);
  }

  /** The budget route preparation passes: none, so the search expands as many nodes as it needs. */
  private static final int UNLIMITED_BUDGET = 0;
}
