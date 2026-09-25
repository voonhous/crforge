package org.crforge.core.battle.deploy;

import java.util.List;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * Where a card placed at a point actually lands: the point clamped to the arena and snapped to a
 * tile, then the nearest legal tile around it.
 *
 * <p>The search walks square rings of tiles around the snapped point, ring 0 to ring 30. A tile is
 * legal when every tile of the unit's footprint lies inside the card's margin columns and inside
 * the arena, is open in the deploy mask, and each of its four map cells may take the card. The
 * first ring that holds a legal tile ends the search; within it the tile nearest to the clamped
 * request wins, the first in walk order on a tie. A ground unit that is no building and walks to
 * nothing then takes 1 off x on the arena's left half and 1 off y on the top side, so the two
 * sides' units stand mirrored exactly.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the clamp, the character snap and the tile centre, the footprint, the rings and"
            + " their walk order, the legality test with the margin columns, the mask and the map"
            + " cells, the nearest-in-the-first-ring rule and the symmetrical snap. Not modelled:"
            + " the Mirror card, a spell or area-effect card, a building unit's morph, and the lane"
            + " requirement, which the place-card command does not ask for.")
public final class PlacementSearch {

  private static final int RING_LIMIT = 30;

  private PlacementSearch() {
    // Utility class
  }

  /** The tiles a unit's footprint spans: its override, or its collision radius over 500 plus 1. */
  public static int footprint(UnitData unit) {
    if (unit.tileSizeOverride() > 0) {
      return unit.tileSizeOverride();
    }
    return (unit.collisionRadius() + 499) / 500 + 1;
  }

  /** One coordinate snapped to its 1000-unit tile: the centre, or the edge for an even building. */
  static int snap(UnitData unit, int v) {
    int t = v / 1000 * 1000;
    if (unit.building() && (footprint(unit) & 1) == 0) {
      return t;
    }
    return t + 500;
  }

  /**
   * Finds the point a card placed at a requested point lands on.
   *
   * @param tileMap the arena's map
   * @param card the card
   * @param x requested x in game units
   * @param y requested y in game units
   * @param side the placing side
   * @param mask the deploy mask, one flag per 1000-unit tile
   * @param symmetricalSnap whether the symmetrical snap applies
   * @return the point, or null when no legal tile is found
   */
  public static int[] find(
      TileMap tileMap,
      DeployCard card,
      int x,
      int y,
      int side,
      boolean[] mask,
      boolean symmetricalSnap) {
    int w = tileMap.width();
    int h = tileMap.height();
    int xmax = w * 500 - 1;
    int ymax = h * 500 - 1;
    int cx = x > 0 ? Math.min(x, xmax) : 0;
    int cy = y > 0 ? Math.min(y, ymax) : 0;
    int hw = w >> 1;
    int hh = h >> 1;
    UnitData unit = card.unit();
    int fp = unit.building() ? footprint(unit) : 1;
    int sx = snap(unit, cx);
    int sy = snap(unit, cy);
    int margin = card.deployWTileMargin();

    long best = Integer.MAX_VALUE;
    int bx = 0;
    int by = 0;
    int limit = RING_LIMIT;
    for (int r = 0; ; r++) {
      int count = r == 0 ? 1 : 2 * r;
      for (int j = 0; j < count; j++) {
        for (int k = 0; k < (r == 0 ? 1 : 4); k++) {
          int dx;
          int dy;
          if ((k & 1) == 0) {
            dx = j - r;
            dy = -r;
          } else {
            dx = -r;
            dy = r - j;
          }
          if (k > 1) {
            dx = -dx;
            dy = -dy;
          }
          int px = sx + dx * 1000;
          int py = sy + dy * 1000;
          if (!legal(tileMap, card, mask, hw, hh, fp, margin, px, py)) {
            continue;
          }
          int d = squaredDistance(px, py, cx, cy);
          if (d < best) {
            best = d;
            bx = px;
            by = py;
            limit = 0;
          }
        }
      }
      if (r >= limit) {
        break;
      }
    }
    if (best == Integer.MAX_VALUE) {
      return null;
    }
    if (symmetricalSnap
        && unit.flyingHeight() <= 0
        && !unit.building()
        && unit.spawnPathfindSpeed() == 0) {
      bx -= cx < w * 250 ? 1 : 0;
      by -= side & 1;
    }
    return new int[] {bx, by};
  }

  /** Whether every tile of the footprint around a point may take the card. */
  private static boolean legal(
      TileMap tileMap,
      DeployCard card,
      boolean[] mask,
      int hw,
      int hh,
      int fp,
      int margin,
      int px,
      int py) {
    int half = 500 * fp;
    int xlo = (px - half) / 1000;
    int xhi = (px + half) / 1000;
    int ylo = (py - half) / 1000;
    int yhi = (py + half) / 1000;
    if (xlo >= xhi) {
      return true;
    }
    int available = Math.max(hh, ylo) - ylo;
    for (int col = xlo; col < xhi; col++) {
      if (ylo >= yhi) {
        continue;
      }
      if (col < margin || col >= hw - margin) {
        return false;
      }
      if (py - half < -999) {
        return false;
      }
      int left = available;
      for (int row = ylo; row < yhi; row++) {
        if (left == 0) {
          return false;
        }
        if (!mask[row * hw + col]) {
          return false;
        }
        for (int[] d : new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
          if (!MapCheck.cellOk(tileMap, 2 * col + d[0], 2 * row + d[1], card)) {
            return false;
          }
        }
        left--;
      }
    }
    return true;
  }

  /**
   * The squared distance between two points; the largest int when a difference lies beyond 46340 or
   * the sum would pass it.
   */
  static int squaredDistance(int ax, int ay, int bx, int by) {
    int dx = bx - ax;
    int dy = by - ay;
    if (dx < -46340 || dx > 46340 || dy < -46340 || dy > 46340) {
      return Integer.MAX_VALUE;
    }
    int a = dx * dx;
    int b = dy * dy;
    return b < Integer.MAX_VALUE - a ? a + b : Integer.MAX_VALUE;
  }

  /** The characters of a battle as the mask reads them, from their data and where they stand. */
  public static MaskEntity maskEntity(UnitData data, int side, int x, int y, boolean alive) {
    return new MaskEntity(
        alive,
        side,
        x,
        y,
        data.summonerTower(),
        data.king(),
        data.building(),
        data.noDeploySizeW(),
        data.noDeploySizeH(),
        footprint(data));
  }

  /** Builds a mask for a card placed by a side. */
  public static boolean[] mask(TileMap tileMap, DeployCard card, int side, List<MaskEntity> list) {
    return DeployMask.build(
        tileMap.width() >> 1,
        tileMap.height() >> 1,
        list,
        (side & 1) == 0,
        card.canDeployOnEnemySide(),
        card.canPlaceOnBuildings(),
        false,
        card.fullLaneDeploy(),
        card.deployStartY(),
        card.deployEndY());
  }
}
