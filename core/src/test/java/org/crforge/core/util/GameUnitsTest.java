package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.crforge.core.arena.Arena;
import org.junit.jupiter.api.Test;

class GameUnitsTest {

  @Test
  void tiles_convertsAtOneThousandUnitsPerTile() {
    assertThat(GameUnits.UNITS_PER_TILE).isEqualTo(1000);
    assertThat(GameUnits.tiles(1)).isEqualTo(1000);
    assertThat(GameUnits.tiles(0.5)).isEqualTo(500);
    assertThat(GameUnits.tiles(9.5f)).isEqualTo(9500);
    assertThat(GameUnits.tiles(0)).isZero();
  }

  @Test
  void tiles_roundsToNearestUnit_forValuesWithAtMostThreeDecimals() {
    // Float literals such as 1.141f are not exactly representable; conversion must still land on
    // the intended unit instead of truncating to 1140
    assertThat(GameUnits.tiles(1.141f)).isEqualTo(1141);
    assertThat(GameUnits.tiles(0.577f)).isEqualTo(577);
    assertThat(GameUnits.tiles(0.001f)).isEqualTo(1);
    assertThat(GameUnits.tiles(10.1f)).isEqualTo(10100);
  }

  @Test
  void tiles_handlesNegativeValuesSymmetrically() {
    assertThat(GameUnits.tiles(-0.5)).isEqualTo(-500);
    assertThat(GameUnits.tiles(-2.75f)).isEqualTo(-2750);
    assertThat(GameUnits.tiles(-1.141f)).isEqualTo(-1141);
  }

  @Test
  void round_tiesTowardPositiveInfinity() {
    assertThat(GameUnits.round(0.5)).isEqualTo(1);
    assertThat(GameUnits.round(-0.5)).isZero();
    assertThat(GameUnits.round(-1.5)).isEqualTo(-1);
    assertThat(GameUnits.round(2.49)).isEqualTo(2);
  }

  @Test
  void round_rejectsValuesOutsideIntRange() {
    assertThatThrownBy(() -> GameUnits.round(1e12)).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void toTiles_isInverseOfTiles() {
    assertThat(GameUnits.toTiles(9500)).isEqualTo(9.5f);
    assertThat(GameUnits.toTiles(-500)).isEqualTo(-0.5f);
    assertThat(GameUnits.toTiles(GameUnits.tiles(13.9f))).isCloseTo(13.9f, within(0.0005f));
  }

  @Test
  void tileIndex_usesFloorDivision_forNegativeCoordinates() {
    assertThat(GameUnits.tileIndex(0)).isZero();
    assertThat(GameUnits.tileIndex(999)).isZero();
    assertThat(GameUnits.tileIndex(1000)).isEqualTo(1);
    // Truncation would map these into tile 0; floor division keeps them outside the grid
    assertThat(GameUnits.tileIndex(-1)).isEqualTo(-1);
    assertThat(GameUnits.tileIndex(-1000)).isEqualTo(-1);
    assertThat(GameUnits.tileIndex(-1001)).isEqualTo(-2);
  }

  @Test
  void tileCenterAndStart_matchTileGrid() {
    assertThat(GameUnits.tileStart(3)).isEqualTo(3000);
    assertThat(GameUnits.tileCenter(3)).isEqualTo(3500);
    assertThat(GameUnits.tileIndex(GameUnits.tileCenter(17))).isEqualTo(17);
  }

  @Test
  void arenaExtent_isEighteenByThirtyTwoTilesInGameUnits() {
    assertThat(Arena.WIDTH_UNITS).isEqualTo(18_000);
    assertThat(Arena.HEIGHT_UNITS).isEqualTo(32_000);
    assertThat(GameUnits.tileIndex(Arena.WIDTH_UNITS - 1)).isEqualTo(Arena.WIDTH - 1);
    assertThat(GameUnits.tileIndex(Arena.HEIGHT_UNITS - 1)).isEqualTo(Arena.HEIGHT - 1);
  }

  @Test
  void rawSpeedToUnitsPerSecond_usesSixtyAsOneTilePerSecond() {
    assertThat(GameUnits.rawSpeedToUnitsPerSecond(60f)).isEqualTo(1000f);
    assertThat(GameUnits.rawSpeedToUnitsPerSecond(45f)).isEqualTo(750f);
    assertThat(GameUnits.rawSpeedToUnitsPerSecond(90f)).isEqualTo(1500f);
    // Fractional result: speeds must stay floating point
    assertThat(GameUnits.rawSpeedToUnitsPerSecond(650f)).isCloseTo(10833.333f, within(0.01f));
  }

  @Test
  void distanceSquared_usesLongArithmetic_withoutOverflow() {
    // Arena diagonal: 18000^2 + 32000^2 = 1,348,000,000 (exceeds float precision)
    assertThat(GameUnits.distanceSquared(0, 0, Arena.WIDTH_UNITS, Arena.HEIGHT_UNITS))
        .isEqualTo(1_348_000_000L);

    // Per-axis separations up to Integer.MAX_VALUE: an int product would overflow, the long
    // result is still exact (2 * (2^31 - 1)^2 is just below Long.MAX_VALUE)
    long extreme = GameUnits.distanceSquared(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
    long axis = (long) Integer.MAX_VALUE * Integer.MAX_VALUE;
    assertThat(extreme).isEqualTo(axis + axis);
    assertThat(extreme).isPositive();
  }

  @Test
  void distanceSquared_isExactWhereFloatWouldLosePrecision() {
    long a = GameUnits.distanceSquared(0, 0, 18_000, 32_000);
    long b = GameUnits.distanceSquared(0, 0, 18_000, 32_001);
    // Squares differ by 64,001; representable as long, but float rounds both to nearby values
    assertThat(b - a).isEqualTo(64_001L);
  }

  @Test
  void withinRadius_isInclusiveAtBoundary_andInsideRadiusIsExclusive() {
    long radius = 1500;
    assertThat(GameUnits.withinRadius(1500L * 1500L, radius)).isTrue();
    assertThat(GameUnits.withinRadius(1500L * 1500L + 1, radius)).isFalse();
    assertThat(GameUnits.insideRadius(1500L * 1500L, radius)).isFalse();
    assertThat(GameUnits.insideRadius(1500L * 1500L - 1, radius)).isTrue();
  }

  @Test
  void distance_matchesEuclideanDistanceInUnits() {
    assertThat(GameUnits.distance(0, 0, 3000, 4000)).isEqualTo(5000.0);
    assertThat(GameUnits.distance(-3000, -4000, 0, 0)).isEqualTo(5000.0);
  }
}
