package org.crforge.core.pathfinding.grid;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Builds the dynamic cost overlay: the per-tick layer that makes a building's cells expensive to
 * route through.
 *
 * <p>The build runs once per tick, before any component pass, over the match's live entity list in
 * creation order. Every entity that is of the routing virtual type and answers that it occludes has
 * a square box stamped around it at the grid's building cost, and its id is folded into a running
 * hash of its own side. Afterwards each side's change flag says whether that side's hash differs
 * from the previous build's, which is what route retention consults to decide whether a cached
 * route may be kept.
 *
 * <p>The flags therefore react to <b>which</b> occluders exist and in what order, not to where they
 * are: a building that appears or disappears raises its side's flag for one tick, while a building
 * that merely moves does not.
 *
 * <p>The end-of-tick rotation that turns the overlay just built into the previous one is {@link
 * CellGrid#swap()} and belongs to the tick driver, not to this class.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled against the 53 reference walks, every one of which routes around tower"
            + " footprints stamped here, and against the per-side change flags the reference"
            + " route retention reads. Only towers occlude in any reference; the rasteriser's"
            + " handling of a moving occluder is held by its own tests.")
public final class FootprintOverlay {

  /** Virtual type of the entities the build considers; everything else is skipped. */
  private static final int ROUTING_ENTITY_TYPE = 5;

  /** Number of sides that carry a hash and a change flag. */
  private static final int SIDES = 2;

  /** How far the running hash is rotated right before each id is folded in. */
  private static final int HASH_ROTATION = 31;

  /** Constant added to every id folded into the running hash. */
  private static final int HASH_ID_OFFSET = 9;

  private FootprintOverlay() {
    // Utility class
  }

  /**
   * Stamps one square box into the current overlay and records its cell bounds.
   *
   * <p>The box is anchored on the cell edge at or after the position, not on the position itself,
   * so two entities less than a cell apart can stamp the same cells. From that anchor the box
   * reaches {@code halfWidth} to the left and right and {@code halfHeight} up and down. If any side
   * of the box would leave the grid nothing is stamped at all, not even the part that fits, and the
   * footprint is not recorded.
   *
   * <p>Cells take the larger of their present value and {@code cost}, so overlapping footprints do
   * not add up.
   *
   * @param grid the arena's routing state; its current overlay and footprint list are written
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   * @param halfWidth half the box's width in game units
   * @param halfHeight half the box's height in game units
   * @param cost the value stamped into each covered cell
   * @return the packed cell bounds of the stamped box, or null when the box leaves the grid
   */
  public static Integer rasterize(
      CellGrid grid, int x, int y, int halfWidth, int halfHeight, int cost) {
    int width = grid.getWidth();
    int height = grid.getHeight();
    int anchorX =
        FixedMath.div(x - 1, TileMap.CELL_UNITS) * TileMap.CELL_UNITS + TileMap.CELL_UNITS;
    int lowX = anchorX - halfWidth;
    if (lowX < 0) {
      return null;
    }
    int anchorY =
        FixedMath.div(y - 1, TileMap.CELL_UNITS) * TileMap.CELL_UNITS + TileMap.CELL_UNITS;
    int lowY = anchorY - halfHeight;
    if (lowY < 0) {
      return null;
    }
    int highX = anchorX + halfWidth;
    if (highX >= width * TileMap.CELL_UNITS) {
      return null;
    }
    int highY = anchorY + halfHeight;
    if (highY >= height * TileMap.CELL_UNITS) {
      return null;
    }
    int lastCol = FixedMath.div(highX - 1, TileMap.CELL_UNITS);
    int firstCol = lowX / TileMap.CELL_UNITS;
    int firstRow = lowY / TileMap.CELL_UNITS;
    int lastRow = FixedMath.div(highY - 1, TileMap.CELL_UNITS);
    int packed = packFootprint(firstCol, lastCol, firstRow, lastRow);
    grid.getFootprints().add(packed);
    if (firstRow > lastRow || firstCol > lastCol) {
      return packed;
    }
    int[] overlay = grid.getCurrent();
    for (int row = firstRow; row <= lastRow; row++) {
      for (int col = firstCol; col <= lastCol; col++) {
        int index = row * width + col;
        if (cost > overlay[index]) {
          overlay[index] = cost;
        }
      }
    }
    return packed;
  }

  /**
   * Rebuilds the overlay for one tick from the match's live entity list.
   *
   * <p>The list must be in creation order, because the per-side hash folds ids in that order and
   * two different orders of the same ids hash differently.
   *
   * <p>When dynamic occlusions are disabled nothing is stamped, the overlay stays inactive and both
   * hashes are set to zero - which itself raises the change flags once, on the first build after
   * the feature was last on.
   *
   * @param grid the arena's routing state; its current overlay, footprints, hashes, change flags,
   *     footprint counter and active flag are written
   * @param entities the match's entities in creation order
   * @return the footprints stamped by this build, in entity order, excluding entities whose box
   *     left the grid
   */
  public static List<Integer> buildOverlay(CellGrid grid, List<GridEntity> entities) {
    grid.setFootprints(new ArrayList<>());
    List<Integer> stamped = new ArrayList<>();
    int[] hashes = new int[SIDES];
    if (grid.dynamicEnabled() == 0) {
      updateFlags(grid, hashes);
      return stamped;
    }
    grid.setFootprintCounter(0);
    grid.setActive(1);
    for (GridEntity entity : entities) {
      if (entity.getType() != ROUTING_ENTITY_TYPE) {
        continue;
      }
      if (!entity.isOccludes()) {
        continue;
      }
      int radius = entity.getCollisionRadius();
      Integer packed =
          rasterize(grid, entity.getX(), entity.getY(), radius, radius, grid.getBuildingCost());
      if (packed != null) {
        stamped.add(packed);
      }
      int side = entity.getSide();
      if (Integer.compareUnsigned(side, SIDES - 1) <= 0) {
        hashes[side] =
            Integer.rotateRight(hashes[side], HASH_ROTATION) + entity.getId() + HASH_ID_OFFSET;
      }
    }
    updateFlags(grid, hashes);
    return stamped;
  }

  /** Stores the new per-side hashes and raises the change flag of every side whose hash moved. */
  private static void updateFlags(CellGrid grid, int[] hashes) {
    int[] previousHashes = grid.getHash();
    int[] changed = new int[SIDES];
    for (int side = 0; side < SIDES; side++) {
      changed[side] = hashes[side] != previousHashes[side] ? 1 : 0;
    }
    grid.setHash(new int[] {hashes[0], hashes[1]});
    grid.setChanged(changed);
  }

  /**
   * Packs the cell bounds of a footprint. The four bytes are, from the top, the first column, the
   * last column, the first row and the last row.
   */
  public static int packFootprint(int firstCol, int lastCol, int firstRow, int lastRow) {
    return (lastCol << 16) | (firstCol << 24) | (firstRow << 8) | lastRow;
  }

  /** Leftmost column a packed footprint covers. */
  public static int footprintFirstCol(int packed) {
    return (packed >>> 24) & 0xff;
  }

  /** Rightmost column a packed footprint covers. */
  public static int footprintLastCol(int packed) {
    return (packed >>> 16) & 0xff;
  }

  /** Topmost row a packed footprint covers. */
  public static int footprintFirstRow(int packed) {
    return (packed >>> 8) & 0xff;
  }

  /** Bottommost row a packed footprint covers. */
  public static int footprintLastRow(int packed) {
    return packed & 0xff;
  }
}
