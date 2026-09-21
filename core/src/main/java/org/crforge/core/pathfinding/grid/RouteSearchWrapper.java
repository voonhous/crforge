package org.crforge.core.pathfinding.grid;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * What happens between "route me from this cell to that cell" and the route search itself.
 *
 * <p>Three things, in order: the start cell is validated, a goal the unit could not enter is moved
 * to the nearest cell it can, and both cells are turned into row-major node ids. If any of the
 * three fails the caller is told there is no route rather than being handed a search that cannot
 * succeed.
 *
 * <p>The goal adjustment scans a square window around the goal whose half-size is the straight-line
 * cell distance from the start to the goal, clipped to the map. Inside that window it keeps the
 * usable cell closest to the original goal in squared cell distance, rows ascending and columns
 * ascending, first one winning a tie. A window with no width or no height, or with no usable cell
 * in it, gives up.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Start validation and the goal adjustment window agree with the reference line"
            + " for line, and every search of the five reference walks goes through it. The"
            + " window's clipped edges and an off-map goal are held by its own tests only.")
public final class RouteSearchWrapper {

  private RouteSearchWrapper() {
    // Utility class
  }

  /**
   * The two row-major node ids a route search is started with.
   *
   * @param startNode the cell the unit stands on
   * @param goalNode the cell it is routed to, after any adjustment
   */
  public record Nodes(int startNode, int goalNode) {}

  /**
   * Whether a unit may start a route from the given cell.
   *
   * <p>Route preparation always asks with a flag of zero, and then only the bounds test applies:
   * every cell inside the map is a valid start, water and building footprints included. With the
   * low bit of the flag set the cell's water bit rejects the start instead, unless the shortcut
   * applies.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param col cell column
   * @param row cell row
   * @param flags caller flags; only the low bit is read
   * @param waterBit answers 1 for a water cell, needed only when the low flag bit is set
   * @param shortcut a per-unit exemption that accepts the cell before the water bit is read
   */
  public static boolean startIsValid(
      int width, int height, int col, int row, int flags, WaterTest waterBit, boolean shortcut) {
    if (col < 0 || row < 0 || col >= width || row >= height) {
      return false;
    }
    if (shortcut) {
      return true;
    }
    if ((flags & 1) != 0) {
      return waterBit.water(col, row) == 0;
    }
    return true;
  }

  /**
   * Validates the start, adjusts the goal and converts both to node ids.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param cost the unit's cost lookup, which answers -1 for a cell it may not enter
   * @param startCol the cell the unit stands on
   * @param startRow the cell the unit stands on
   * @param goalCol the cell it wants to reach
   * @param goalRow the cell it wants to reach
   * @param adjust when its low bit is set, an unusable goal is moved to the nearest usable cell
   * @return the two node ids, or null when there is no route to look for
   */
  public static Nodes searchWrapper(
      int width,
      int height,
      CellCostLookup cost,
      int startCol,
      int startRow,
      int goalCol,
      int goalRow,
      int adjust) {
    return searchWrapper(
        width,
        height,
        cost,
        startCol,
        startRow,
        goalCol,
        goalRow,
        adjust,
        startIsValid(width, height, startCol, startRow, 0, null, false));
  }

  /**
   * The same as {@link #searchWrapper(int, int, CellCostLookup, int, int, int, int, int)} with the
   * start validity decided by the caller.
   *
   * @param startValid the answer of the start validation for this unit and cell
   */
  public static Nodes searchWrapper(
      int width,
      int height,
      CellCostLookup cost,
      int startCol,
      int startRow,
      int goalCol,
      int goalRow,
      int adjust,
      boolean startValid) {
    if (!startValid) {
      return null;
    }
    int col = goalCol;
    int row = goalRow;
    if (cost.cost(col, row) == -1 && (adjust & 1) != 0) {
      int reach =
          FixedMath.isqrt(
              (col - startCol) * (col - startCol) + (row - startRow) * (row - startRow));
      int lowCol = clipWindowEdge(col - reach, width);
      int lowRow = clipWindowEdge(row - reach, height);
      int highCol = clipWindowEdge(col + reach, width);
      int highRow = clipWindowEdge(row + reach, height);
      if (highCol <= lowCol || highRow <= lowRow) {
        return null;
      }
      int best = Integer.MAX_VALUE;
      int bestCol = -1;
      int bestRow = -1;
      for (int scanRow = lowRow; scanRow < highRow; scanRow++) {
        for (int scanCol = lowCol; scanCol < highCol; scanCol++) {
          int distance = (scanCol - col) * (scanCol - col) + (scanRow - row) * (scanRow - row);
          if (cost.cost(scanCol, scanRow) != -1 && distance < best) {
            best = distance;
            bestCol = scanCol;
            bestRow = scanRow;
          }
        }
      }
      if (bestCol == -1) {
        return null;
      }
      col = bestCol;
      row = bestRow;
    }
    if (cost.cost(col, row) == -1) {
      return null;
    }
    return new Nodes(startRow * width + startCol, row * width + col);
  }

  /**
   * Clips one edge of the adjustment window: an edge at or below zero becomes zero, otherwise it is
   * capped at the map's extent.
   */
  private static int clipWindowEdge(int edge, int extent) {
    return edge > 0 ? Math.min(edge, extent) : 0;
  }
}
