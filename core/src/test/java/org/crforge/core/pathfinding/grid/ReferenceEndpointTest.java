package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Choosing the cell a unit walks to in order to attack its target. */
class ReferenceEndpointTest {

  /** The Knight's attack range: its own range plus its own collision radius. */
  private static final int ATTACK_RANGE = 1700;

  private TileMap map;
  private CellGrid grid;

  @BeforeEach
  void setUp() {
    map = TileMap.standard1v1();
    grid = new CellGrid(map, true, 100);
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
  }

  private int endpoint(int targetX, int targetY, int unitX, int unitY, boolean air) {
    return endpoint(targetX, targetY, unitX, unitY, air, null);
  }

  private int endpoint(
      int targetX, int targetY, int unitX, int unitY, boolean air, List<int[]> visits) {
    return ReferenceEndpoint.selectEndpoint(
        map.width(),
        map.height(),
        unitX,
        unitY,
        targetX / TileMap.CELL_UNITS,
        targetY / TileMap.CELL_UNITS,
        ATTACK_RANGE,
        air,
        PathfindingGlobals.KS_POS_TO_TARGET_FLYING_NO_WATER,
        PathfindingGlobals.KS_POS_TO_TARGET_GROUND_AVOID_BUILDINGS,
        ReferenceEndpoint.everyCellInBounds(map.width(), map.height()),
        (cellCentreX, cellCentreY) ->
            FixedMath.squaredDistance(targetX, targetY, cellCentreX, cellCentreY),
        (col, row) -> map.isWater(col, row) ? 1 : 0,
        (col, row) -> CellTests.overlayBlocks(grid, col, row),
        visits == null ? null : (col, row) -> visits.add(new int[] {col, row}));
  }

  @Test
  void walksToTheCellBesideTheLeftPrincessTower() {
    List<int[]> visits = new ArrayList<>();
    int packed = endpoint(3500, 25500, 3500, 10000, false, visits);

    assertThat(packed).isEqualTo((6 << 16) | 48);
    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(6);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(48);
    // A range of 1700 gives a window of 1700 / 500 + 1 = 4 cells each way, so nine by nine.
    assertThat(visits).hasSize(81);
  }

  @Test
  void walksToTheCellBesideTheRightPrincessTower() {
    int packed = endpoint(14500, 25500, 14500, 10000, false);

    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(29);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(48);
  }

  @Test
  void walksToTheCellBesideTheKingTower() {
    int packed = endpoint(9000, 29000, 9000, 12000, false);

    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(18);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(55);
  }

  @Test
  void picksTheCellNearestTheUnitOnceItHasCrossedTheBridge() {
    int packed = endpoint(3500, 25500, 3731, 22854, false);

    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(7);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(48);
  }

  /** A ten by ten synthetic map for the ranking rules, with every cell in range of the target. */
  private int syntheticEndpoint(
      CellPredicate waterBit,
      CellPredicate overlay,
      boolean air,
      boolean flyingNoWater,
      boolean groundAvoidBuildings) {
    return ReferenceEndpoint.selectEndpoint(
        10,
        10,
        0,
        0,
        5,
        5,
        1000,
        air,
        flyingNoWater,
        groundAvoidBuildings,
        ReferenceEndpoint.everyCellInBounds(10, 10),
        (cellCentreX, cellCentreY) -> 0,
        waterBit,
        overlay,
        null);
  }

  private static final CellPredicate NONE = (col, row) -> 0;

  /** Everything is water except one cell, which is therefore the only preferred one. */
  private static final CellPredicate WATER_EXCEPT_FAR = (col, row) -> col == 7 && row == 7 ? 0 : 1;

  /** Everything carries a building footprint except one cell. */
  private static final CellPredicate OCCLUDED_EXCEPT_FAR =
      (col, row) -> col == 7 && row == 7 ? 0 : 1;

  @Test
  void prefersADryCellOverACloserWetOne() {
    int packed = syntheticEndpoint(WATER_EXCEPT_FAR, NONE, false, true, true);

    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(7);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(7);
  }

  @Test
  void aFlyingUnitWithTheNoWaterRuleOnTreatsEveryCellAlike() {
    int packed = syntheticEndpoint(WATER_EXCEPT_FAR, NONE, true, true, true);

    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(2);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(2);
  }

  @Test
  void aFlyingUnitWithTheNoWaterRuleOffStillAvoidsWater() {
    int packed = syntheticEndpoint(WATER_EXCEPT_FAR, NONE, true, false, true);

    assertThat(ReferenceEndpoint.unpackCol(packed)).isEqualTo(7);
    assertThat(ReferenceEndpoint.unpackRow(packed)).isEqualTo(7);
  }

  @Test
  void aGroundUnitAvoidsBuildingFootprintsOnlyWhileTheRuleIsOn() {
    int avoiding = syntheticEndpoint(NONE, OCCLUDED_EXCEPT_FAR, false, true, true);
    assertThat(ReferenceEndpoint.unpackCol(avoiding)).isEqualTo(7);
    assertThat(ReferenceEndpoint.unpackRow(avoiding)).isEqualTo(7);

    int ignoring = syntheticEndpoint(NONE, OCCLUDED_EXCEPT_FAR, false, true, false);
    assertThat(ReferenceEndpoint.unpackCol(ignoring)).isEqualTo(2);
    assertThat(ReferenceEndpoint.unpackRow(ignoring)).isEqualTo(2);
  }

  @Test
  void scansEachRowRightToLeftForAUnitOnTheRightHalfOfTheArena() {
    List<int[]> fromLeft = new ArrayList<>();
    List<int[]> fromRight = new ArrayList<>();
    scanWithUnitAt(0, fromLeft);
    scanWithUnitAt(9 * TileMap.CELL_UNITS, fromRight);

    assertThat(fromLeft.get(0)).containsExactly(2, 2);
    assertThat(fromRight.get(0)).containsExactly(8, 2);
  }

  private void scanWithUnitAt(int unitX, List<int[]> visits) {
    ReferenceEndpoint.selectEndpoint(
        10,
        10,
        unitX,
        0,
        5,
        5,
        1000,
        false,
        true,
        true,
        ReferenceEndpoint.everyCellInBounds(10, 10),
        (cellCentreX, cellCentreY) -> 0,
        NONE,
        NONE,
        (col, row) -> visits.add(new int[] {col, row}));
  }

  @Test
  void answersNoEndpointWhenNoCellQualifies() {
    int outOfRange =
        ReferenceEndpoint.selectEndpoint(
            10,
            10,
            0,
            0,
            5,
            5,
            100,
            false,
            true,
            true,
            ReferenceEndpoint.everyCellInBounds(10, 10),
            (cellCentreX, cellCentreY) -> Integer.MAX_VALUE,
            NONE,
            NONE,
            null);
    assertThat(outOfRange).isEqualTo(-1);

    int allRejected =
        ReferenceEndpoint.selectEndpoint(
            10,
            10,
            0,
            0,
            5,
            5,
            100,
            false,
            true,
            true,
            (col, row) -> 0,
            (cellCentreX, cellCentreY) -> 0,
            NONE,
            NONE,
            null);
    assertThat(allRejected).isEqualTo(-1);
  }

  @Test
  void theFallbackHandsBackTheTargetsOwnWorldPositionWhenThereIsNoEndpoint() {
    ReferenceEndpoint.Destination fallback =
        ReferenceEndpoint.referenceEndpointAndFallback(-1, 3500, 25500);

    assertThat(fallback.x()).isEqualTo(3500);
    assertThat(fallback.y()).isEqualTo(25500);
    assertThat(fallback.fallback()).isTrue();
  }

  /**
   * The branch is decided by the top bit, so an all-bits-set value is a fallback and not the cell
   * that its two halves would otherwise spell out.
   */
  @Test
  void anyPackedValueWithItsTopBitSetTakesTheFallbackBranch() {
    int allBitsSet = (0xffff << 16) | 0xffff;
    ReferenceEndpoint.Destination fallback =
        ReferenceEndpoint.referenceEndpointAndFallback(allBitsSet, 1, 2);
    assertThat(fallback.fallback()).isTrue();
    assertThat(fallback.x()).isEqualTo(1);
    assertThat(fallback.y()).isEqualTo(2);

    assertThat(ReferenceEndpoint.referenceEndpointAndFallback(Integer.MIN_VALUE, 1, 2).fallback())
        .isTrue();
  }

  @Test
  void theFallbackUnpacksACellWhenThereIsAnEndpoint() {
    ReferenceEndpoint.Destination chosen =
        ReferenceEndpoint.referenceEndpointAndFallback((6 << 16) | 48, 3500, 25500);

    assertThat(chosen.x()).isEqualTo(6);
    assertThat(chosen.y()).isEqualTo(48);
    assertThat(chosen.fallback()).isFalse();
  }

  @Test
  void asksTheCellAcceptanceOnceMoreForTheCellItChose() {
    List<int[]> asked = new ArrayList<>();
    CellPredicate recording =
        (col, row) -> {
          asked.add(new int[] {col, row});
          return 1;
        };

    int packed =
        ReferenceEndpoint.selectEndpoint(
            10,
            10,
            0,
            0,
            5,
            5,
            1000,
            false,
            true,
            true,
            recording,
            (cellCentreX, cellCentreY) -> 0,
            NONE,
            NONE,
            null);

    assertThat(asked).hasSize(50);
    assertThat(asked.get(asked.size() - 1))
        .containsExactly(ReferenceEndpoint.unpackCol(packed), ReferenceEndpoint.unpackRow(packed));
  }
}
