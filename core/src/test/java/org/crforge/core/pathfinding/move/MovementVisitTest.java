package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour of one whole movement visit driven through the chain. */
class MovementVisitTest {

  private static final int WIDTH = 36;

  /** Column 7 row 21, whose centre is at (3750, 10750). */
  private static final int NEAR_NODE = 21 * WIDTH + 7;

  /** Column 7 row 22, whose centre is at (3750, 11250). */
  private static final int NEXT_NODE = 22 * WIDTH + 7;

  /** The goal cell of the left lane, column 6 row 48. */
  private static final int GOAL_NODE = 48 * WIDTH + 6;

  private MovementState component;
  private GridEntity owner;
  private StubMovementQueries queries;
  private CellGrid grid;

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
    owner.setCollisionRadius(500);
    owner.setMass(6);
    queries = new StubMovementQueries();
    queries.speedBudget = 60;
    queries.pushGate = 1;
    queries.routeRequest = 0;
    grid = new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);
  }

  private MovementChain visit() {
    MovementChain chain =
        new MovementChain(
            component,
            owner,
            grid,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(WIDTH),
            new ReferencePoint(3500, 25500),
            List.of(),
            queries);
    MovementVisit.movementVisit(
        component, owner, null, MovementConfig.forGroundUnit(), null, queries, false, chain);
    return chain;
  }

  @Test
  void aUnitWithoutARouteStepsTowardItsOwnPositionAndStaysPut() {
    MovementChain chain = visit();

    assertThat(owner.getX()).isEqualTo(3500);
    assertThat(owner.getY()).isEqualTo(10000);
    assertThat(component.getWaypointReached()).isEqualTo(1);
    assertThat(component.getWorkVector()).containsExactly(3500, 10000);
    assertThat(component.getMoveTimeMs()).isEqualTo(50);
    assertThat(chain.markers())
        .containsExactly(
            "route_query",
            "avoidance_gate",
            "push_gate",
            "owner_radius",
            "owner_push_enabled",
            "neighbour_query",
            "owner_side",
            "release",
            "speed_budget",
            "modifier_component",
            "facing_gate",
            "modifier_component");
  }

  @Test
  void aUnitWalksTowardItsNextWaypointAndConsumesItOnArrival() {
    component.setRoute(Route.of(NEAR_NODE));

    MovementChain chain = visit();

    assertThat(owner.getX()).isEqualTo(3518);
    assertThat(owner.getY()).isEqualTo(10056);
    assertThat(component.getWorkVector()).containsExactly(3750, 10750);
    assertThat(component.getRoute().isEmpty()).isTrue();
    assertThat(component.getRouteDirX()).isZero();
    assertThat(component.getRouteDirY()).isZero();
    assertThat(chain.markers()).endsWith("facing_gate", "modifier_component", "direction_init");
  }

  @Test
  void onlyTheLastNodeIsConsumedWhenTheBudgetAllowsOneStep() {
    owner.setY(11000);
    component.setRouteDirX(0);
    component.setRouteDirY(256);
    component.setRoute(Route.of(NEAR_NODE, NEXT_NODE));

    visit();

    assertThat(owner.getX()).isEqualTo(3542);
    assertThat(owner.getY()).isEqualTo(11042);
    assertThat(owner.getDirX()).isEqualTo(181);
    assertThat(owner.getDirY()).isEqualTo(181);
    assertThat(component.getRoute().toArray()).containsExactly(NEAR_NODE);
  }

  @Test
  void aRoutingStateAsksForARouteBeforeItFollowsIt() {
    queries.routeRequest = 1;
    queries.endpoint = (6 << 16) | 48;
    queries.searchResult = Route.of(GOAL_NODE, 47 * WIDTH + 7, 46 * WIDTH + 7, NEAR_NODE);

    MovementChain chain = visit();

    assertThat(component.getRoute().toArray())
        .containsExactly(GOAL_NODE, 47 * WIDTH + 7, 46 * WIDTH + 7);
    assertThat(owner.getX()).isEqualTo(3518);
    assertThat(owner.getY()).isEqualTo(10056);
    assertThat(chain.markers())
        .startsWith(
            "route_query",
            "endpoint",
            "owner_side",
            "ground",
            "search",
            "search_notify",
            "search_stats",
            "farther",
            "direction_init",
            "avoidance_gate");
  }

  @Test
  void anAttackingUnitSpendsNoBudgetAtAll() {
    owner.setState(GridEntityState.ATTACKING);
    queries.speedBudget = 0;
    queries.facingGate = 0;
    component.setRoute(Route.of(NEAR_NODE));

    visit();

    assertThat(owner.getX()).isEqualTo(3500);
    assertThat(owner.getY()).isEqualTo(10000);
    assertThat(owner.getDirY()).isEqualTo(256);
    assertThat(component.getMoveTimeMs()).isZero();
  }

  @Test
  void aMorphingUnitDoesNothingAtAll() {
    owner.setState(GridEntityState.MORPHING);
    component.setRoute(Route.of(NEAR_NODE));

    MovementChain chain = visit();

    assertThat(owner.getX()).isEqualTo(3500);
    assertThat(component.getRoute().size()).isEqualTo(1);
    assertThat(chain.markers()).isEmpty();
  }

  @Test
  void aBlockedUnitReplaysItsLastDisplacementAndCountsTheBlockDown() {
    component.setBlockCountdown(2);
    component.setPushbackBudget(60);
    component.setTargetX(3250);
    component.setTargetY(10250);

    MovementChain chain = visit();

    assertThat(component.getBlockCountdown()).isEqualTo(1);
    assertThat(component.getPushbackBudget()).isEqualTo(60);
    assertThat(owner.getX()).isEqualTo(3458);
    assertThat(owner.getY()).isEqualTo(10042);
    assertThat(chain.markers()).doesNotContain("route_query");
  }

  @Test
  void aBlockCountdownThatRunsOutClearsThePushbackBudget() {
    component.setBlockCountdown(1);
    component.setPushbackBudget(60);
    component.setTargetX(3500);
    component.setTargetY(10000);

    visit();

    assertThat(component.getBlockCountdown()).isZero();
    assertThat(component.getPushbackBudget()).isZero();
  }

  @Test
  void anInFlightPushbackLosesTwentyFiveOfItsBudgetPerVisit() {
    component.setPushbackInFlight(1);
    component.setPushbackBudget(60);
    component.setTargetX(3250);
    component.setTargetY(10250);

    visit();

    assertThat(component.getPushbackBudget()).isEqualTo(35);
    assertThat(component.getPushbackInFlight()).isEqualTo(1);
  }

  @Test
  void aPushbackEndsOnceItsBudgetGoesNegative() {
    component.setPushbackInFlight(1);
    component.setPushbackBudget(10);
    component.setTargetX(3250);
    component.setTargetY(10250);

    visit();

    assertThat(component.getPushbackBudget()).isEqualTo(-15);
    assertThat(component.getPushbackInFlight()).isZero();
  }
}
