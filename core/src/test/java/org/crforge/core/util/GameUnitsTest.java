package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.Test;

class GameUnitsTest {

  @Test
  void tileCenter_isHalfATileIntoTheTile() {
    assertThat(GameUnits.UNITS_PER_TILE).isEqualTo(1000);
    assertThat(GameUnits.tileCenter(0)).isEqualTo(500);
    assertThat(GameUnits.tileCenter(3)).isEqualTo(3500);
    assertThat(GameUnits.tileCenter(17)).isEqualTo(17_500);
  }

  @Test
  void arenaExtent_isEighteenByThirtyTwoTilesInGameUnits() {
    TileMap arena = TileMap.standard1v1();
    assertThat(arena.widthUnits()).isEqualTo(18_000);
    assertThat(arena.heightUnits()).isEqualTo(32_000);
    assertThat(arena.widthUnits() / GameUnits.UNITS_PER_TILE).isEqualTo(18);
    assertThat(arena.heightUnits() / GameUnits.UNITS_PER_TILE).isEqualTo(32);
  }
}
