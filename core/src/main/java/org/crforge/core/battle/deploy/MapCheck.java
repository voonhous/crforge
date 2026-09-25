package org.crforge.core.battle.deploy;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * The first check of a card play, on the raw requested point: whether it lies on the map, and
 * whether the map cell under it may take the card. A refused play creates nothing.
 *
 * <p>The codes are the command's: 0x0f for a point left of the map, 0x10 above its first row, 0x11
 * right of its last column, 0x12 below its last row, and 0x13 for a cell the card may not be placed
 * on.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the four bounds on the raw point, the cell test with the water, not-placeable"
            + " and blocked bits, and the codes. Not modelled: the margin rows, which no data"
            + " sets.")
public final class MapCheck {

  /** The play passed. */
  public static final int OK = 0;

  /** Bit of a map cell that holds water. */
  private static final int WATER = 0x20;

  /** Bits of a map cell that may not take a card unless it may be placed on buildings. */
  private static final int NOT_PLACEABLE = 0x50;

  /** The command's code for the first of the check's failures. */
  private static final int CODE_BASE = 0xe;

  private MapCheck() {
    // Utility class
  }

  /**
   * Checks a requested point.
   *
   * @return {@link #OK} or the command's refusal code
   */
  public static int check(TileMap tileMap, DeployCard card, int x, int y) {
    if (card.unit() == null) {
      return OK;
    }
    if (x < -499) {
      return CODE_BASE + 1;
    }
    int row = y / 500;
    if (row < 0) {
      return CODE_BASE + 2;
    }
    int col = x / 500;
    if (col >= tileMap.width()) {
      return CODE_BASE + 3;
    }
    if (row >= tileMap.height()) {
      return CODE_BASE + 4;
    }
    return cellOk(tileMap, col, row, card) ? OK : CODE_BASE + 5;
  }

  /** Whether one 500-unit map cell may take the card. */
  static boolean cellOk(TileMap tileMap, int col, int row, DeployCard card) {
    int bits = tileMap.bits(col, row);
    if ((bits & WATER) != 0) {
      return card.canPlaceOnWater();
    }
    return (bits & NOT_PLACEABLE) == 0 || card.canPlaceOnBuildings();
  }
}
