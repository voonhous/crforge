package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The standard 1v1 cell map loads with the expected shape and cell flags. */
class TileMapTest {

  private final TileMap map = TileMap.standard1v1();

  @Test
  void hasTheStandardArenaDimensions() {
    assertThat(map.width()).isEqualTo(36);
    assertThat(map.height()).isEqualTo(64);
    assertThat(TileMap.CELL_UNITS).isEqualTo(500);
    assertThat(map.widthUnits()).isEqualTo(18000);
    assertThat(map.heightUnits()).isEqualTo(32000);
  }

  @Test
  void isLoadedOnceAndShared() {
    assertThat(TileMap.standard1v1()).isSameAs(map);
  }

  @Test
  void readsTheSampleCells() {
    assertThat(map.bits(7, 20)).isEqualTo(1);
    assertThat(map.bits(0, 0)).isEqualTo(16);
    assertThat(map.bits(3, 31)).isEqualTo(32);
  }

  @Test
  void masksAwayTheBitsRoutingDoesNotUse() {
    // These cells carry values above the routing bits in the published data.
    assertThat(map.bits(7, 32)).isEqualTo(1); // published as 257
    assertThat(map.bits(28, 32)).isEqualTo(2); // published as 258
    assertThat(map.bits(11, 36)).isZero(); // published as 128
    assertThat(map.bits(17, 41)).isZero(); // published as 512

    for (int row = 0; row < map.height(); row++) {
      for (int col = 0; col < map.width(); col++) {
        assertThat(map.bits(col, row)).isBetween(0, TileMap.ROUTING_BITS_MASK);
      }
    }
  }

  @Test
  void countsWaterCells() {
    assertThat(countWhere(TileMap.WATER_BIT)).isEqualTo(112);
  }

  @Test
  void countsNotPlaceableCellsAndHasNoBlockedCells() {
    assertThat(countWhere(TileMap.NOT_PLACEABLE_BIT)).isEqualTo(176);
    assertThat(countWhere(TileMap.BLOCKED_BIT)).isZero();
  }

  @Test
  void countsRoadCellsPerLane() {
    int[] perLane = new int[4];
    for (int row = 0; row < map.height(); row++) {
      for (int col = 0; col < map.width(); col++) {
        perLane[map.roadId(col, row)]++;
      }
    }
    assertThat(perLane[0]).isEqualTo(1612);
    assertThat(perLane[1]).isEqualTo(346);
    assertThat(perLane[2]).isEqualTo(346);
    assertThat(perLane[3]).isZero();
  }

  @Test
  void theRiverRowIsWaterExceptWhereTheTwoBridgesCross() {
    int riverRow = 31;
    // Each bridge is four columns wide: two road columns with a dry column on either side.
    for (int col = 0; col < map.width(); col++) {
      boolean onABridge = (col >= 5 && col <= 8) || (col >= 27 && col <= 30);
      assertThat(map.isWater(col, riverRow))
          .as("cell (%d, %d)", col, riverRow)
          .isNotEqualTo(onABridge);
    }
    assertThat(map.roadId(6, riverRow)).isEqualTo(1);
    assertThat(map.roadId(7, riverRow)).isEqualTo(1);
    assertThat(map.roadId(28, riverRow)).isEqualTo(2);
    assertThat(map.roadId(29, riverRow)).isEqualTo(2);
    assertThat(map.roadId(5, riverRow)).isZero();
    assertThat(map.roadId(30, riverRow)).isZero();
  }

  @Test
  void cornersAreNotPlaceable() {
    assertThat(map.isNotPlaceable(0, 0)).isTrue();
    assertThat(map.isNotPlaceable(map.width() - 1, map.height() - 1)).isTrue();
  }

  @Test
  void convertsBetweenCoordinatesAndRowMajorIds() {
    assertThat(map.index(7, 20)).isEqualTo(20 * 36 + 7);
    assertThat(map.col(727)).isEqualTo(7);
    assertThat(map.row(727)).isEqualTo(20);
    assertThat(map.index(map.col(1734), map.row(1734))).isEqualTo(1734);
  }

  @Test
  void boundsAreChecked() {
    assertThat(map.contains(0, 0)).isTrue();
    assertThat(map.contains(35, 63)).isTrue();
    assertThat(map.contains(-1, 0)).isFalse();
    assertThat(map.contains(36, 0)).isFalse();
    assertThat(map.contains(0, 64)).isFalse();
    assertThatThrownBy(() -> map.bits(36, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> map.bits(0, -1)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void aSyntheticMapMasksAndCopiesItsValues() {
    int[] raw = {257, 512, 32, 16};
    TileMap synthetic = new TileMap(2, 2, raw);
    raw[0] = 0;

    assertThat(synthetic.bits(0, 0)).isEqualTo(1);
    assertThat(synthetic.bits(1, 0)).isZero();
    assertThat(synthetic.bits(0, 1)).isEqualTo(32);
    assertThat(synthetic.bits(1, 1)).isEqualTo(16);
  }

  @Test
  void rejectsMalformedDimensions() {
    assertThatThrownBy(() -> new TileMap(0, 2, new int[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TileMap(2, 2, new int[3]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loaderReportsAMissingResource() {
    assertThatThrownBy(() -> TileMapLoader.load("/arena/does_not_exist.txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  private int countWhere(int bit) {
    int count = 0;
    for (int row = 0; row < map.height(); row++) {
      for (int col = 0; col < map.width(); col++) {
        if ((map.bits(col, row) & bit) != 0) {
          count++;
        }
      }
    }
    return count;
  }
}
