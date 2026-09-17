package org.crforge.core.pathfinding.grid;

/**
 * The six per-cell weights the route search charges, as one immutable set.
 *
 * <p>A cell's weight is multiplied by the step factor of the move that enters it (10 for a cardinal
 * step, 14 for a diagonal one), so these are relative numbers rather than distances. The blocked
 * weight is not a rejection: a blocked cell can still be crossed, it simply costs twenty times an
 * ordinary one, which is what makes a unit walk to a bridge rather than into the river.
 *
 * <p>The components are listed in the order the published data lists them.
 *
 * @param waterCost weight of a water cell for a unit that may enter water
 * @param blockedCost weight of a cell the unit cannot ordinarily cross
 * @param buildingCost weight stamped over the cells a building occupies
 * @param defaultCost weight of an ordinary cell carrying no road
 * @param roadCost weight of a cell carrying a road the unit is not assigned to
 * @param matchingRoadCost weight of a cell carrying the road the unit is assigned to
 */
public record CellCosts(
    int waterCost,
    int blockedCost,
    int buildingCost,
    int defaultCost,
    int roadCost,
    int matchingRoadCost) {

  /** The costs of the standard game, taken from {@link PathfindingGlobals}. */
  public static CellCosts standard() {
    return new CellCosts(
        PathfindingGlobals.PATHFINDING_WATER_COST,
        PathfindingGlobals.PATHFINDING_BLOCKED_COST,
        PathfindingGlobals.PATHFINDING_BUILDING_COST,
        PathfindingGlobals.PATHFINDING_DEFAULT_COST,
        PathfindingGlobals.PATHFINDING_ROAD_COST,
        PathfindingGlobals.PATHFINDING_MATCHINGROAD_COST);
  }
}
