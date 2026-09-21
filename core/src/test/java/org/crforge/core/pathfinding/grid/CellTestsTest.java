package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The overlay test and the standing test on the standard arena. */
class CellTestsTest {

  private CellGrid grid;

  @BeforeEach
  void setUp() {
    grid = new CellGrid(TileMap.standard1v1(), true, 100);
  }

  @Test
  void reportsACellWhoseOverlayReachesTheBuildingCost() {
    grid.getCurrent()[51 * 36 + 7] = 100;

    assertThat(CellTests.overlayBlocks(grid, 7, 51)).isEqualTo(1);
    assertThat(CellTests.overlayBlocks(grid, 7, 50)).isZero();
  }

  @Test
  void reportsNothingForNegativeOrOutOfBoundsCells() {
    grid.getCurrent()[51 * 36 + 7] = 100;

    assertThat(CellTests.overlayBlocks(grid, -1, 0)).isZero();
    assertThat(CellTests.overlayBlocks(grid, 0, -1)).isZero();
    assertThat(CellTests.overlayBlocks(grid, 36, 0)).isZero();
    assertThat(CellTests.overlayBlocks(grid, 0, 64)).isZero();
  }

  @Test
  void reportsNothingWhileDynamicOcclusionsAreOff() {
    CellGrid disabled = new CellGrid(TileMap.standard1v1(), false, 100);
    disabled.getCurrent()[51 * 36 + 7] = 100;

    assertThat(CellTests.overlayBlocks(disabled, 7, 51)).isZero();
  }

  @Test
  void comparesTheOverlayAgainstTheGridsOwnBuildingCost() {
    CellGrid dearer = new CellGrid(TileMap.standard1v1(), true, 101);
    dearer.getCurrent()[51 * 36 + 7] = 100;

    assertThat(CellTests.overlayBlocks(dearer, 7, 51)).isZero();
  }

  @Test
  void acceptsAnOpenGroundCell() {
    assertThat(CellTests.cellBlocked(grid, 3500, 10000)).isZero();
  }

  @Test
  void rejectsWaterAndTheNotPlaceableCells() {
    assertThat(CellTests.cellBlocked(grid, 1500, 15750)).isEqualTo(1);
    assertThat(CellTests.cellBlocked(grid, 9000, 3000)).isEqualTo(1);
    assertThat(CellTests.cellBlocked(grid, 0, 0)).isEqualTo(1);
  }

  @Test
  void rejectsPointsOutsideTheArena() {
    assertThat(CellTests.cellBlocked(grid, -10, 500)).isEqualTo(1);
    assertThat(CellTests.cellBlocked(grid, 500, -10)).isEqualTo(1);
    assertThat(CellTests.cellBlocked(grid, 18000, 500)).isEqualTo(1);
    assertThat(CellTests.cellBlocked(grid, 500, 32000)).isEqualTo(1);
  }

  @Test
  void theStandingTestIgnoresTheOverlay() {
    grid.setActive(1);
    grid.getCurrent()[20 * 36 + 7] = 100;

    assertThat(CellTests.cellBlocked(grid, 3500, 10000)).isZero();
  }
}
