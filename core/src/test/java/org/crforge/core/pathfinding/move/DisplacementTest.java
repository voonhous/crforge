package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of one displacement toward a point, and of the charge bookkeeping it tails into.
 *
 * <p>The two arrival-boundary cases set the route direction to a unit vector straight along the
 * arena's width, so the projection the arrival test computes is simply the distance left along that
 * axis after the step: a target 1060 units away less the 60-unit step leaves exactly 1000, and 1061
 * leaves 1001. That is the smallest pair either side of the threshold.
 */
class DisplacementTest {

  private static final CellGrid GRID =
      new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);

  private static final MovementGlobals GLOBALS = MovementGlobals.forStandardArena(36);

  private MovementState component;
  private GridEntity owner;
  private StubMovementQueries queries;
  private MovementChain chain;

  @BeforeEach
  void setUp() {
    component = MovementState.forSide(0, 3500, 10000);
    component.setRouteDirX(81);
    component.setRouteDirY(243);
    owner = new GridEntity();
    owner.setX(3500);
    owner.setY(10000);
    owner.setDirX(0);
    owner.setDirY(256);
    owner.setState(GridEntityState.MOVING);
    queries = new StubMovementQueries();
    chain =
        new MovementChain(
            component,
            owner,
            GRID,
            MovementConfig.forGroundUnit(),
            GLOBALS,
            null,
            List.of(),
            queries);
  }

  private int displace(int tx, int ty, int budget, int updateFacing) {
    return Displacement.displace(
        component,
        owner,
        GRID,
        MovementConfig.forGroundUnit(),
        GLOBALS,
        queries,
        chain,
        tx,
        ty,
        budget,
        updateFacing,
        0);
  }

  @Test
  void aStepTowardTheNextWaypointMovesTurnsAndReportsArrival() {
    int step = displace(3250, 10250, 60, 1);

    assertThat(step).isEqualTo(60);
    assertThat(owner.getX()).isEqualTo(3458);
    assertThat(owner.getY()).isEqualTo(10042);
    assertThat(owner.getDirX()).isEqualTo(-181);
    assertThat(owner.getDirY()).isEqualTo(181);
    assertThat(component.getWaypointReached()).isEqualTo(1);
  }

  @Test
  void aShutFacingGateLeavesTheFacingAlone() {
    displace(3250, 10250, 60, 0);

    assertThat(owner.getX()).isEqualTo(3458);
    assertThat(owner.getY()).isEqualTo(10042);
    assertThat(owner.getDirX()).isZero();
    assertThat(owner.getDirY()).isEqualTo(256);
  }

  @Test
  void aStepIsCappedAtTwoHundredAndFiftyUnits() {
    int step = displace(13500, 10000, 400, 1);

    assertThat(step).isEqualTo(250);
    assertThat(owner.getX()).isEqualTo(3750);
    assertThat(owner.getY()).isEqualTo(10000);
    assertThat(component.getWaypointReached()).isZero();
  }

  @Test
  void aTargetOnTheUnitItselfStepsOneUnitAndKeepsTheFacing() {
    int step = displace(3500, 10000, 60, 1);

    assertThat(step).isEqualTo(1);
    assertThat(owner.getX()).isEqualTo(3500);
    assertThat(owner.getY()).isEqualTo(10000);
    assertThat(owner.getDirY()).isEqualTo(256);
    assertThat(component.getWaypointReached()).isEqualTo(1);
  }

  @Test
  void aProjectedRemainingDistanceOfExactlyAThousandCountsAsArrival() {
    component.setRouteDirX(256);
    component.setRouteDirY(0);

    int step = displace(3500 + 1060, 10_000, 60, 0);

    assertThat(step).isEqualTo(60);
    assertThat(owner.getX()).isEqualTo(3560);
    assertThat(owner.getY()).isEqualTo(10_000);
    assertThat(component.getWaypointReached()).isEqualTo(1);
  }

  @Test
  void aProjectedRemainingDistanceOfOneMoreThanAThousandDoesNot() {
    component.setRouteDirX(256);
    component.setRouteDirY(0);

    int step = displace(3500 + 1061, 10_000, 60, 0);

    assertThat(step).isEqualTo(60);
    assertThat(owner.getX()).isEqualTo(3560);
    assertThat(owner.getY()).isEqualTo(10_000);
    assertThat(component.getWaypointReached()).isZero();
  }

  @Test
  void anAvoidanceBlendRotatesTheStepSideways() {
    component.setAvoidanceBlend(100);

    displace(3250, 10250, 60, 1);

    assertThat(owner.getX()).isEqualTo(3486);
    assertThat(owner.getY()).isEqualTo(10058);
    assertThat(component.getWorkVector()).containsExactly(-14, 58);
  }

  @Test
  void aBlendBeyondTheClampStillRotatesWithinIt() {
    component.setAvoidanceBlend(-200);

    displace(3250, 10250, 60, 1);

    assertThat(owner.getX()).isEqualTo(3448);
    assertThat(owner.getY()).isEqualTo(9971);
  }

  @Test
  void anAccumulatedPushIsAveragedAddedAndThenCleared() {
    component.setPushX(-600);
    component.setPushY(200);
    component.setPushCount(2);

    displace(3250, 10250, 60, 1);

    assertThat(owner.getX()).isEqualTo(3316);
    assertThat(owner.getY()).isEqualTo(10089);
    assertThat(component.getPushX()).isZero();
    assertThat(component.getPushY()).isZero();
    assertThat(component.getPushCount()).isZero();
  }

  @Test
  void aLongPushIsClampedToAFixedLengthBeforeItIsAdded() {
    component.setPushX(-1200);
    component.setPushY(400);
    component.setPushCount(2);

    displace(3250, 10250, 60, 1);

    assertThat(owner.getX()).isEqualTo(3316);
    assertThat(owner.getY()).isEqualTo(10089);
  }

  @Test
  void anActiveChargeGrowsWithEveryStepTaken() {
    component.setChargeProgress(0);
    queries.hasModifierComponent = true;
    queries.chargeRangeFromModifiers = 2000;

    displace(3250, 10250, 60, 1);

    assertThat(component.getChargeProgress()).isEqualTo(30);
  }

  @Test
  void aCompletedChargeSetsTheChargingFlag() {
    component.setChargeProgress(9990);
    queries.hasModifierComponent = true;
    queries.chargeRangeFromModifiers = 100;

    displace(3250, 10250, 60, 1);

    assertThat(component.getChargeProgress()).isEqualTo(10590);
    assertThat(owner.getPendingFlags()).isEqualTo(1L << 3);
  }

  @Test
  void theGuardedHelpersAgreeWithTheDocumentedLengths() {
    assertThat(FixedMath.guardedDistance(300, 400)).isEqualTo(500);
    int[] vector = {300, 400};
    assertThat(FixedMath.normalize(vector, 256)).isEqualTo(500);
    assertThat(vector).containsExactly(153, 204);
  }
}
