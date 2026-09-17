package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Moving a point off water, on the standard arena. */
class RelocationTest {

  private static final TileMap MAP = TileMap.standard1v1();

  private static final WaterTest WATER = (col, row) -> (MAP.bits(col, row) >> 5) & 1;

  private static int relocate(int x, int y, int referenceY) {
    return Relocation.relocate(MAP.width(), MAP.height(), x, y, referenceY, WATER);
  }

  @Test
  void movesAPointOnTheRiverToTheNearestDryRowWithoutAReference() {
    int packed = relocate(1500, 15750, -1);

    assertThat(Relocation.unpackX(packed)).isEqualTo(1250);
    assertThat(Relocation.unpackY(packed)).isEqualTo(14500);
  }

  @Test
  void keepsTheColumnBandWhenTheSecondPointIsFurtherRight() {
    int packed = relocate(9000, 15750, -1);

    assertThat(Relocation.unpackX(packed)).isEqualTo(8750);
    assertThat(Relocation.unpackY(packed)).isEqualTo(14500);
  }

  @Test
  void aReferenceBelowThePointSelectsTheRowsBelowIt() {
    int packed = relocate(1500, 15750, 16000);

    assertThat(Relocation.unpackX(packed)).isEqualTo(1250);
    assertThat(Relocation.unpackY(packed)).isEqualTo(17000);
  }

  @Test
  void aReferenceAboveThePointSelectsTheRowsAboveIt() {
    int packed = relocate(1500, 15750, 15000);

    assertThat(Relocation.unpackX(packed)).isEqualTo(1250);
    assertThat(Relocation.unpackY(packed)).isEqualTo(14500);
  }

  @Test
  void returnsADryPointUnchanged() {
    int packed = relocate(3500, 10000, -1);

    assertThat(Relocation.unpackX(packed)).isEqualTo(3500);
    assertThat(Relocation.unpackY(packed)).isEqualTo(10000);
  }

  @Test
  void clampsAPointOutsideTheArenaToTheEdgeBand() {
    int lowCorner = relocate(-500, -500, -1);
    assertThat(Relocation.unpackX(lowCorner)).isEqualTo(250);
    assertThat(Relocation.unpackY(lowCorner)).isEqualTo(250);

    int highCorner = relocate(20000, 40000, -1);
    assertThat(Relocation.unpackX(highCorner)).isEqualTo(17750);
    assertThat(Relocation.unpackY(highCorner)).isEqualTo(31750);
  }

  @Test
  void packsTheXInTheLowHalfAndTheYInTheHighHalf() {
    int packed = relocate(1500, 15750, -1);

    assertThat(packed).isEqualTo(1250 | (14500 << 16));
  }
}
