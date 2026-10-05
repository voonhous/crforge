package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
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
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a state change does to a unit's route, its target-lost timer and its deploy countdown. */
class GridStateSetterTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

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
    unit.setFlagBits(BITS);
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
  @DisplayName(
      "entering either pathfinding state drops the damage pending on the unit and keeps its"
          + " duration")
  void enteringAPathfindingStateDropsThePendingDamage() {
    for (int state : new int[] {GridEntityState.SPAWN_PATHFIND, GridEntityState.INGAME_PATHFIND}) {
      unit.setState(GridEntityState.MOVING);
      unit.setPendingDamageAmount(120);
      unit.setPendingDamageDurationMs(300);
      setter.setState(unit, state);

      assertThat(unit.getState()).isEqualTo(state);
      assertThat(unit.getPendingDamageAmount()).isZero();
      assertThat(unit.getPendingDamageDurationMs()).isEqualTo(300);
    }
  }

  @Test
  @DisplayName("entering the standing, clone-setup and casting states empties the route too")
  void theOtherStoppingStatesEmptyTheRoute() {
    setter.setCasting(new GridStateSetter.Casting(new StateTimers(), 933, 50, false, () -> {}));
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
  @DisplayName(
      "a cast with neither a cast time nor a trigger delay runs its effect in the casting state's"
          + " entry, after the route is emptied, then goes back to the state it came from, whose"
          + " change alone runs the combat gate")
  void aCastWithNoCountdownsFiresInTheEntry() {
    StateTimers timers = new StateTimers();
    int[] gates = {0};
    GridStateSetter setter =
        new GridStateSetter(
            unit, movement, targeting, () -> null, -1, MovementConfig.forGroundUnit());
    List<String> seen = new ArrayList<>();
    setter.setCasting(
        new GridStateSetter.Casting(
            timers,
            0,
            0,
            false,
            () -> gates[0]++,
            0,
            () ->
                seen.add(
                    "effect in "
                        + unit.getState()
                        + " route empty "
                        + movement.getRoute().isEmpty())));
    unit.setState(GridEntityState.STANDING);
    movement.setRoute(Route.of(48 * WIDTH + 6, 47 * WIDTH + 7));

    setter.setState(unit, GridEntityState.CASTING);

    assertThat(seen).containsExactly("effect in " + GridEntityState.CASTING + " route empty true");
    assertThat(unit.getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(gates[0]).as("the gate of the change back only").isEqualTo(1);
    assertThat(unit.getPendingFlags() & BITS.castingAbility()).isNotZero();
  }

  @Test
  @DisplayName(
      "a cast with no countdowns whose effect sends the unit into the follow-up state stays there")
  void aCastWithNoCountdownsKeepsTheFollowUp() {
    StateTimers timers = new StateTimers();
    int[] gates = {0};
    GridStateSetter setter =
        new GridStateSetter(
            unit, movement, targeting, () -> null, -1, MovementConfig.forGroundUnit());
    setter.setCasting(
        new GridStateSetter.Casting(
            timers,
            0,
            0,
            false,
            () -> gates[0]++,
            500,
            () -> setter.setState(unit, GridEntityState.ABILITY_FOLLOW_UP)));
    unit.setState(GridEntityState.STANDING);

    setter.setState(unit, GridEntityState.CASTING);

    assertThat(unit.getState()).isEqualTo(GridEntityState.ABILITY_FOLLOW_UP);
    assertThat(timers.getAbilityCountdown()).isEqualTo(10);
    assertThat(gates[0]).as("the follow-up change's gate and the cast's own").isEqualTo(2);
  }

  @Test
  @DisplayName("a cast with no countdowns and no effect to run in the entry is refused")
  void aCastWithNoCountdownsAndNoEffectIsRefused() {
    setter.setCasting(new GridStateSetter.Casting(new StateTimers(), 0, 0, false, () -> {}));
    assertThatThrownBy(() -> setter.setState(unit, GridEntityState.CASTING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("casts with no cast time and no trigger delay");
  }

  @Test
  @DisplayName(
      "entering the casting state seeds the ability's countdowns in whole ticks, then runs the gate")
  void enteringCastingSeedsTheCountdowns() {
    StateTimers timers = new StateTimers();
    int[] gates = {0};
    setter.setCasting(new GridStateSetter.Casting(timers, 933, 50, false, () -> gates[0]++));

    setter.setState(unit, GridEntityState.CASTING);

    assertThat(timers.getAbilityCountdown()).as("933 ms").isEqualTo(18);
    assertThat(timers.getAbilityWarningCountdown()).as("50 ms").isEqualTo(1);
    assertThat(unit.getPendingFlags() & BITS.castingAbility()).isNotZero();
    assertThat(movement.getRoute().isEmpty()).isTrue();
    assertThat(gates[0]).as("the combat gate at the change's end").isEqualTo(1);
  }

  @Test
  @DisplayName("leaving the casting state before its effect fires leaves the ability pending")
  void leavingCastingEarlyLeavesItPending() {
    StateTimers timers = new StateTimers();
    int[] gates = {0};
    GridStateSetter withCharge =
        new GridStateSetter(
            unit, movement, targeting, () -> null, -1, MovementConfig.forGroundUnit());
    withCharge.setCasting(new GridStateSetter.Casting(timers, 933, 100, false, () -> gates[0]++));
    withCharge.setState(unit, GridEntityState.CASTING);

    withCharge.setState(unit, GridEntityState.STANDING);

    assertThat(timers.isAbilityReady()).isTrue();
    assertThat(unit.getPendingFlags() & BITS.abilityCooldownPaused()).isNotZero();
    assertThat(movement.getChargeProgress()).isEqualTo(MovementState.CHARGE_INACTIVE);
    assertThat(gates[0]).as("into the cast and out of it").isEqualTo(2);
  }

  @Test
  @DisplayName(
      "the charge reset leaves 0 for a row without a charge range when the unit's buffs give one")
  void aBuffsChargeRangeKeepsTheCharge() {
    StateTimers timers = new StateTimers();
    GridStateSetter withCharge =
        new GridStateSetter(
            unit, movement, targeting, () -> null, -1, MovementConfig.forGroundUnit());
    withCharge.setCasting(new GridStateSetter.Casting(timers, 933, 100, false, () -> {}));
    withCharge.setChargeRangeFromModifiers(() -> 250);
    withCharge.setState(unit, GridEntityState.CASTING);
    movement.setChargeProgress(MovementState.CHARGE_INACTIVE);

    withCharge.setState(unit, GridEntityState.STANDING);

    assertThat(movement.getChargeProgress()).isZero();
  }

  @Test
  @DisplayName("a unit without an ability may not enter the casting state")
  void castingWithoutAnAbilityIsRefused() {
    assertThatThrownBy(() -> setter.setState(unit, GridEntityState.CASTING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("without an ability");
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

    // A hook's state needs what the unit does beyond the setter's fields, here nothing.
    setter.setFollowing(
        new GridStateSetter.Following() {
          @Override
          public void components(boolean on) {}

          @Override
          public void movementOn() {}

          @Override
          public void dropReferenceOutOfRange() {}

          @Override
          public void standOrRelocate() {}

          @Override
          public void tailGate() {}
        });
    setter.setState(unit, GridEntityState.FOLLOWING_REMOVED);
    assertThat(unit.getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
  }

  @Test
  @DisplayName("a hook's state is refused to a setter not told what the unit does in it")
  void aHookStateNeedsTheUnit() {
    assertThatThrownBy(() -> setter.setState(unit, GridEntityState.COMPONENTS_DISABLED))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("hook's state");
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
    other.setFlagBits(BITS);
    other.setName("other");

    assertThatThrownBy(() -> setter.setState(other, GridEntityState.ATTACKING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner")
        .hasMessageContaining("other");
  }
}
