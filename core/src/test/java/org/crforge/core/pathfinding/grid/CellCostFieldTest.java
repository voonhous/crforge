package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-cell routing costs on the standard arena and on synthetic cost constants. */
class CellCostFieldTest {

  /** The standard arena's costs: water 5, blocked 100, building 100, default 7, roads 5 and 5. */
  private static final CellCosts LIVE = CellCosts.standard();

  /**
   * Six distinct constants, so every branch of the cost rule is distinguishable. The live values
   * make the two road costs equal, which hides the lane branch.
   */
  private static final CellCosts DISTINCT = new CellCosts(1, 2, 3, 4, 5, 6);

  private TileMap map;
  private CellGrid grid;

  @BeforeEach
  void setUp() {
    map = TileMap.standard1v1();
    grid =
        new CellGrid(
            map,
            PathfindingGlobals.PATHFINDING_DYNAMIC_OCCLUSIONS,
            PathfindingGlobals.PATHFINDING_BUILDING_COST);
  }

  private int liveCost(int cellX, int cellY, int state, int lane) {
    return CellCostField.cellCost(
        map.width(),
        map.height(),
        cellX,
        cellY,
        map.contains(cellX, cellY) ? map.bits(cellX, cellY) : 0,
        LIVE,
        true,
        false,
        false,
        state,
        lane,
        grid.getActive() != 0,
        map.contains(cellX, cellY) ? grid.getCurrent()[grid.index(cellX, cellY)] : 0);
  }

  @Test
  void chargesTheMatchingRoadCostOnTheUnitsOwnLane() {
    assertThat(map.bits(7, 20)).isEqualTo(1);
    assertThat(liveCost(7, 20, GridEntityState.MOVING, 1)).isEqualTo(5);
  }

  @Test
  void chargesTheDefaultCostOffTheRoads() {
    assertThat(map.bits(0, 0)).isEqualTo(16);
    assertThat(liveCost(0, 0, GridEntityState.MOVING, 1)).isEqualTo(7);
  }

  @Test
  void chargesTheBlockedCostOnWaterWithoutPermission() {
    assertThat(map.bits(3, 31)).isEqualTo(32);
    assertThat(liveCost(3, 31, GridEntityState.MOVING, 1)).isEqualTo(100);
  }

  @Test
  void raisesACellToTheOverlayCostWhileTheOverlayIsActive() {
    grid.setActive(1);
    grid.getCurrent()[grid.index(7, 31)] = 100;
    assertThat(map.bits(7, 31)).isEqualTo(1);
    assertThat(liveCost(7, 31, GridEntityState.MOVING, 1)).isEqualTo(100);
  }

  @Test
  void returnsTheDefaultCostInThePathfindStatesBeforeTheRoadRuleIsReached() {
    assertThat(liveCost(7, 20, GridEntityState.SPAWN_PATHFIND, 1)).isEqualTo(7);
    assertThat(liveCost(7, 20, GridEntityState.INGAME_PATHFIND, 1)).isEqualTo(7);
    assertThat(liveCost(7, 20, GridEntityState.ROUTE_FOLLOWING_ALTERNATE, 1)).isEqualTo(7);
  }

  @Test
  void rejectsCellsOutsideTheMap() {
    assertThat(liveCost(-1, 5, GridEntityState.MOVING, 1)).isEqualTo(-1);
    assertThat(liveCost(36, 5, GridEntityState.MOVING, 1)).isEqualTo(-1);
    assertThat(liveCost(5, -1, GridEntityState.MOVING, 1)).isEqualTo(-1);
    assertThat(liveCost(5, 64, GridEntityState.MOVING, 1)).isEqualTo(-1);
  }

  private int distinctCost(
      int tileBits,
      boolean entityPresent,
      boolean waterPermission,
      boolean alternateWaterPermission,
      int state,
      int lane,
      boolean dynamicActive,
      int occlusionCost) {
    return CellCostField.cellCost(
        10,
        10,
        1,
        1,
        tileBits,
        DISTINCT,
        entityPresent,
        waterPermission,
        alternateWaterPermission,
        state,
        lane,
        dynamicActive,
        occlusionCost);
  }

  @Test
  void distinguishesTheUnitsOwnRoadFromAnotherRoad() {
    assertThat(distinctCost(1, true, false, false, 1, 1, false, 0)).isEqualTo(6);
    assertThat(distinctCost(1, true, false, false, 1, 2, false, 0)).isEqualTo(5);
  }

  @Test
  void chargesThePlainRoadCostWhenNoEntityIsSupplied() {
    assertThat(distinctCost(1, false, false, false, 1, 1, false, 0)).isEqualTo(5);
  }

  @Test
  void allowsWaterForEitherWaterFlagAndBlocksItOtherwise() {
    assertThat(distinctCost(32, true, true, false, 1, 0, false, 0)).isEqualTo(1);
    assertThat(distinctCost(32, true, false, true, 1, 0, false, 0)).isEqualTo(1);
    assertThat(distinctCost(32, true, false, false, 1, 0, false, 0)).isEqualTo(2);
    assertThat(distinctCost(32, false, true, false, 1, 0, false, 0)).isEqualTo(2);
  }

  @Test
  void chargesTheBlockedCostOnABlockedCell() {
    assertThat(distinctCost(64, true, false, false, 1, 0, false, 0)).isEqualTo(2);
  }

  @Test
  void waterIsTestedBeforeTheBlockedBit() {
    assertThat(distinctCost(32 | 64, true, true, false, 1, 0, false, 0)).isEqualTo(1);
  }

  @Test
  void ignoresTheOverlayWhileTheOverlayIsInactive() {
    assertThat(distinctCost(0, true, false, false, 1, 0, false, 99)).isEqualTo(4);
    assertThat(distinctCost(0, true, false, false, 1, 0, true, 99)).isEqualTo(99);
    assertThat(distinctCost(0, true, false, false, 1, 0, true, 1)).isEqualTo(4);
  }

  @Test
  void buildsTheWholeRowMajorFieldForAUnit() {
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
    int[] field = CellCostField.costField(grid, LIVE, GridEntityState.MOVING, 1, false, false);

    assertThat(field).hasSize(36 * 64);
    assertThat(field[20 * 36 + 7]).isEqualTo(5);
    assertThat(field[0]).isEqualTo(7);
    assertThat(field[31 * 36 + 3]).isEqualTo(100);
    assertThat(field[6 * 36 + 18]).isEqualTo(100);

    int fives = 0;
    int sevens = 0;
    int hundreds = 0;
    for (int cost : field) {
      if (cost == 5) {
        fives++;
      } else if (cost == 7) {
        sevens++;
      } else if (cost == 100) {
        hundreds++;
      }
    }
    assertThat(fives).isEqualTo(556);
    assertThat(sevens).isEqualTo(1500);
    assertThat(hundreds).isEqualTo(248);
  }

  @Test
  void theCostLookupAgreesWithTheField() {
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
    CellCostLookup lookup =
        CellCostField.costLookup(grid, LIVE, GridEntityState.MOVING, 1, false, false);
    int[] field = CellCostField.costField(grid, LIVE, GridEntityState.MOVING, 1, false, false);

    for (int row = 0; row < 64; row++) {
      for (int col = 0; col < 36; col++) {
        assertThat(lookup.cost(col, row)).isEqualTo(field[row * 36 + col]);
      }
    }
    assertThat(lookup.cost(-1, 0)).isEqualTo(-1);
    assertThat(lookup.cost(36, 0)).isEqualTo(-1);
    assertThat(lookup.cost(0, 64)).isEqualTo(-1);
  }

  @Test
  void exposesThePublishedCostConstants() {
    assertThat(LIVE.waterCost()).isEqualTo(5);
    assertThat(LIVE.blockedCost()).isEqualTo(100);
    assertThat(LIVE.buildingCost()).isEqualTo(100);
    assertThat(LIVE.defaultCost()).isEqualTo(7);
    assertThat(LIVE.roadCost()).isEqualTo(5);
    assertThat(LIVE.matchingRoadCost()).isEqualTo(5);
  }
}
