package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.move.GridMoveEntity;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementGlobals;
import org.crforge.core.pathfinding.move.MovementQueries;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.ReferencePoint;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a state change does to a unit's route, its target-lost timer and its deploy countdown. */
class GridStateSetterTest {

  private static final int WIDTH = 36;

  private GridEntity unit;
  private MovementState movement;
  private TargetingState targeting;
  private RoutingAnswers answers;
  private GridStateSetter setter;

  /** A movement pass whose search always answers the same three-node route. */
  private static final class RoutingAnswers implements MovementQueries {
    private final GridEntity unit;
    private final MovementConfig config;
    private ReferencePoint reference;
    private int searches;

    RoutingAnswers(GridEntity unit, MovementConfig config) {
      this.unit = unit;
      this.config = config;
    }

    @Override
    public Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust) {
      searches++;
      return Route.of(goalRow * WIDTH + goalCol, 20 * WIDTH + 7, 21 * WIDTH + 7);
    }

    @Override
    public int endpoint(int referenceCol, int referenceRow, int radius) {
      return (referenceCol << 16) | (referenceRow - 1);
    }

    @Override
    public int attackRange() {
      return 1700;
    }

    @Override
    public int farther() {
      return 0;
    }

    @Override
    public int relocate(int x, int y) {
      return (y << 16) | x;
    }

    @Override
    public int cellTest(int worldX, int worldY) {
      return 0;
    }

    @Override
    public int speedBudget() {
      return 60;
    }

    @Override
    public int facingGate() {
      return 1;
    }

    @Override
    public int avoidanceGate() {
      return 0;
    }

    @Override
    public int pushGate() {
      return 0;
    }

    @Override
    public int routeRequest() {
      return 1;
    }

    @Override
    public int ownerSide() {
      return unit.getSide();
    }

    @Override
    public int air() {
      return 0;
    }

    @Override
    public int ground() {
      return 1;
    }

    @Override
    public int referenceAvailable() {
      return reference == null ? 0 : 1;
    }

    @Override
    public int gridWidth() {
      return WIDTH;
    }

    @Override
    public GridMoveEntity entityView() {
      return GridMoveEntity.of(unit, config);
    }
  }

  @BeforeEach
  void setUp() {
    unit = new GridEntity();
    unit.setName("owner");
    unit.setSide(0);
    unit.setX(3500);
    unit.setY(10000);
    unit.setCollisionRadius(500);
    unit.setMovementActive(true);
    unit.setState(GridEntityState.MOVING);

    movement = MovementState.forSide(0, 3500, 10000);
    movement.setRoute(Route.of(48 * WIDTH + 6, 47 * WIDTH + 7, 46 * WIDTH + 7));
    movement.setRouteLeadsAway(1);

    targeting = new TargetingState();
    targeting.setOwner(unit);
    targeting.setTargetLostTimerMs(150);

    MovementConfig config = MovementConfig.forGroundUnit();
    answers = new RoutingAnswers(unit, config);
    CellGrid grid =
        new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);
    MovementGlobals globals = MovementGlobals.forStandardArena(WIDTH);
    setter =
        new GridStateSetter(
            unit,
            movement,
            targeting,
            () ->
                new MovementChain(
                    movement, unit, grid, config, globals, answers.reference, List.of(), answers));
  }

  @Test
  @DisplayName("entering the attacking state empties the route and clears the leads-away bit")
  void enteringAttackingEmptiesTheRoute() {
    setter.setState(unit, GridEntityState.ATTACKING);

    assertThat(unit.getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(movement.getRoute().isEmpty()).isTrue();
    assertThat(movement.getRouteLeadsAway()).isZero();
  }

  @Test
  @DisplayName("entering the standing, clone-setup and casting states empties the route too")
  void theOtherStoppingStatesEmptyTheRoute() {
    for (int state :
        new int[] {
          GridEntityState.STANDING, GridEntityState.CLONE_SETUP, GridEntityState.CASTING
        }) {
      unit.setState(GridEntityState.MOVING);
      movement.setRoute(Route.of(48 * WIDTH + 6, 47 * WIDTH + 7));

      setter.setState(unit, state);

      assertThat(movement.getRoute().isEmpty()).as("state %d", state).isTrue();
    }
  }

  @Test
  @DisplayName("leaving the attacking state clears the target-lost timer")
  void leavingAttackingClearsTheTargetLostTimer() {
    unit.setState(GridEntityState.ATTACKING);

    setter.setState(unit, GridEntityState.STANDING);

    assertThat(targeting.getTargetLostTimerMs()).isZero();
  }

  @Test
  @DisplayName("entering the moving state prepares a route over the current reference at once")
  void enteringMovingPreparesARoute() {
    unit.setState(GridEntityState.ATTACKING);
    movement.getRoute().clear();
    answers.reference = new ReferencePoint(3500, 25500);

    setter.setState(unit, GridEntityState.MOVING);

    assertThat(unit.getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(answers.searches).isEqualTo(1);
    assertThat(movement.getRoute().size()).isEqualTo(3);
    assertThat(movement.getRoute().get(0)).isEqualTo(50 * WIDTH + 7);
  }

  @Test
  @DisplayName("entering the moving state with no reference and no goal leaves the route alone")
  void enteringMovingWithoutAReferenceLeavesTheRoute() {
    unit.setState(GridEntityState.STANDING);
    movement.getRoute().clear();
    answers.reference = null;

    setter.setState(unit, GridEntityState.MOVING);

    assertThat(answers.searches).isZero();
    assertThat(movement.getRoute().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("asking for the state the unit already has does nothing")
  void theSameStateIsANoOp() {
    setter.setState(unit, GridEntityState.MOVING);

    assertThat(movement.getRoute().size()).isEqualTo(3);
    assertThat(answers.searches).isZero();
  }

  @Test
  @DisplayName("while the deploy countdown runs only clone setup and the following states apply")
  void theDeployCountdownGuardsTheState() {
    unit.setState(GridEntityState.DEPLOYING);
    unit.setDeployCountdown(500);

    setter.setState(unit, GridEntityState.ATTACKING);
    assertThat(unit.getState()).isEqualTo(GridEntityState.DEPLOYING);
    setter.setState(unit, GridEntityState.MOVING);
    assertThat(unit.getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(movement.getRoute().size()).isEqualTo(3);

    setter.setState(unit, GridEntityState.FOLLOWING_REMOVED);
    assertThat(unit.getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
  }

  @Test
  @DisplayName("leaving the deploying state for anything but clone setup clears the countdown")
  void leavingDeployingClearsTheCountdown() {
    unit.setState(GridEntityState.DEPLOYING);
    unit.setDeployCountdown(0);
    answers.reference = null;

    setter.setState(unit, GridEntityState.MOVING);

    assertThat(unit.getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(unit.getDeployCountdown()).isZero();
  }

  @Test
  @DisplayName("a unit without components changes state and nothing else")
  void aUnitWithoutComponentsOnlyStoresTheState() {
    GridStateSetter bare = new GridStateSetter(unit, null, null, () -> null);

    bare.setState(unit, GridEntityState.ATTACKING);
    bare.setState(unit, GridEntityState.MOVING);

    assertThat(unit.getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(movement.getRoute().size()).isEqualTo(3);
    assertThat(targeting.getTargetLostTimerMs()).isEqualTo(150);
  }

  @Test
  @DisplayName("the setter refuses to act on another entity")
  void theSetterBelongsToOneUnit() {
    GridEntity other = new GridEntity();
    other.setName("other");

    assertThatThrownBy(() -> setter.setState(other, GridEntityState.ATTACKING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner")
        .hasMessageContaining("other");
  }
}
