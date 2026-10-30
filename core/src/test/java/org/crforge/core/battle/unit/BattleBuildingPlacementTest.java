/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The placement a spawn row that validates its point as a building's asks for, on a blocked point:
 * the clamped point is kept when no building other than the owner overlaps the child's circle there
 * and its cell can be stood on; otherwise up to nine half-tile rows are tried in the point's
 * column, stepping down the arena for side 0 and up for side 1. A row passes when the cells at its
 * two edges, half the child's footprint below and above it, can be stood on and no building at all,
 * the owner included, overlaps the child's circle at the middle of the two; the point then moves to
 * the lower edge, a further footprint of rows on the side's way, in whole half tiles. When no row
 * passes the point is answered as it was asked for, unclamped.
 */
class BattleBuildingPlacementTest {

  /** The Goblins hero's banner, a building that is placed by an action. */
  private static final String BANNER = "GoblinHero_Flag_Building";

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  private static final int CELL = TileMap.CELL_UNITS;

  /** A column of the arena's river beside the left bridge, and the river's first and last rows. */
  private static final int RIVER_COLUMN = 9;

  private static final int RIVER_FIRST_ROW = 30;

  private static final int RIVER_LAST_ROW = 33;

  @Test
  @DisplayName(
      "a point on the river is moved down the arena for side 0 to the first row whose edges are"
          + " dry, and then a footprint of rows further, onto a half tile")
  void sideZeroStepsDown() {
    Standard1v1Battle battle = battle();
    assertRiverColumn(battle);
    UnitData banner = GameData.records().unit(BANNER);
    int half = PlacementSearch.footprint(banner) / 2;
    int x = RIVER_COLUMN * CELL + 157;
    int y = (RIVER_FIRST_ROW + 1) * CELL + 459;
    // The first row whose upper edge lies below the river, and its lower edge.
    int row = RIVER_FIRST_ROW - 1 - half;
    int low = row - half;
    int[] placed =
        battle
            .getWorld()
            .spawns()
            .buildingPlacement(battle.getWorld().kingTower(0), banner)
            .place(x, y);
    assertThat(placed).containsExactly(x, (low - PlacementSearch.footprint(banner)) * CELL);
  }

  @Test
  @DisplayName(
      "a point on the river is moved up the arena for side 1 to the first row whose edges are"
          + " dry, and then a footprint of rows further from its lower edge")
  void sideOneStepsUp() {
    Standard1v1Battle battle = battle();
    assertRiverColumn(battle);
    UnitData banner = GameData.records().unit(BANNER);
    int half = PlacementSearch.footprint(banner) / 2;
    int x = RIVER_COLUMN * CELL + 157;
    int y = (RIVER_FIRST_ROW + 1) * CELL + 459;
    int row = RIVER_LAST_ROW + 1 + half;
    int low = row - half;
    int[] placed =
        battle
            .getWorld()
            .spawns()
            .buildingPlacement(battle.getWorld().kingTower(1), banner)
            .place(x, y);
    assertThat(placed).containsExactly(x, (low + PlacementSearch.footprint(banner)) * CELL);
  }

  @Test
  @DisplayName(
      "the rows' overlap query passes no object by: the king tower placing its own row on its own"
          + " point is passed by there, but its circle refuses every row whose edges can be stood on")
  void theRowsDoNotPassTheOwnerBy() {
    Standard1v1Battle battle = battle();
    TowerEntity king = battle.getWorld().kingTower(0);
    UnitData row = king.getData();
    int kx = king.getView().getX();
    int ky = king.getView().getY();
    int col = kx / CELL;
    int start = ky / CELL;
    int half = PlacementSearch.footprint(row) / 2;
    int reach = king.getView().getCollisionRadius() + row.collisionRadius();
    // The king's own point: its circle is passed by, but its cell cannot be stood on.
    assertThat(standable(battle, col, start)).isFalse();
    // Among the nine rows down the arena some have both edges standable, and the king's circle
    // overlaps the middle of each of them: only the owner blocks those rows.
    int open = 0;
    for (int k = 1; k <= 9; k++) {
      int r = start - k;
      if (standable(battle, col, r - half) && standable(battle, col, r + half)) {
        assertThat(Math.abs(r * CELL - ky)).as("row " + r).isLessThan(reach);
        open++;
      }
    }
    assertThat(open).isPositive();
    // No row passes, so the king's own point comes back.
    assertThat(battle.getWorld().spawns().buildingPlacement(king, row).place(kx, ky))
        .containsExactly(kx, ky);
  }

  @Test
  @DisplayName(
      "a point with no passing row in the nine is answered as it was asked for, outside the arena"
          + " too, unclamped")
  void noFreeRowKeepsTheRequestedPoint() {
    Standard1v1Battle battle = battle();
    UnitData banner = GameData.records().unit(BANNER);
    TileMap map = battle.getWorld().getTileMap();
    // Beyond the arena's top left corner, for side 1: the point clamps to the corner cell, which
    // cannot be stood on, and every row above it lies outside the map.
    int x = -300;
    int y = map.height() * CELL + 200;
    assertThat(map.bits(0, map.height() - 1) & TileMap.NOT_PLACEABLE_BIT).isNotZero();
    assertThat(
            battle
                .getWorld()
                .spawns()
                .buildingPlacement(battle.getWorld().kingTower(1), banner)
                .place(x, y))
        .containsExactly(x, y);
  }

  private static boolean standable(Standard1v1Battle battle, int col, int row) {
    TileMap map = battle.getWorld().getTileMap();
    if (col < 0 || row < 0 || col >= map.width() || row >= map.height()) {
      return false;
    }
    int bits = map.bits(col, row);
    return (bits & (TileMap.NOT_PLACEABLE_BIT | TileMap.WATER_BIT | TileMap.BLOCKED_BIT)) == 0;
  }

  /** The arena's column beside the left bridge is river on its four rows and dry around them. */
  private static void assertRiverColumn(Standard1v1Battle battle) {
    TileMap map = battle.getWorld().getTileMap();
    for (int row = RIVER_FIRST_ROW - 5; row <= RIVER_LAST_ROW + 5; row++) {
      boolean river = row >= RIVER_FIRST_ROW && row <= RIVER_LAST_ROW;
      assertThat(map.bits(RIVER_COLUMN, row) & TileMap.WATER_BIT)
          .as("row " + row)
          .isEqualTo(river ? TileMap.WATER_BIT : 0);
      assertThat(standable(battle, RIVER_COLUMN, row)).as("row " + row).isEqualTo(!river);
    }
  }

  private static Standard1v1Battle battle() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(KNIGHTS, KNIGHTS, 0, 0, new int[8], new int[8]);
    return battle;
  }
}
