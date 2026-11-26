/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The map check on a raw requested point, and its codes. */
class MapCheckTest {

  private static final TileMap MAP = TileMap.standard1v1();

  private static DeployCard knight() {
    return GameData.card("Knight");
  }

  @ParameterizedTest(name = "({0}, {1}) -> {2}")
  @CsvSource({
    "3500, 10000, 0",
    "-499, 10000, 0",
    "-500, 10000, 15",
    "3500, -500, 16",
    "3500, -499, 19",
    "18000, 10000, 17",
    "17999, 10000, 0",
    "3500, 32000, 18",
    "4500, 16250, 19"
  })
  @DisplayName(
      "the four bounds on the raw point, then the cell under it: a point just inside the top bound"
          + " reaches the first row, which takes no card")
  void theBoundsAndTheCell(int x, int y, int code) {
    assertThat(MapCheck.check(MAP, knight(), x, y)).isEqualTo(code);
  }

  @Test
  @DisplayName(
      "a map that refuses cells with bits above the modelled seven refuses a check of one, and"
          + " checks every other cell as before")
  void refusesACheckOfAnUnmodelledCell() {
    int[] values = new int[4 * 4];
    values[2 * 4 + 1] = 1 << 8;
    TileMap map = new TileMap(4, 4, values).refusingUnmodelledCells();

    assertThat(MapCheck.check(map, knight(), 500 + 250, 250)).isZero();
    assertThatThrownBy(() -> MapCheck.check(map, knight(), 500 + 250, 1000 + 250))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("(1, 2)")
        .hasMessageContaining("a placement");
    // The same map as a battle of two reads it: the bit is masked away.
    assertThat(MapCheck.check(new TileMap(4, 4, values), knight(), 750, 1250)).isZero();
  }
}
