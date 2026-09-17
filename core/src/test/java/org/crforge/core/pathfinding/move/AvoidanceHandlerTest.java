package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the handler that steers a unit around whatever stands in front of it.
 *
 * <p>Every case below places a unit at (3500, 10000) facing down the arena with a facing vector of
 * (0, 256), a collision radius of 500 and a mass of 3, and gives it one or two neighbours. The grid
 * is the standard arena, 36 columns wide, so the route node 763 is the cell at column 7, row 21,
 * whose centre is (3750, 10750), and node 1447 is the cell at column 7, row 40.
 *
 * <p>Sides: a neighbour to the left of the facing vector (a lower x, here) steers the unit one way
 * and a neighbour to the right the other; the handler names the two 1 and 0 and turns them into a
 * blend of +200 and -200.
 */
class AvoidanceHandlerTest {

  private static final CellGrid GRID =
      new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);

  /** Columns of the standard arena, which is what turns a route node into a cell centre. */
  private static final int WIDTH = 36;

  /** The unit's next waypoint: column 7, row 21, centred on (3750, 10750). */
  private static final int NEXT_NODE = 21 * WIDTH + 7;

  /** A node farther down the same column, which the route keeps when the next one is dropped. */
  private static final int GOAL_NODE = 40 * WIDTH + 7;

  private MovementState component;
  private GridEntity owner;
  private StubMovementQueries queries;

  @BeforeEach
  void setUp() {
    component = MovementState.forSide(0, 3500, 10000);
    owner = new GridEntity();
    owner.setId(1);
    owner.setX(3500);
    owner.setY(10000);
    owner.setDirX(0);
    owner.setDirY(256);
    owner.setCollisionRadius(500);
    owner.setMass(3);
    owner.setState(GridEntityState.MOVING);
    owner.setMovementActive(true);
    queries = new StubMovementQueries();
  }

  /** A neighbour with no movement component of its own: an obstacle to steer around. */
  private static GridEntity obstacle(int id, int x, int y) {
    GridEntity entity = new GridEntity();
    entity.setId(id);
    entity.setX(x);
    entity.setY(y);
    entity.setCollisionRadius(500);
    entity.setMass(3);
    entity.setMovementActive(false);
    entity.setSlot190(1);
    return entity;
  }

  /** A neighbour that moves, with the facing and state that decide whether it blocks. */
  private static GridEntity mover(int id, int x, int y, int dirX, int dirY, int state) {
    GridEntity entity = obstacle(id, x, y);
    entity.setMovementActive(true);
    entity.setDirX(dirX);
    entity.setDirY(dirY);
    entity.setState(state);
    return entity;
  }

  private void run(List<GridEntity> others) {
    MovementChain chain =
        new MovementChain(
            component,
            owner,
            GRID,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(WIDTH),
            null,
            others,
            queries);
    AvoidanceHandler.avoidance(component, owner, others, queries, chain);
  }

  // ---------------------------------------------------------------------------------------
  // Obstacles
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("an obstacle standing on the next waypoint makes the unit skip that waypoint")
  void anObstacleOnTheNextWaypointDropsIt() {
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));

    run(List.of(obstacle(2, 3750, 10750)));

    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE);
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("an obstacle whose circle misses the next waypoint leaves the route alone")
  void anObstacleAwayFromTheWaypointKeepsTheRoute() {
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));

    run(List.of(obstacle(2, 4350, 10750)));

    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE, NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("the last waypoint of a one node route is never dropped")
  void anObstacleOnTheOnlyWaypointKeepsIt() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(obstacle(2, 3750, 10750)));

    assertThat(component.getRoute().toArray()).containsExactly(NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("an obstacle to either side sets the blend toward that side")
  void anObstacleSetsTheBlendBySide() {
    component.setRoute(Route.of(NEXT_NODE));
    run(List.of(obstacle(2, 3000, 10200)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(200);

    setUp();
    component.setRoute(Route.of(NEXT_NODE));
    run(List.of(obstacle(2, 4000, 10200)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("an obstacle moves a blend the unit already has by twenty, and no further than 200")
  void anObstacleNudgesAnExistingBlend() {
    component.setRoute(Route.of(NEXT_NODE));
    component.setAvoidanceBlend(100);
    run(List.of(obstacle(2, 3000, 10200)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(120);

    setUp();
    component.setRoute(Route.of(NEXT_NODE));
    component.setAvoidanceBlend(100);
    run(List.of(obstacle(2, 4000, 10200)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(80);

    setUp();
    component.setRoute(Route.of(NEXT_NODE));
    component.setAvoidanceBlend(195);
    run(List.of(obstacle(2, 3000, 10200)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(200);

    setUp();
    component.setRoute(Route.of(NEXT_NODE));
    component.setAvoidanceBlend(-195);
    run(List.of(obstacle(2, 4000, 10200)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("a moving neighbour flagged as an obstacle is treated as one")
  void aMoverFlaggedAsAnObstacleDropsTheWaypoint() {
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));
    GridEntity other = mover(2, 3750, 10750, 0, 256, GridEntityState.MOVING);
    other.setFlags(EntityFlags.AVOIDANCE_AS_OBSTACLE);

    run(List.of(other));

    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE);
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  // ---------------------------------------------------------------------------------------
  // Blockers
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a neighbour walking the other way blocks and steers the unit aside")
  void aHeadOnMoverBlocks() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(mover(2, 3400, 10400, 0, -256, GridEntityState.MOVING)));

    assertThat(component.getAvoidanceBlend()).isEqualTo(200);
  }

  @Test
  @DisplayName("a blocker with a blend of its own steers the unit the way that blend points")
  void aBlockerLendsItsOwnBlend() {
    component.setRoute(Route.of(NEXT_NODE));
    GridEntity other = mover(2, 3400, 10400, 0, -256, GridEntityState.MOVING);
    queries.neighbourBlend.put(other, -30);

    run(List.of(other));

    // Its position alone would have given +200; its own blend overrides that.
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("a blocker on the other side with no blend steers the unit the other way")
  void aBlockerOnTheRightSteersTheOtherWay() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(mover(2, 3600, 10400, 0, -256, GridEntityState.MOVING)));

    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("a neighbour walking the same way is ignored altogether")
  void aMoverHeadingTheSameWayIsIgnored() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(mover(2, 3500, 10400, 0, 256, GridEntityState.MOVING)));

    assertThat(component.getAvoidanceBlend()).isZero();
  }

  @Test
  @DisplayName("an attacking neighbour counts as facing nowhere, so it blocks whatever it faces")
  void anAttackingMoverBlocksEvenFacingAway() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(mover(2, 3400, 10400, 0, 256, GridEntityState.ATTACKING)));

    assertThat(component.getAvoidanceBlend()).isEqualTo(200);
  }

  @Test
  @DisplayName("a neighbour with a special attack loaded counts as facing nowhere too")
  void aMoverWithASpecialLoadBlocks() {
    component.setRoute(Route.of(NEXT_NODE));
    GridEntity other = mover(2, 3400, 10400, 0, 256, GridEntityState.MOVING);
    queries.neighbourSpecialLoad.put(other, 1);

    run(List.of(other));

    assertThat(component.getAvoidanceBlend()).isEqualTo(200);
  }

  @Test
  @DisplayName("a neighbour whose state override is off keeps the facing it actually has")
  void aMoverWithoutTheStateOverrideKeepsItsFacing() {
    component.setRoute(Route.of(NEXT_NODE));
    GridEntity other = mover(2, 3400, 10400, 0, 256, GridEntityState.ATTACKING);
    other.setSlot190(0);

    run(List.of(other));

    assertThat(component.getAvoidanceBlend()).isZero();
  }

  @Test
  @DisplayName("a charging unit runs a lighter blocker over and is steered by a heavier one")
  void aChargingUnitOnlyGivesWayToSomethingHeavier() {
    component.setRoute(Route.of(NEXT_NODE));
    component.setChargeProgress(12000);
    owner.setMass(8);
    run(List.of(mover(2, 3400, 10400, 0, -256, GridEntityState.MOVING)));
    assertThat(component.getAvoidanceBlend()).isZero();

    setUp();
    component.setRoute(Route.of(NEXT_NODE));
    component.setChargeProgress(12000);
    owner.setMass(3);
    GridEntity heavy = mover(2, 3400, 10400, 0, -256, GridEntityState.MOVING);
    heavy.setMass(8);
    run(List.of(heavy));
    assertThat(component.getAvoidanceBlend()).isEqualTo(200);
  }

  @Test
  @DisplayName("blockers alone never move a blend the unit already has")
  void blockersAloneLeaveAnExistingBlendAlone() {
    component.setRoute(Route.of(NEXT_NODE));
    component.setAvoidanceBlend(100);

    run(List.of(mover(2, 3400, 10400, 0, -256, GridEntityState.MOVING)));

    assertThat(component.getAvoidanceBlend()).isEqualTo(100);
  }

  // ---------------------------------------------------------------------------------------
  // Who is skipped
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a unit taken out of physical interaction avoids nothing at all")
  void aUnitOutOfPhysicalInteractionDoesNothing() {
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));
    owner.setFlags(EntityFlags.DISABLE_PHYSICAL);

    run(List.of(obstacle(2, 3750, 10750)));

    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE, NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isZero();
  }

  @Test
  @DisplayName("a unit flagged as an obstacle itself avoids nothing at all")
  void aUnitFlaggedAsAnObstacleDoesNothing() {
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));
    owner.setFlags(EntityFlags.AVOIDANCE_AS_OBSTACLE);

    run(List.of(obstacle(2, 3750, 10750)));

    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE, NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isZero();
  }

  @Test
  @DisplayName("a neighbour out of physical interaction, or one refusing contact, is skipped")
  void neighboursThatTakeNoPartAreSkipped() {
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));
    GridEntity ghost = obstacle(2, 3750, 10750);
    ghost.setFlags(EntityFlags.DISABLE_PHYSICAL);
    run(List.of(ghost));
    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE, NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isZero();

    setUp();
    component.setRoute(Route.of(GOAL_NODE, NEXT_NODE));
    GridEntity refuses = obstacle(2, 3750, 10750);
    queries.neighbourContact.put(refuses, 0);
    run(List.of(refuses));
    assertThat(component.getRoute().toArray()).containsExactly(GOAL_NODE, NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isZero();
  }

  @Test
  @DisplayName("a unit on the ground ignores a neighbour in the air")
  void theTwoHeightLayersDoNotSeeEachOther() {
    component.setRoute(Route.of(NEXT_NODE));
    GridEntity flyer = mover(2, 3400, 10400, 0, -256, GridEntityState.MOVING);
    flyer.setZTotal(700);

    run(List.of(flyer));

    assertThat(component.getAvoidanceBlend()).isZero();
  }

  @Test
  @DisplayName("a unit with nothing around it is left alone")
  void aUnitStandingAloneKeepsItsBlend() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of());

    assertThat(component.getRoute().toArray()).containsExactly(NEXT_NODE);
    assertThat(component.getAvoidanceBlend()).isZero();
  }

  // ---------------------------------------------------------------------------------------
  // Several neighbours at once
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("with two obstacles the last one seen decides the side")
  void theLastObstacleDecides() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(obstacle(2, 3000, 10200), obstacle(3, 4000, 10200)));

    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);
  }

  @Test
  @DisplayName("an obstacle outranks a blocker, whichever came last")
  void anObstacleOutranksABlocker() {
    component.setRoute(Route.of(NEXT_NODE));
    GridEntity blocker = mover(3, 3400, 10400, 0, -256, GridEntityState.MOVING);
    queries.neighbourBlend.put(blocker, 15);

    run(List.of(obstacle(2, 3000, 10200), blocker));

    // The blocker's own blend would also have given +200; the obstacle's side is what is used.
    assertThat(component.getAvoidanceBlend()).isEqualTo(200);
  }

  @Test
  @DisplayName("with blockers only the last one's hint decides, and a blend already set stands")
  void withBlockersOnlyTheLastHintDecides() {
    component.setRoute(Route.of(NEXT_NODE));
    run(
        List.of(
            mover(2, 3000, 10200, 0, -256, GridEntityState.MOVING),
            mover(3, 4000, 10200, 0, -256, GridEntityState.MOVING)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(-200);

    setUp();
    component.setRoute(Route.of(NEXT_NODE));
    component.setAvoidanceBlend(40);
    run(
        List.of(
            mover(2, 3000, 10200, 0, -256, GridEntityState.MOVING),
            mover(3, 4000, 10200, 0, -256, GridEntityState.MOVING)));
    assertThat(component.getAvoidanceBlend()).isEqualTo(40);
  }

  @Test
  @DisplayName("the unit itself in the answer is skipped")
  void theUnitItselfIsSkipped() {
    component.setRoute(Route.of(NEXT_NODE));

    run(List.of(owner));

    assertThat(component.getAvoidanceBlend()).isZero();
  }
}
