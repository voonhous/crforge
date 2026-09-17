package org.crforge.core.pathfinding.grid;

import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Moving a point off water, used when a unit is pushed or placed onto the river.
 *
 * <p>The point is first clamped 250 units inside every edge of the arena. If its cell is not water
 * it is returned as it stands. Otherwise the neighbourhood is scanned for the nearest dry point:
 * rows 500 units apart around the point's own y and columns from 2250 units left of it to 2750
 * units right of it, all measured at cell centres. The candidate with the smallest approximate
 * distance from the clamped point wins, and the first one found wins a tie. If none qualifies, the
 * clamped point is returned unchanged even though it is on water.
 *
 * <p>A reference y narrows the rows: rows strictly below the point when the reference lies below
 * it, rows above when it lies above, and all eleven rows when the two are equal or when the
 * reference is -1. The caller uses this to keep a unit on the side of the river it came from.
 *
 * <p><b>Packing.</b> The result packs x in the low half and y in the high half, which is the
 * opposite orientation from the endpoint scan's. Always unpack with {@link #unpackX(int)} and
 * {@link #unpackY(int)} rather than by hand.
 */
public final class Relocation {

  /** Distance kept from every arena edge when the point is clamped. */
  private static final int EDGE_MARGIN = 250;

  /** Lowest row offset scanned, in rows of one cell. */
  private static final int ROW_SPAN = 5;

  /** Leftmost column offset scanned, in game units, before the 3250-unit recentring. */
  private static final int COLUMN_SCAN_START = -5500;

  /** Offset added to a scanned column to place it at a cell centre right of the point. */
  private static final int COLUMN_RECENTRE = 3250;

  private Relocation() {
    // Utility class
  }

  /**
   * Moves a point off water, or leaves it where it is.
   *
   * @param width arena width in cells
   * @param height arena height in cells
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   * @param referenceY a second position's y in game units that decides which rows are scanned, or
   *     -1 to scan them all
   * @param water answers 1 for a water cell
   * @return the new position packed as {@code x | (y << 16)}
   */
  public static int relocate(int width, int height, int x, int y, int referenceY, WaterTest water) {
    int clampedX = Math.min(Math.max(x, EDGE_MARGIN), width * TileMap.CELL_UNITS - EDGE_MARGIN);
    int clampedY = Math.min(Math.max(y, EDGE_MARGIN), height * TileMap.CELL_UNITS - EDGE_MARGIN);
    int startCol = FixedMath.div(clampedX, TileMap.CELL_UNITS);
    int startRow = FixedMath.div(clampedY, TileMap.CELL_UNITS);
    if ((water.water(startCol, startRow) & 1) == 0) {
      return pack(clampedX, clampedY);
    }

    int firstRowOffset;
    int lastRowOffset;
    if (referenceY == -1) {
      firstRowOffset = -ROW_SPAN;
      lastRowOffset = ROW_SPAN;
    } else if (clampedY < referenceY) {
      firstRowOffset = 1;
      lastRowOffset = ROW_SPAN;
    } else {
      firstRowOffset = -ROW_SPAN;
      lastRowOffset = clampedY > referenceY ? 0 : ROW_SPAN;
    }

    int best = Integer.MAX_VALUE;
    int bestX = clampedX;
    int bestY = clampedY;
    for (int rowOffset = firstRowOffset; rowOffset <= lastRowOffset; rowOffset++) {
      int candidateY = rowOffset * TileMap.CELL_UNITS + clampedY + EDGE_MARGIN;
      if (candidateY < 0) {
        continue;
      }
      int dy = candidateY - clampedY;
      int candidateRow = candidateY / TileMap.CELL_UNITS;
      for (int dx = COLUMN_SCAN_START; dx < 0; dx += TileMap.CELL_UNITS) {
        int candidateX = clampedX + dx + COLUMN_RECENTRE;
        if (candidateX < 0) {
          continue;
        }
        if (candidateX >= width * TileMap.CELL_UNITS || candidateY >= height * TileMap.CELL_UNITS) {
          continue;
        }
        if ((water.water(candidateX / TileMap.CELL_UNITS, candidateRow) & 1) != 0) {
          continue;
        }
        int distance = FixedMath.approxDistance(dx + COLUMN_RECENTRE, dy);
        if (distance < best) {
          best = distance;
          bestY = candidateY;
          bestX = candidateX;
        }
      }
    }
    return pack(bestX, bestY);
  }

  /** Packs a relocated position as {@code x | (y << 16)}. */
  public static int pack(int x, int y) {
    return x | (y << 16);
  }

  /** The x of a position packed by {@link #relocate}, which lives in the low half. */
  public static int unpackX(int packed) {
    return packed & 0xffff;
  }

  /**
   * The y of a position packed by {@link #relocate}, which lives in the high half. The shift is
   * arithmetic, matching how the movement code reads the value back.
   */
  public static int unpackY(int packed) {
    return packed >> 16;
  }
}
