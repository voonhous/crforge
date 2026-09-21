package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Allocation, lookups and the end-of-tick overlay rotation of {@link CellGrid}. */
class CellGridTest {

  private CellGrid grid;

  @BeforeEach
  void setUp() {
    grid =
        new CellGrid(
            TileMap.standard1v1(),
            PathfindingGlobals.PATHFINDING_DYNAMIC_OCCLUSIONS,
            PathfindingGlobals.PATHFINDING_BUILDING_COST);
  }

  @Test
  void allocatesZeroedOverlaysOfTheMapSize() {
    assertThat(grid.getWidth()).isEqualTo(36);
    assertThat(grid.getHeight()).isEqualTo(64);
    assertThat(grid.getCurrent()).hasSize(36 * 64).containsOnly(0);
    assertThat(grid.getPrevious()).hasSize(36 * 64).containsOnly(0);
    assertThat(grid.getHash()).containsExactly(0, 0);
    assertThat(grid.getChanged()).containsExactly(0, 0);
    assertThat(grid.getChangeFlags()).containsExactly(0, 0);
    assertThat(grid.getFootprints()).isEmpty();
    assertThat(grid.getFootprintCounter()).isZero();
    assertThat(grid.getActive()).isZero();
  }

  @Test
  void carriesTheConfiguredOcclusionSettings() {
    assertThat(grid.isDynamicOcclusionsEnabled()).isTrue();
    assertThat(grid.dynamicEnabled()).isEqualTo(1);
    assertThat(grid.getBuildingCost()).isEqualTo(50);

    CellGrid disabled = new CellGrid(TileMap.standard1v1(), false, 100);
    assertThat(disabled.isDynamicOcclusionsEnabled()).isFalse();
    assertThat(disabled.dynamicEnabled()).isZero();
  }

  @Test
  void looksUpTileFlagsAndWaterFromTheMap() {
    assertThat(grid.tiles(7, 20)).isEqualTo(1);
    assertThat(grid.tiles(0, 0)).isEqualTo(16);
    assertThat(grid.tiles(3, 31)).isEqualTo(32);

    assertThat(grid.water(3, 31)).isEqualTo(1);
    assertThat(grid.water(7, 31)).isZero();
    assertThat(grid.water(7, 20)).isZero();
  }

  @Test
  void indexesCellsRowMajor() {
    assertThat(grid.index(7, 20)).isEqualTo(20 * 36 + 7);
    assertThat(grid.index(0, 0)).isZero();
  }

  @Test
  void swapMovesTheCurrentOverlayIntoPreviousAndStartsAFreshOne() {
    int[] built = grid.getCurrent();
    built[grid.index(7, 20)] = 100;

    grid.swap();

    assertThat(grid.getPrevious()).isSameAs(built);
    assertThat(grid.getPrevious()[grid.index(7, 20)]).isEqualTo(100);
    assertThat(grid.getCurrent()).isNotSameAs(built).hasSize(36 * 64).containsOnly(0);
  }

  @Test
  void repeatedSwapsKeepOnlyTheLastTwoOverlays() {
    int[] first = grid.getCurrent();
    grid.swap();
    int[] second = grid.getCurrent();
    grid.swap();

    assertThat(grid.getPrevious()).isSameAs(second);
    assertThat(grid.getCurrent()).isNotSameAs(first).isNotSameAs(second);
  }
}
