package org.crforge.core.battle.spawn;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * The in-front test: may a spawned unit stand at a point?
 *
 * <p>It is written as a square of half side {@code r} around the point, refused when the square
 * leaves the arena or overlaps a water cell. But {@code r} is the collision radius only when that
 * is below 1, and 1 otherwise, so for every real unit the square is the unit square just below and
 * left of the point: the cell under the point, and its left or lower neighbour when the point lies
 * exactly on a cell edge. Nothing else is looked at: not buildings, not the footprint overlay, not
 * whether the unit flies.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases, on the standard arena and on grids of their own.")
public final class SpawnPassable {

  /** The flag word of one cell, by column and row. */
  @FunctionalInterface
  public interface CellFlags {

    /** The cell's flag word. */
    int flags(int column, int row);
  }

  private SpawnPassable() {
    // Utility class
  }

  /** The in-front test on an arena's cell map. */
  public static boolean passable(TileMap map, int x, int y, int collisionRadius) {
    return passable(map.width(), map.height(), map::bits, x, y, collisionRadius);
  }

  /**
   * The in-front test on a grid.
   *
   * @param width the grid's width in cells
   * @param height the grid's height in cells
   * @param cells each cell's flag word
   * @param x the point along the width, in game units
   * @param y the point along the length, in game units
   * @param collisionRadius the unit's collision radius
   */
  public static boolean passable(
      int width, int height, CellFlags cells, int x, int y, int collisionRadius) {
    int r = collisionRadius < 1 ? collisionRadius : 1;
    int lowX = x - r;
    int lowY = y - r;
    if (lowX < 0 || lowY < 0) {
      return false;
    }
    int highX = r + x;
    if (highX >= width * TileMap.CELL_UNITS) {
      return false;
    }
    int highY = r + y;
    if (highY >= height * TileMap.CELL_UNITS) {
      return false;
    }
    int firstColumn = lowX / TileMap.CELL_UNITS;
    int lastColumn = (highX - 1) / TileMap.CELL_UNITS;
    if (firstColumn > lastColumn) {
      return true;
    }
    int firstRow = lowY / TileMap.CELL_UNITS;
    int lastRow = (highY - 1) / TileMap.CELL_UNITS;
    if (firstRow > lastRow) {
      return true;
    }
    for (int column = firstColumn; column <= lastColumn; column++) {
      for (int row = firstRow; row <= lastRow; row++) {
        if (width <= column || height <= row) {
          return false;
        }
        if ((cells.flags(column, row) & TileMap.WATER_BIT) != 0) {
          return false;
        }
      }
    }
    return true;
  }
}
