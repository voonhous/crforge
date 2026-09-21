package org.crforge.core.pathfinding.grid;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Which cell a unit walks to in order to reach its target.
 *
 * <p>This is not where the unit will stand when it attacks; it is the cell the route search is
 * asked to reach. The scan covers a square of cells around the target's own cell, reaching {@code
 * range / 500 + 1} cells each way and clipped to the map. Rows are visited from low to high. Within
 * a row, columns are visited left to right while the unit stands strictly left of the arena's
 * centre line and right to left otherwise, so a unit exactly on the centre line scans right to
 * left.
 *
 * <p>A cell qualifies when the cell-acceptance test takes it and its centre is within {@code range}
 * of the target, measured centre to centre. Qualifying cells are then ranked:
 *
 * <ul>
 *   <li>rank 2, the preferred rank, for an ordinary cell;
 *   <li>rank 1 for a cell the unit would rather not stop on.
 * </ul>
 *
 * Which cells fall to rank 1 depends on two published rules and on a per-unit flag. For a ground
 * unit water is rank 1, and so is a cell covered by a building footprint while the
 * ground-avoid-buildings rule is on. For a unit whose flag is set, the flying-no-water rule being
 * on makes every cell rank 2; with that rule off, water is rank 1 again. A higher rank always wins;
 * within a rank the cell closest to the <b>unit</b> wins, and the first cell reached wins a tie.
 *
 * <p><b>Rank 1 is a preference, not a rejection.</b> The acceptance test that could reject a cell
 * is asked with a flag that makes it take every cell inside the map, which is what {@link
 * #everyCellInBounds(int, int)} supplies.
 *
 * <p>The result packs the column in the high half and the row in the low half, which is the
 * opposite orientation from a relocated position's. Unpack with {@link #unpackCol(int)} and {@link
 * #unpackRow(int)}.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled against the endpoint of every route preparation of the five reference"
            + " walks, in both scan directions. All five are one unit with one attack range,"
            + " so other ranges are held by its own tests alone. Which cells are acceptable at"
            + " all is asked of the caller, and the caller accepts every cell on the map.")
public final class ReferenceEndpoint {

  /** The preferred rank: a cell with nothing against it. */
  private static final int RANK_PLAIN = 2;

  /** The rank of a cell the unit would rather not stop on. */
  private static final int RANK_AVOIDED = 1;

  private ReferenceEndpoint() {
    // Utility class
  }

  /**
   * Where the caller of {@link #referenceEndpointAndFallback(int, int, int)} was told to go.
   *
   * <p>The two numbers are cell coordinates on the ordinary branch but raw <b>world</b> coordinates
   * on the fallback branch, which is why the branch is reported rather than hidden.
   *
   * @param x column, or a world x on the fallback branch
   * @param y row, or a world y on the fallback branch
   * @param fallback true when no endpoint was found and the target's own position was handed back
   */
  public record Destination(int x, int y, boolean fallback) {}

  /**
   * The cell acceptance the routing code actually supplies: every cell inside the map is accepted.
   *
   * <p>It is a parameter rather than a constant because the same scan is also asked with an
   * acceptance that does reject cells; passing this one documents that the live behaviour rejects
   * nothing.
   */
  public static CellPredicate everyCellInBounds(int width, int height) {
    return (col, row) -> col >= 0 && row >= 0 && col < width && row < height ? 1 : 0;
  }

  /**
   * Chooses the cell a unit should walk to in order to reach its target.
   *
   * @param width map width in cells
   * @param height map height in cells
   * @param unitX the unit's position along the arena's width in game units
   * @param unitY the unit's position along the arena's length in game units
   * @param targetCol the target's own cell
   * @param targetRow the target's own cell
   * @param range how far the unit can reach, in game units; usually its attack range
   * @param avoidanceRuleFlag a per-unit flag that selects which of the two preference rules below
   *     applies. Its writers are not established, so the name stays neutral; the rules it selects
   *     between are the flying and the ground ones.
   * @param flyingNoWater the published flying-no-water rule
   * @param groundAvoidBuildings the published ground-avoid-buildings rule
   * @param accepted whether a cell may be considered at all; see {@link #everyCellInBounds(int,
   *     int)}
   * @param targetDistanceSquared squared distance from a point to the target
   * @param waterBit 1 for a water cell
   * @param overlayBlocked 1 for a cell covered by a building footprint
   * @param visitor told about each cell reached, or null
   * @return the chosen cell packed as {@code (col << 16) | row}, or -1 when no cell qualified
   */
  public static int selectEndpoint(
      int width,
      int height,
      int unitX,
      int unitY,
      int targetCol,
      int targetRow,
      int range,
      boolean avoidanceRuleFlag,
      boolean flyingNoWater,
      boolean groundAvoidBuildings,
      CellPredicate accepted,
      TargetDistanceSquared targetDistanceSquared,
      CellPredicate waterBit,
      CellPredicate overlayBlocked,
      CellVisitor visitor) {
    int extent = range / TileMap.CELL_UNITS + 1;
    int lowCol = Math.max(targetCol - extent, 0);
    int highCol = Math.min(targetCol + extent, width - 1);
    int lowRow = Math.max(targetRow - extent, 0);
    int highRow = Math.min(targetRow + extent, height - 1);
    boolean useWaterRank = !avoidanceRuleFlag || !flyingNoWater;
    boolean useOverlayRank = !avoidanceRuleFlag && groundAvoidBuildings;
    // A unit exactly on the centre line scans right to left, because the test is strict.
    boolean leftToRight = unitX < width * (TileMap.CELL_UNITS / 2);

    int best = -1;
    int bestRank = 0;
    int bestDistance = Integer.MAX_VALUE;
    for (int row = lowRow; row <= highRow; row++) {
      int columns = highCol - lowCol + 1;
      for (int step = 0; step < columns; step++) {
        int col = leftToRight ? lowCol + step : highCol - step;
        if (visitor != null) {
          visitor.visit(col, row);
        }
        if (accepted.test(col, row) == 0) {
          continue;
        }
        int centreX = col * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
        int centreY = row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
        if (targetDistanceSquared.distanceSquared(centreX, centreY) > range * range) {
          continue;
        }
        int distance =
            (centreX - unitX) * (centreX - unitX) + (centreY - unitY) * (centreY - unitY);
        int rank;
        if (useWaterRank) {
          if (waterBit.test(col, row) != 0) {
            rank = RANK_AVOIDED;
          } else if (useOverlayRank && overlayBlocked.test(col, row) != 0) {
            rank = RANK_AVOIDED;
          } else {
            rank = RANK_PLAIN;
          }
        } else {
          rank = useOverlayRank && overlayBlocked.test(col, row) != 0 ? RANK_AVOIDED : RANK_PLAIN;
        }
        if (rank > bestRank || (rank == bestRank && distance < bestDistance)) {
          best = (col << 16) | row;
          bestRank = rank;
          bestDistance = distance;
        }
      }
    }
    if (bestDistance == Integer.MAX_VALUE) {
      return -1;
    }
    // The cell that won is offered to the acceptance test once more and the answer is discarded.
    // It is kept because the test may be stateful.
    accepted.test(best >> 16, best & 0xffff);
    return best;
  }

  /**
   * Turns a packed endpoint into the destination its caller uses.
   *
   * <p>A negative packed value means "no endpoint was found". The caller then reads the target's
   * <b>world</b> position into the same two registers the cell would have gone into, without
   * converting it to cells. That is surprising, and it is what the standard game does, so it is not
   * normalised here; {@link Destination#fallback()} says which branch was taken.
   *
   * @param packed the answer of {@link #selectEndpoint}
   * @param targetWorldX the target's position along the arena's width in game units
   * @param targetWorldY the target's position along the arena's length in game units
   */
  public static Destination referenceEndpointAndFallback(
      int packed, int targetWorldX, int targetWorldY) {
    if (packed < 0) {
      return new Destination(targetWorldX, targetWorldY, true);
    }
    return new Destination(unpackCol(packed), unpackRow(packed), false);
  }

  /** The column of a packed endpoint, which lives in the high half. */
  public static int unpackCol(int packed) {
    return packed >> 16;
  }

  /** The row of a packed endpoint, which lives in the low half. */
  public static int unpackRow(int packed) {
    return packed & 0xffff;
  }
}
