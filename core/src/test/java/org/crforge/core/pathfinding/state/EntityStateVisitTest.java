package org.crforge.core.pathfinding.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour of the per-tick entity visit that runs after the component passes. */
class EntityStateVisitTest {

  private static final int DEPLOY_TIME_MS = 1000;

  private static final StateVisitConfig CONFIG = StateVisitConfig.forGroundUnit(DEPLOY_TIME_MS);

  private GridEntity entity;
  private StateTimers timers;
  private MovementState movement;
  private List<String> chain;

  @BeforeEach
  void setUp() {
    entity = new GridEntity();
    entity.setSide(0);
    entity.setX(3500);
    entity.setY(10000);
    entity.setDirX(0);
    entity.setDirY(256);
    entity.setMovementActive(true);
    entity.setState(GridEntityState.DEPLOYING);
    entity.setDeployCountdown(DEPLOY_TIME_MS);
    timers = new StateTimers();
    movement = MovementState.forSide(0, 3500, 10000);
    chain = new ArrayList<>();
  }

  private void visit(StateVisitConfig config, StateQueries queries) {
    EntityStateVisit.stateVisit(
        entity,
        timers,
        movement,
        config,
        StateVisitGlobals.standard(),
        queries,
        chain,
        StateSetter.guarded());
  }

  private void visit() {
    visit(CONFIG, StateQueries.forUnitWithRoute(0));
  }

  @Test
  void aDeployingUnitHoldsStillForTwentyTicksAndThenMoves() {
    for (int tick = 0; tick < 19; tick++) {
      visit();
      assertThat(entity.getState()).as("tick %d", tick).isEqualTo(GridEntityState.DEPLOYING);
    }
    assertThat(entity.getDeployCountdown()).isEqualTo(50);

    visit();

    assertThat(entity.getDeployCountdown()).isZero();
    assertThat(entity.getState()).isEqualTo(GridEntityState.MOVING);
  }

  @Test
  void theAttackFinishLatchSurvivesFiveVisitsAndClearsOnTheSixth() {
    entity.setState(GridEntityState.MOVING);
    timers.setAttackFinishing(true);

    int[] expectedElapsed = {50, 100, 150, 200, 250};
    for (int visit = 0; visit < expectedElapsed.length; visit++) {
      visit();
      assertThat(timers.getAttackFinishElapsedMs())
          .as("elapsed after visit %d", visit + 1)
          .isEqualTo(expectedElapsed[visit]);
      assertThat(timers.isAttackFinishing())
          .as("still finishing after visit %d", visit + 1)
          .isTrue();
    }

    visit();

    assertThat(timers.getAttackFinishElapsedMs()).isEqualTo(300);
    assertThat(timers.isAttackFinishing()).isFalse();
  }

  @Test
  void aUnitThatMayNotHoldARouteStandsInsteadOfMoving() {
    StateQueries standing = StateQueries.forUnitWithRoute(0).withMayHoldRoute(false);
    for (int tick = 0; tick < 20; tick++) {
      visit(CONFIG, standing);
    }

    assertThat(entity.getState()).isEqualTo(GridEntityState.STANDING);
  }

  @Test
  void theDeployCountdownDoesNotAdvanceTheOrdinaryElapsedTime() {
    visit();

    assertThat(entity.getDelay()).isZero();
  }

  @Test
  void anOrdinaryStateAdvancesTheElapsedTimeByOneTick() {
    entity.setState(GridEntityState.MOVING);
    entity.setDeployCountdown(0);

    visit();

    assertThat(entity.getDelay()).isEqualTo(50);
  }

  @Test
  void aStaggeredUnitCountsDownAndThenStartsDeploying() {
    entity.setState(GridEntityState.WAITING_TO_DEPLOY);
    entity.setDeployCountdown(0);
    entity.setDelay(100);

    visit();
    assertThat(entity.getState()).isEqualTo(GridEntityState.WAITING_TO_DEPLOY);
    assertThat(entity.getDelay()).isEqualTo(50);

    visit();
    assertThat(entity.getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(entity.getDelay()).isZero();
  }

  @Test
  void aLandedDashReleasesTheUnitBackIntoMovement() {
    StateVisitConfig config =
        new StateVisitConfig(
            DEPLOY_TIME_MS,
            100,
            0,
            0,
            false,
            false,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            0L);
    entity.setState(GridEntityState.STANDING);
    entity.setDeployCountdown(0);
    entity.setBlockCountdownMs(50);

    visit(config, StateQueries.forUnitWithRoute(0));

    assertThat(entity.getBlockCountdownMs()).isZero();
    assertThat(entity.getState()).isEqualTo(GridEntityState.MOVING);
  }

  @Test
  void aSpawnPathfindingUnitThatRanOutOfRouteGoesBackToDeploying() {
    entity.setState(GridEntityState.SPAWN_PATHFIND);
    entity.setDeployCountdown(0);
    movement.setExplicitX(4000);
    movement.setExplicitY(11000);

    visit();

    assertThat(entity.getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(entity.getX()).isEqualTo(4000);
    assertThat(entity.getY()).isEqualTo(11000);
    assertThat(entity.getDirX()).isZero();
    assertThat(entity.getDirY()).isEqualTo(256);
    assertThat(movement.getExplicitX()).isEqualTo(-1);
    assertThat(movement.getExplicitY()).isEqualTo(-1);
  }

  @Test
  void aMidMatchPathfindingUnitThatRanOutOfRouteStartsMoving() {
    entity.setState(GridEntityState.INGAME_PATHFIND);
    entity.setDeployCountdown(0);

    visit();

    assertThat(entity.getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(movement.getExplicitX()).isEqualTo(-1);
  }

  @Test
  void aMorphingUnitCountsItsMorphDownAndThenStands() {
    entity.setState(GridEntityState.MORPHING);
    entity.setDeployCountdown(0);
    timers.setMorphCountdownMs(50);

    visit();

    assertThat(entity.getState()).isEqualTo(GridEntityState.STANDING);
  }

  @Test
  void theStateSetterRefusesEverythingButTheThreeAllowedStatesWhileDeploying() {
    StateSetter setter = StateSetter.guarded();
    entity.setState(GridEntityState.DEPLOYING);
    entity.setDeployCountdown(500);

    setter.setState(entity, GridEntityState.ATTACKING);
    assertThat(entity.getState()).isEqualTo(GridEntityState.DEPLOYING);

    setter.setState(entity, GridEntityState.CLONE_SETUP);
    assertThat(entity.getState()).isEqualTo(GridEntityState.CLONE_SETUP);
  }

  @Test
  void theStateSetterAppliesEveryRequestOnceTheDeployCountdownHasRun() {
    StateSetter setter = StateSetter.guarded();
    entity.setState(GridEntityState.DEPLOYING);
    entity.setDeployCountdown(0);

    setter.setState(entity, GridEntityState.ATTACKING);

    assertThat(entity.getState()).isEqualTo(GridEntityState.ATTACKING);
  }

  @Test
  void aDroppedReferenceResumesIntoMovement() {
    entity.setState(GridEntityState.ATTACKING);
    List<String> calls = new ArrayList<>();

    ResumeHelper.resume(
        entity, CONFIG, StateQueries.forUnitWithRoute(0), calls, StateSetter.direct());

    assertThat(entity.getState()).isEqualTo(GridEntityState.MOVING);
  }

  @Test
  void aUnitThatMayNotRouteResumesIntoStanding() {
    entity.setState(GridEntityState.ATTACKING);
    List<String> calls = new ArrayList<>();

    ResumeHelper.resume(
        entity,
        CONFIG,
        StateQueries.forUnitWithRoute(0).withMayHoldRoute(false),
        calls,
        StateSetter.direct());

    assertThat(entity.getState()).isEqualTo(GridEntityState.STANDING);
  }

  @Test
  void aMorphingUnitIsNeverResumed() {
    entity.setState(GridEntityState.MORPHING);
    List<String> calls = new ArrayList<>();

    ResumeHelper.resume(
        entity, CONFIG, StateQueries.forUnitWithRoute(0), calls, StateSetter.direct());

    assertThat(entity.getState()).isEqualTo(GridEntityState.MORPHING);
  }
}
