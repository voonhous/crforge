package org.crforge.core.pathfinding.grid;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Which road (lane) a world position belongs to.
 *
 * <p>The answer is the road id of the road cell nearest to the position's own cell, measured in
 * squared cell distance, with ties going to the first cell in column-major order (columns outer,
 * rows inner). A map with no road cells, or with no columns, answers 0.
 *
 * <p>A second form takes a reference position, which is where a card was dropped. It runs the same
 * nearest-road search a second time from the reference's column and, when both searches agree and
 * the position and the reference lie on opposite halves of the arena in the direction from the
 * position to the reference, swaps the two lane ids so the unit is assigned to the far lane rather
 * than the near one. A reference of -1, or a set flag, skips all of that.
 *
 * <p>A unit's lane is written once when it is created and never recomputed.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled against the lane of all 53 reference walks, deployed at random points"
            + " on both sides.")
public final class LaneAssignment {

  private LaneAssignment() {
    // Utility class
  }

  /**
   * Road id of the road cell nearest to a given cell, 0 when the map carries no road.
   *
   * <p>Distance is squared cell distance. The scan runs columns outer and rows inner and keeps the
   * first cell at the best distance, so a tie is decided by that order and not by direction.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param col the cell to measure from
   * @param row the cell to measure from
   * @param tiles the map's flag words
   */
  public static int nearestRoad(int width, int height, int col, int row, TileLookup tiles) {
    int best = 0;
    int bestDistance = Integer.MAX_VALUE;
    for (int scanCol = 0; scanCol < width; scanCol++) {
      int dx2 = (scanCol - col) * (scanCol - col);
      for (int scanRow = 0; scanRow < height; scanRow++) {
        int roadId = tiles.bits(scanCol, scanRow) & TileMap.ROAD_ID_MASK;
        int distance = (scanRow - row) * (scanRow - row) + dx2;
        if (roadId >= 1 && distance < bestDistance) {
          best = roadId;
          bestDistance = distance;
        }
      }
    }
    return best;
  }

  /**
   * Road id (lane) of a world position.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param halfSource the arena width in cells whose half decides which side of the map a cell is
   *     on; the halving truncates
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   * @param referenceX a second position's x in game units, or -1 for the plain search
   * @param flag when its low bit is set the reference is ignored, as if it were -1
   * @param tiles the map's flag words
   * @return the road id, 0 when the map carries no road or has no columns
   */
  public static int lane(
      int width,
      int height,
      int halfSource,
      int x,
      int y,
      int referenceX,
      int flag,
      TileLookup tiles) {
    if (width < 1) {
      return 0;
    }
    int col = FixedMath.div(x, TileMap.CELL_UNITS);
    int row = FixedMath.div(y, TileMap.CELL_UNITS);
    if (referenceX == -1 || (flag & 1) != 0) {
      return nearestRoad(width, height, col, row, tiles);
    }
    // The position's column is recomputed five units to the right for the halves comparison only.
    int shiftedCol = FixedMath.div(x + 5, TileMap.CELL_UNITS);
    int referenceCol = FixedMath.div(referenceX, TileMap.CELL_UNITS);
    int half = FixedMath.div(halfSource, 2);

    int nearestToPosition = 0;
    int distanceToPosition = Integer.MAX_VALUE;
    int nearestToReference = -1;
    int distanceToReference = Integer.MAX_VALUE;
    boolean oppositeHalves = false;

    for (int scanCol = 0; scanCol < width; scanCol++) {
      int dxPosition2 = (scanCol - col) * (scanCol - col);
      int dxReference2 = (scanCol - referenceCol) * (scanCol - referenceCol);
      for (int scanRow = 0; scanRow < height; scanRow++) {
        int roadId = tiles.bits(scanCol, scanRow) & TileMap.ROAD_ID_MASK;
        if (roadId < 1) {
          continue;
        }
        int dy2 = (scanRow - row) * (scanRow - row);
        int distanceFromReference = dy2 + dxReference2;
        int distanceFromPosition = dy2 + dxPosition2;
        if (distanceFromReference < distanceToReference) {
          nearestToReference = roadId;
          distanceToReference = distanceFromReference;
        }
        // An equal distance still refreshes the halves flag, although it does not take the road.
        boolean closer = distanceFromPosition <= distanceToPosition;
        if (distanceFromPosition < distanceToPosition) {
          nearestToPosition = roadId;
          distanceToPosition = distanceFromPosition;
        }
        if (closer && !oppositeHalves) {
          // Once set the flag is never cleared again.
          if (referenceX > x) {
            oppositeHalves = referenceCol > half && shiftedCol <= half;
          } else if (referenceX == x) {
            oppositeHalves = false;
          } else {
            oppositeHalves = referenceCol < half && shiftedCol >= half;
          }
        }
      }
    }

    if (oppositeHalves && nearestToPosition == nearestToReference) {
      if (nearestToReference == 1) {
        return 2;
      }
      return nearestToReference == 2 ? 1 : nearestToReference;
    }
    return nearestToPosition;
  }
}
