package org.crforge.core.battle.deploy;

import java.util.Arrays;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The tiles a card may be placed on: a grid of 1000-unit tiles, a row of the grid spanning the
 * arena's width, every tile open to begin with.
 *
 * <p>The card's row limits close the rows before its first open row and from its last. Every alive
 * building or king tower then writes a box of tiles around itself: an enemy tower closes the box it
 * keeps free of the other side's placements unless the card may be placed on that side, and any
 * other building closes its own footprint. Last, a card that keeps only whole rows closes every row
 * with a closed tile. The towers' boxes are what keep a card to its own half: once a princess tower
 * falls, its box no longer closes the pocket in front of it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the row limits, the boxes of enemy towers and of other buildings, the whole-row"
            + " filter, and the C divisions of the box bounds. Not modelled: a capture tower, a"
            + " battle of four players, a touchdown mode and the two dummy rows.")
public final class DeployMask {

  private DeployMask() {
    // Utility class
  }

  /**
   * Builds the mask.
   *
   * @param stride tiles across the arena's width
   * @param rows tiles along its length
   * @param entities the battle's characters, the live list then the queued ones
   * @param ownTeam0 whether the placing side is on team 0
   * @param enemyOk whether the card may be placed on the other side
   * @param onBuildings whether the card may be placed on buildings
   * @param touchdown whether the touchdown limit applies
   * @param fullLane whether only whole rows stay open
   * @param startY the card's first open row
   * @param endY the row from which the card's rows close again
   * @return one flag per tile, row after row; true for open
   */
  public static boolean[] build(
      int stride,
      int rows,
      List<MaskEntity> entities,
      boolean ownTeam0,
      boolean enemyOk,
      boolean onBuildings,
      boolean touchdown,
      boolean fullLane,
      int startY,
      int endY) {
    boolean[] grid = new boolean[Math.max(stride * rows, 0)];
    Arrays.fill(grid, true);
    rowLimits(grid, stride, rows, startY, endY);
    for (MaskEntity e : entities) {
      int[] box = box(e, ownTeam0, enemyOk, onBuildings, touchdown, startY, endY);
      if (box != null) {
        markBox(grid, stride, rows, e.x(), e.y(), box[0], box[1], box[2] != 0);
      }
    }
    if (fullLane) {
      for (int r = 0; r < rows; r++) {
        boolean whole = true;
        for (int c = 0; c < stride; c++) {
          whole &= grid[r * stride + c];
        }
        if (!whole) {
          Arrays.fill(grid, r * stride, (r + 1) * stride, false);
        }
      }
    }
    return grid;
  }

  private static void rowLimits(boolean[] grid, int stride, int rows, int startY, int endY) {
    if (endY == startY) {
      return;
    }
    if (startY >= 1 && stride >= 1) {
      for (int r = 0; r < Math.min(startY, rows); r++) {
        Arrays.fill(grid, r * stride, (r + 1) * stride, false);
      }
    }
    int first = endY == 0 ? rows : endY;
    if (rows - first > 0 && stride >= 1) {
      for (int r = first; r < rows; r++) {
        Arrays.fill(grid, r * stride, (r + 1) * stride, false);
      }
    }
  }

  /** The box one entity writes, as {width, height, open}, or null for none. */
  private static int[] box(
      MaskEntity e,
      boolean ownTeam0,
      boolean enemyOk,
      boolean onBuildings,
      boolean touchdown,
      int startY,
      int endY) {
    if (!e.alive()) {
      return null;
    }
    if (!(e.building() || e.summoner())) {
      return null;
    }
    boolean sameRows = startY == endY;
    boolean enemy = (e.team() == 0) != ownTeam0;
    if (onBuildings) {
      boolean toBox = e.summoner() ? sameRows && enemy : sameRows && enemy && e.summonerTower();
      if (toBox && !enemyOk) {
        return noDeployBox(e, touchdown);
      }
      if (e.building() || e.noDeploySizeW() > 0) {
        return null;
      }
    }
    if (sameRows && enemy && e.noDeploySizeW() >= 1 && !enemyOk) {
      return noDeployBox(e, touchdown);
    }
    return new int[] {e.footprint(), e.footprint(), 0};
  }

  /** The box an enemy tower keeps free of the other side's placements, in a battle of two. */
  private static int[] noDeployBox(MaskEntity e, boolean touchdown) {
    int h = touchdown ? e.noDeploySizeH() - 12 : e.noDeploySizeH();
    return new int[] {e.noDeploySizeW(), h, 0};
  }

  /**
   * Writes a box of w by h tiles centred on a point: columns (x - 500w) / 1000 up to (x + 500w) /
   * 1000, rows alike, the divisions truncating, tiles outside the grid skipped.
   */
  private static void markBox(
      boolean[] grid, int stride, int rows, int x, int y, int w, int h, boolean value) {
    if (w == 0) {
      return;
    }
    int r0 = (y - 500 * h) / 1000;
    int r1 = (y + 500 * h) / 1000;
    int c0 = (x - 500 * w) / 1000;
    int c1 = (x + 500 * w) / 1000;
    if (r0 >= r1 || c0 >= c1) {
      return;
    }
    for (int r = r0; r < r1; r++) {
      if (r < 0 || r >= rows) {
        continue;
      }
      for (int c = c0; c < c1; c++) {
        if (c >= 0 && c < stride) {
          grid[r * stride + c] = value;
        }
      }
    }
  }
}
