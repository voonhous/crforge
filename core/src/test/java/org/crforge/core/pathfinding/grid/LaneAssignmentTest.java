/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Road (lane) assignment of a world position on the standard arena and on synthetic maps. */
class LaneAssignmentTest {

  private static final TileMap MAP = TileMap.standard1v1();

  private static int standardLane(int x, int y) {
    return LaneAssignment.lane(MAP.width(), MAP.height(), MAP.width(), x, y, -1, 0, MAP::bits);
  }

  @Test
  void assignsTheLeftLaneOnTheLeftHalfAndTheRightLaneOnTheRightHalf() {
    assertThat(standardLane(3500, 10000)).isEqualTo(1);
    assertThat(standardLane(14500, 10000)).isEqualTo(2);
    assertThat(standardLane(3500, 25500)).isEqualTo(1);
    assertThat(standardLane(14500, 25500)).isEqualTo(2);
  }

  @Test
  void assignsTheRightLaneOnTheCentreLine() {
    assertThat(standardLane(9000, 12000)).isEqualTo(2);
    assertThat(standardLane(9000, 3000)).isEqualTo(2);
  }

  @Test
  void findsTheNearestRoadOfACell() {
    assertThat(LaneAssignment.nearestRoad(MAP.width(), MAP.height(), 7, 20, MAP::bits))
        .isEqualTo(1);
    assertThat(LaneAssignment.nearestRoad(MAP.width(), MAP.height(), 0, 0, MAP::bits)).isEqualTo(1);
    assertThat(LaneAssignment.nearestRoad(MAP.width(), MAP.height(), 35, 63, MAP::bits))
        .isEqualTo(2);
  }

  /** Columns 1 and 2 carry road 1, columns 5 and 6 carry road 2; the map is 8 by 4 cells. */
  private static final TileLookup TWO_ROADS =
      (col, row) -> {
        if (col == 1 || col == 2) {
          return 1;
        }
        return col == 5 || col == 6 ? 2 : 0;
      };

  private static int syntheticLane(TileLookup tiles, int x, int refX, int flag) {
    return LaneAssignment.lane(8, 4, 8, x, 750, refX, flag, tiles);
  }

  @Test
  void aReferenceOnTheOtherHalfDoesNotSwapWhenTheTwoSearchesDisagree() {
    assertThat(syntheticLane(TWO_ROADS, 750, 2750, 0)).isEqualTo(1);
    assertThat(syntheticLane(TWO_ROADS, 2750, 750, 0)).isEqualTo(2);
  }

  @Test
  void aSetFlagSkipsTheReferenceSearchEntirely() {
    assertThat(syntheticLane(TWO_ROADS, 750, 2750, 1)).isEqualTo(1);
  }

  /** A single road of id 1 in column 3, so both searches always agree. */
  private static final TileLookup ONE_ROAD = (col, row) -> col == 3 ? 1 : 0;

  /** The same map with the single road carrying id 2. */
  private static final TileLookup ONE_ROAD_TWO = (col, row) -> col == 3 ? 2 : 0;

  @Test
  void swapsTheTwoLanesWhenBothSearchesAgreeAndTheHalvesDiffer() {
    assertThat(syntheticLane(ONE_ROAD, 750, 2750, 0)).isEqualTo(2);
    assertThat(syntheticLane(ONE_ROAD, 2750, 750, 0)).isEqualTo(2);
    assertThat(syntheticLane(ONE_ROAD_TWO, 750, 2750, 0)).isEqualTo(1);
    assertThat(syntheticLane(ONE_ROAD_TWO, 2750, 750, 0)).isEqualTo(1);
  }

  @Test
  void doesNotSwapWhilePositionAndReferenceShareAHalf() {
    assertThat(syntheticLane(ONE_ROAD, 1750, 2250, 0)).isEqualTo(1);
    assertThat(syntheticLane(ONE_ROAD_TWO, 1750, 2250, 0)).isEqualTo(2);
  }

  @Test
  void leavesARoadIdOutsideOneAndTwoAlone() {
    TileLookup roadThree = (col, row) -> col == 3 ? 3 : 0;
    assertThat(LaneAssignment.lane(8, 4, 8, 750, 750, -1, 0, roadThree)).isEqualTo(3);
    assertThat(syntheticLane(roadThree, 750, 2750, 0)).isEqualTo(3);
  }

  @Test
  void answersNoLaneWithoutRoadsOrWithoutColumns() {
    assertThat(LaneAssignment.lane(8, 4, 8, 750, 750, -1, 0, (col, row) -> 0)).isZero();
    assertThat(LaneAssignment.lane(0, 4, 8, 750, 750, -1, 0, TWO_ROADS)).isZero();
  }

  /**
   * The search over every cell of the map, as it ran before the map kept its road cells: the oracle
   * the search over the road cells alone is held to.
   */
  private static int wholeMapLane(TileMap map, int x, int y, int referenceX, int flag) {
    int col = x / TileMap.CELL_UNITS;
    int row = y / TileMap.CELL_UNITS;
    int nearestToPosition = 0;
    int distanceToPosition = Integer.MAX_VALUE;
    int nearestToReference = -1;
    int distanceToReference = Integer.MAX_VALUE;
    boolean oppositeHalves = false;
    boolean plain = referenceX == -1 || (flag & 1) != 0;
    int shiftedCol = (x + 5) / TileMap.CELL_UNITS;
    int referenceCol = referenceX / TileMap.CELL_UNITS;
    int half = map.width() / 2;
    for (int scanCol = 0; scanCol < map.width(); scanCol++) {
      for (int scanRow = 0; scanRow < map.height(); scanRow++) {
        int roadId = map.bits(scanCol, scanRow) & TileMap.ROAD_ID_MASK;
        if (roadId < 1) {
          continue;
        }
        int dy2 = (scanRow - row) * (scanRow - row);
        int fromPosition = dy2 + (scanCol - col) * (scanCol - col);
        int fromReference = dy2 + (scanCol - referenceCol) * (scanCol - referenceCol);
        if (!plain && fromReference < distanceToReference) {
          nearestToReference = roadId;
          distanceToReference = fromReference;
        }
        boolean closer = fromPosition <= distanceToPosition;
        if (fromPosition < distanceToPosition) {
          nearestToPosition = roadId;
          distanceToPosition = fromPosition;
        }
        if (!plain && closer && !oppositeHalves) {
          if (referenceX > x) {
            oppositeHalves = referenceCol > half && shiftedCol <= half;
          } else if (referenceX < x) {
            oppositeHalves = referenceCol < half && shiftedCol >= half;
          }
        }
      }
    }
    if (!plain && oppositeHalves && nearestToPosition == nearestToReference) {
      return nearestToReference == 1 ? 2 : nearestToReference == 2 ? 1 : nearestToReference;
    }
    return nearestToPosition;
  }

  @Test
  void theTileMapsRoadCellsAnswerAsAScanOfTheWholeMap() {
    int checked = 0;
    for (int x = 0; x < MAP.widthUnits(); x += 350) {
      for (int y = 0; y < MAP.heightUnits(); y += 700) {
        for (int referenceX : new int[] {-1, 0, 4000, 8995, 9000, 9005, 14000, 17999}) {
          for (int flag = 0; flag <= 1; flag++) {
            assertThat(LaneAssignment.lane(MAP, x, y, referenceX, flag))
                .as("x %d y %d reference %d flag %d", x, y, referenceX, flag)
                .isEqualTo(wholeMapLane(MAP, x, y, referenceX, flag))
                .isEqualTo(
                    LaneAssignment.lane(
                        MAP.width(), MAP.height(), MAP.width(), x, y, referenceX, flag, MAP::bits));
            checked++;
          }
        }
      }
    }
    assertThat(checked).isGreaterThan(10_000);
  }
}
