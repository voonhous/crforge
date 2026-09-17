package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.Test;

/** Behaviour of the per-visit movement budget and of the three gates that ride along with it. */
class SpeedBudgetTest {

  private static final int KNIGHT_SPEED = 60;

  private static final SpeedConfig CONFIG = SpeedConfig.forGroundUnit(KNIGHT_SPEED);

  private static SpeedInputs inputs(int state) {
    return new SpeedInputs(0L, state, false, 0, 0, 0, 0, new int[0], true, -1);
  }

  @Test
  void movingGroundUnitSpendsItsWholeSpeed() {
    assertThat(
            SpeedBudget.speedBudget(
                inputs(GridEntityState.MOVING), CONFIG, SpeedGlobals.standard()))
        .isEqualTo(60);
  }

  @Test
  void statesThatHoldStillSpendNothing() {
    assertThat(
            SpeedBudget.speedBudget(
                inputs(GridEntityState.ATTACKING), CONFIG, SpeedGlobals.standard()))
        .isZero();
    assertThat(
            SpeedBudget.speedBudget(
                inputs(GridEntityState.DEPLOYING), CONFIG, SpeedGlobals.standard()))
        .isZero();
  }

  @Test
  void pathfindStatesUseTheirOwnSpeedColumns() {
    assertThat(
            SpeedBudget.speedBudget(
                inputs(GridEntityState.SPAWN_PATHFIND), CONFIG, SpeedGlobals.standard()))
        .isZero();
    assertThat(
            SpeedBudget.speedBudget(
                inputs(GridEntityState.INGAME_PATHFIND), CONFIG, SpeedGlobals.standard()))
        .isZero();
  }

  @Test
  void aDashWindUpStopsTheUnitEvenWhileMoving() {
    SpeedInputs windingUp =
        new SpeedInputs(0L, GridEntityState.MOVING, true, 1, 0, 0, 0, new int[0], true, -1);
    assertThat(SpeedBudget.speedBudget(windingUp, CONFIG, SpeedGlobals.standard())).isZero();
    assertThat(MovementGates.facingGate(windingUp)).isZero();
    assertThat(MovementGates.avoidanceGate(windingUp)).isZero();
    assertThat(MovementGates.pushGate(windingUp, CONFIG)).isZero();
  }

  @Test
  void aSlowModifierScalesTheBudgetDown() {
    SpeedInputs slowed =
        new SpeedInputs(0L, GridEntityState.MOVING, false, 0, 0, 0, 0, new int[] {-35}, true, -1);
    assertThat(SpeedBudget.speedBudget(slowed, CONFIG, SpeedGlobals.standard())).isEqualTo(39);
  }

  @Test
  void theLargestBoostAndTheLargestSlowBothApply() {
    assertThat(SpeedBudget.speedModifier(new int[] {130, -35}, 60)).isEqualTo(50);
  }

  @Test
  void aCompleteChargeScalesTheBudgetByItsMultiplier() {
    SpeedConfig doubled = new SpeedConfig(KNIGHT_SPEED, 0, 0, 0, 200, false);
    SpeedInputs charged =
        new SpeedInputs(
            0L,
            GridEntityState.MOVING,
            false,
            0,
            0,
            0,
            0,
            new int[0],
            true,
            MovementState.CHARGE_COMPLETE);
    assertThat(SpeedBudget.speedBudget(charged, doubled, SpeedGlobals.standard())).isEqualTo(120);
  }

  @Test
  void theBudgetGivesOneDisplacementPerTickForAGroundUnit() {
    assertThat(
            SpeedBudget.speedBudget(inputs(GridEntityState.MOVING), CONFIG, SpeedGlobals.standard())
                / 250)
        .isZero();
  }

  @Test
  void theFacingGateIsShutInTheStatesThatMayNotTurn() {
    assertThat(MovementGates.facingGate(inputs(GridEntityState.STANDING))).isZero();
    assertThat(MovementGates.facingGate(inputs(GridEntityState.ATTACKING))).isZero();
    assertThat(MovementGates.facingGate(inputs(GridEntityState.CLONE_SETUP))).isZero();
    assertThat(MovementGates.facingGate(inputs(GridEntityState.MOVING))).isEqualTo(1);
  }

  @Test
  void thePushGateIsShutWhileSpawnPathfinding() {
    assertThat(MovementGates.pushGate(inputs(GridEntityState.SPAWN_PATHFIND), CONFIG)).isZero();
    assertThat(MovementGates.pushGate(inputs(GridEntityState.MOVING), CONFIG)).isEqualTo(1);
    assertThat(MovementGates.pushGate(inputs(GridEntityState.ATTACKING), CONFIG)).isEqualTo(1);
  }

  @Test
  void theAvoidanceGateIsShutWhileDashingOrJumping() {
    assertThat(MovementGates.avoidanceGate(inputs(GridEntityState.DASHING))).isZero();
    assertThat(MovementGates.avoidanceGate(inputs(GridEntityState.JUMPING))).isZero();
    assertThat(MovementGates.avoidanceGate(inputs(GridEntityState.MOVING))).isEqualTo(1);
  }
}
