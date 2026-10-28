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
        "Settled against the walks of the reference battles, with units deployed on both sides and"
            + " at arbitrary points in the random battles.")
public final class LaneAssignment {

  private LaneAssignment() {
    // Utility class
  }

  /**
   * The road cells of a map in scan order, columns outer and rows inner: three entries a cell, its
   * column, its row and its road id. A cell with no road takes no part in either search, so a
   * search over these alone answers as one over the whole map.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param tiles the map's flag words
   */
  public static int[] roadCells(int width, int height, TileLookup tiles) {
    int[] roads = new int[3 * Math.max(width, 0) * Math.max(height, 0)];
    int size = 0;
    for (int col = 0; col < width; col++) {
      for (int row = 0; row < height; row++) {
        int roadId = tiles.bits(col, row) & TileMap.ROAD_ID_MASK;
        if (roadId >= 1) {
          roads[size++] = col;
          roads[size++] = row;
          roads[size++] = roadId;
        }
      }
    }
    return Arrays.copyOf(roads, size);
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
    return nearestRoad(roadCells(width, height, tiles), col, row);
  }

  /** {@link #nearestRoad(int, int, int, int, TileLookup)} over a map's {@link #roadCells}. */
  private static int nearestRoad(int[] roads, int col, int row) {
    int best = 0;
    int bestDistance = Integer.MAX_VALUE;
    for (int i = 0; i < roads.length; i += 3) {
      int dx = roads[i] - col;
      int dy = roads[i + 1] - row;
      int distance = dy * dy + dx * dx;
      if (distance < bestDistance) {
        best = roads[i + 2];
        bestDistance = distance;
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
    return lane(roadCells(width, height, tiles), halfSource, x, y, referenceX, flag);
  }

  /**
   * {@link #lane(int, int, int, int, int, int, int, TileLookup)} on a tile map, its width the
   * halves' source, over the road cells the map keeps.
   *
   * @param map the arena's tile map
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   * @param referenceX a second position's x in game units, or -1 for the plain search
   * @param flag when its low bit is set the reference is ignored, as if it were -1
   * @return the road id, 0 when the map carries no road
   */
  public static int lane(TileMap map, int x, int y, int referenceX, int flag) {
    return lane(map.roadCells(), map.width(), x, y, referenceX, flag);
  }

  private static int lane(int[] roads, int halfSource, int x, int y, int referenceX, int flag) {
    int col = FixedMath.div(x, TileMap.CELL_UNITS);
    int row = FixedMath.div(y, TileMap.CELL_UNITS);
    if (referenceX == -1 || (flag & 1) != 0) {
      return nearestRoad(roads, col, row);
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

    for (int i = 0; i < roads.length; i += 3) {
      int scanCol = roads[i];
      int scanRow = roads[i + 1];
      int roadId = roads[i + 2];
      int dy2 = (scanRow - row) * (scanRow - row);
      int distanceFromReference = dy2 + (scanCol - referenceCol) * (scanCol - referenceCol);
      int distanceFromPosition = dy2 + (scanCol - col) * (scanCol - col);
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

    if (oppositeHalves && nearestToPosition == nearestToReference) {
      if (nearestToReference == 1) {
        return 2;
      }
      return nearestToReference == 2 ? 1 : nearestToReference;
    }
    return nearestToPosition;
  }
}
