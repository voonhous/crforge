package org.crforge.core.pathfinding.grid;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The two cheap yes/no questions the rest of the movement code asks about a cell.
 *
 * <p>Both answer 1 or 0 rather than a boolean, because they are read straight into the ranking
 * arithmetic of the endpoint scan and of the state code.
 *
 * <p>They are not the same question. The overlay test looks only at the dynamic building overlay
 * and takes cell coordinates; the standing test looks only at the static map and takes <b>world</b>
 * coordinates. Neither consults the other.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Agrees with the reference line for line; held by its own tests, since no"
            + " reference walk ends up asking either question.")
public final class CellTests {

  /** Bits that make a cell unusable to stand on: not placeable and blocked. */
  private static final int STANDING_REJECT_BITS = TileMap.NOT_PLACEABLE_BIT | TileMap.BLOCKED_BIT;

  private CellTests() {
    // Utility class
  }

  /**
   * 1 when a building's footprint covers the given cell, 0 otherwise.
   *
   * <p>A cell counts as covered when dynamic occlusions are enabled, the cell lies inside the grid
   * and the current overlay's value for it has reached the grid's building cost. Cells outside the
   * grid and negative cells answer 0, so this is a "known to be occupied" test and not an "is
   * routable" one.
   *
   * @param grid the arena's routing state
   * @param col cell column
   * @param row cell row
   */
  public static int overlayBlocks(CellGrid grid, int col, int row) {
    if (col < 0 || row < 0) {
      return 0;
    }
    if (grid.dynamicEnabled() == 0) {
      return 0;
    }
    if (col >= grid.getWidth() || row >= grid.getHeight()) {
      return 0;
    }
    int value = grid.getCurrent()[grid.getWidth() * row + col];
    return value >= grid.getBuildingCost() ? 1 : 0;
  }

  /**
   * 1 when a unit may not stand at the given world position, 0 otherwise.
   *
   * <p>A position is rejected when it is negative, at or beyond the arena's extent, on a cell
   * marked not placeable or blocked, or on water. The dynamic overlay plays no part here, so a
   * building's footprint does not make a position unstandable by this test.
   *
   * @param grid the arena's routing state; only its size and static cell map are read
   * @param worldX position along the arena's width in game units
   * @param worldY position along the arena's length in game units
   */
  public static int cellBlocked(CellGrid grid, int worldX, int worldY) {
    if (worldX < 0 || worldY < 0) {
      return 1;
    }
    if (grid.getWidth() * TileMap.CELL_UNITS <= worldX
        || grid.getHeight() * TileMap.CELL_UNITS <= worldY) {
      return 1;
    }
    int col = worldX / TileMap.CELL_UNITS;
    int row = worldY / TileMap.CELL_UNITS;
    int bits = grid.tiles(col, row);
    if ((bits & STANDING_REJECT_BITS) != 0) {
      return 1;
    }
    return (bits & TileMap.WATER_BIT) != 0 ? 1 : 0;
  }
}
