package org.crforge.core.pathfinding.grid;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * What one cell costs a unit to enter, and the whole row-major cost array the route search reads.
 *
 * <p>The rule is a fixed chain of tests and the order matters, because an earlier branch hides a
 * later one:
 *
 * <ol>
 *   <li>A cell outside the map costs -1, which rejects it outright.
 *   <li>A water cell costs the water weight when a unit is supplied and either of its two water
 *       permissions is set, and the blocked weight otherwise. This is tested before the blocked
 *       bit, so a cell that is both water and blocked follows the water rule.
 *   <li>A cell with the blocked bit costs the blocked weight.
 *   <li>A unit in one of the three pathfinding states costs every remaining cell the default
 *       weight. This returns before the road rule, so roads give a pathfinding unit no discount.
 *   <li>A cell carrying a road costs the matching-road weight when a unit is supplied and its lane
 *       equals that road, the plain road weight otherwise; a cell with no road costs the default
 *       weight.
 *   <li>While the overlay is active the answer is raised to the overlay's own value for that cell -
 *       a maximum, not a sum, so an overlay below the base cost changes nothing.
 * </ol>
 *
 * <p>"A unit is supplied" is the {@code entityPresent} argument: the same rule is also asked about
 * a bare cell, and then the water permission, the lane and the pathfinding states play no part.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled against the five reference walks: the two that head for the king tower"
            + " first move when a cost does. The water-permission branches and the blocked bit"
            + " are not reached by a ground unit on the standard map and are held by its own"
            + " tests only.")
public final class CellCostField {

  private CellCostField() {
    // Utility class
  }

  /**
   * Cost of one cell for one unit.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param col cell column
   * @param row cell row
   * @param tileBits the cell's static routing flag word, as {@link TileMap#bits(int, int)} answers
   *     it
   * @param costs the six cost weights
   * @param entityPresent whether a unit is being priced, rather than the bare cell
   * @param waterPermission whether that unit may enter water
   * @param alternateWaterPermission a second per-unit flag that also permits water; its writers are
   *     not established, so the name stays neutral
   * @param state the unit's state, one of the constants in {@link GridEntityState}
   * @param lane the road id the unit is assigned to, 0 for none
   * @param dynamicActive whether the building overlay has been built for this tick
   * @param occlusionCost the overlay's value for this cell
   * @return the cost, or -1 when the cell is outside the map
   */
  public static int cellCost(
      int width,
      int height,
      int col,
      int row,
      int tileBits,
      CellCosts costs,
      boolean entityPresent,
      boolean waterPermission,
      boolean alternateWaterPermission,
      int state,
      int lane,
      boolean dynamicActive,
      int occlusionCost) {
    if (col < 0 || row < 0 || col >= width || row >= height) {
      return -1;
    }
    if ((tileBits & TileMap.WATER_BIT) != 0) {
      boolean mayEnterWater = entityPresent && (waterPermission || alternateWaterPermission);
      return mayEnterWater ? costs.waterCost() : costs.blockedCost();
    }
    if ((tileBits & TileMap.BLOCKED_BIT) != 0) {
      return costs.blockedCost();
    }
    if (entityPresent && isPathfindingState(state)) {
      return costs.defaultCost();
    }
    int roadId = tileBits & TileMap.ROAD_ID_MASK;
    int cost;
    if (roadId != 0) {
      cost = entityPresent && lane == roadId ? costs.matchingRoadCost() : costs.roadCost();
    } else {
      cost = costs.defaultCost();
    }
    return dynamicActive ? Math.max(cost, occlusionCost) : cost;
  }

  /**
   * The three states in which every cell costs the default weight: the two pathfinding states and
   * the second route-following state.
   */
  private static boolean isPathfindingState(int state) {
    return state == GridEntityState.SPAWN_PATHFIND
        || state == GridEntityState.INGAME_PATHFIND
        || state == GridEntityState.ROUTE_FOLLOWING_ALTERNATE;
  }

  /**
   * A cost lookup over a grid for one unit, reading the grid's live tile map and overlay on every
   * call. It answers -1 outside the map, which is what the search wrapper's goal adjustment tests
   * for.
   *
   * @param grid the arena's routing state; its overlay is read as it stands at each call
   * @param costs the six cost weights
   * @param state the unit's state
   * @param lane the road id the unit is assigned to
   * @param waterPermission whether the unit may enter water
   * @param alternateWaterPermission the second water permission flag
   */
  public static CellCostLookup costLookup(
      CellGrid grid,
      CellCosts costs,
      int state,
      int lane,
      boolean waterPermission,
      boolean alternateWaterPermission) {
    int width = grid.getWidth();
    int height = grid.getHeight();
    return (col, row) -> {
      boolean inside = col >= 0 && row >= 0 && col < width && row < height;
      int tileBits = inside ? grid.tiles(col, row) : 0;
      int occlusionCost = inside ? grid.getCurrent()[row * width + col] : 0;
      return cellCost(
          width,
          height,
          col,
          row,
          tileBits,
          costs,
          true,
          waterPermission,
          alternateWaterPermission,
          state,
          lane,
          grid.getActive() != 0,
          occlusionCost);
    };
  }

  /**
   * The whole cost array for one unit, row-major with one entry per cell, which is what the route
   * search consumes. A fresh array is built on every call, as the search does one per query.
   *
   * @see #costLookup(CellGrid, CellCosts, int, int, boolean, boolean)
   */
  public static int[] costField(
      CellGrid grid,
      CellCosts costs,
      int state,
      int lane,
      boolean waterPermission,
      boolean alternateWaterPermission) {
    CellCostLookup lookup =
        costLookup(grid, costs, state, lane, waterPermission, alternateWaterPermission);
    int width = grid.getWidth();
    int[] field = new int[width * grid.getHeight()];
    for (int i = 0; i < field.length; i++) {
      field[i] = lookup.cost(i % width, i / width);
    }
    return field;
  }
}
