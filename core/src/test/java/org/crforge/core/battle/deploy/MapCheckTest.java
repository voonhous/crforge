package org.crforge.core.battle.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
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
}
