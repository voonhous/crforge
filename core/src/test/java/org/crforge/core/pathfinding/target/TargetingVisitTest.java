package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The per-tick targeting pass: its timers, its attack decision and what it asks the caller for. */
class TargetingVisitTest {

  private TargetingState knight;
  private GridEntity unit;
  private MovementState movement;
  private TargetingOutcome outcome;
  private TargetView tower;
  private Queries queries;

  /** A hit sink and a selection that answer from the test. */
  private final class Queries implements TargetingQueries {
    private final List<int[]> hits = new ArrayList<>();
    private TargetView selection;
    private int selectionCalls;

    /** The attack sequence step the on-starting-attack action saw, one entry per run. */
    private final List<Integer> actionSawStep = new ArrayList<>();

    /** What the next-step query answers, so a test can tell the step apart from the entry value. */
    private int nextStep;

    @Override
    public void onStartingAttack() {
      actionSawStep.add(knight.getAttackSequenceIndex());
    }

    @Override
    public int nextAttackSequenceStep(int attackTimerMs) {
      return nextStep;
    }

    @Override
    public TargetView runSelection() {
      selectionCalls++;
      return selection;
    }

    @Override
    public boolean validateReference(int mode) {
      return ReferenceValidator.validate(
          knight, knight.getReference(), mode, ValidatorQueries.standard1v1());
    }

    /** What the sink answers: true means nothing landed, false means the hit was applied. */
    private boolean nothingLands;

    @Override
    public HitSink hitSink() {
      return (target, sequenceIndex, extra, last) -> {
        hits.add(new int[] {sequenceIndex, extra, last ? 1 : 0});
        return nothingLands;
      };
    }
  }

  @BeforeEach
  void setUp() {
    unit = new GridEntity();
    unit.setName("owner");
    unit.setId(7);
    unit.setSide(0);
    unit.setX(3500);
    unit.setY(24000);
    unit.setCollisionRadius(500);
    unit.setState(GridEntityState.MOVING);
    unit.setTargetable(1);

    knight = new TargetingState();
    knight.setOwner(unit);
    knight.setConfig(TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    knight.setMovementComponentActive(true);

    GridEntity towerEntity = new GridEntity();
    towerEntity.setName("PrincessTower_1_1");
    towerEntity.setId(5);
    towerEntity.setSide(1);
    towerEntity.setX(3500);
    towerEntity.setY(25500);
    towerEntity.setCollisionRadius(1000);
    towerEntity.setBuilding(true);
    towerEntity.setKingCandidate(1);
    towerEntity.setTargetable(1);
    tower =
        new TargetView(
            towerEntity, TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));

    movement = new MovementState();
    outcome = new TargetingOutcome();
    queries = new Queries();
    queries.selection = tower;
  }

  private void visit() {
    outcome.clear();
    TargetingVisit.targetingVisit(knight, unit, movement, queries, outcome);
  }

  @Test
  @DisplayName("the two leading countdowns step by one tick every visit")
  void theLeadingCountdownsStep() {
    knight.setLoadTimerMs(700);
    knight.setRetargetCooldownMs(100);

    visit();

    assertThat(knight.getLoadTimerMs()).isEqualTo(650);
    assertThat(knight.getRetargetCooldownMs()).isEqualTo(50);
  }

  @Test
  @DisplayName("a countdown at zero stays at zero rather than going negative")
  void countdownsDoNotGoNegative() {
    visit();

    assertThat(knight.getLoadTimerMs()).isZero();
    assertThat(knight.getRetargetCooldownMs()).isZero();
  }

  @Test
  @DisplayName("nothing happens while the unit is pathfinding or morphing")
  void theThreeSkippedStates() {
    for (int state :
        new int[] {
          GridEntityState.SPAWN_PATHFIND, GridEntityState.INGAME_PATHFIND, GridEntityState.MORPHING
        }) {
      knight.setReference(null);
      unit.setState(state);

      visit();

      assertThat(knight.getReference()).as("state %d", state).isNull();
      assertThat(queries.selectionCalls).isZero();
    }
  }

  @Test
  @DisplayName("a unit without a target re-selects every tick and asks to keep moving")
  void aUnitWithoutATargetReselects() {
    unit.setY(10000);

    visit();

    assertThat(queries.selectionCalls).isEqualTo(1);
    assertThat(knight.getReference()).isSameAs(tower);
    assertThat(outcome.isResumeRequested()).isTrue();
    assertThat(unit.getState()).isEqualTo(GridEntityState.MOVING);
  }

  @Test
  @DisplayName("the on-starting-attack action runs before the attack sequence steps on")
  void theActionRunsBeforeTheSequenceSteps() {
    knight.setConfig(
        knight.getConfig().toBuilder()
            .attackSequenceMode(2)
            .attackSequenceLength(2)
            .attackSequenceStepIds(List.of(0, 1))
            .attackSequenceEntries(List.of(AttackSequenceEntry.none(), AttackSequenceEntry.none()))
            .build());
    knight.setReference(tower);
    queries.nextStep = 1;

    visit();

    assertThat(queries.actionSawStep)
        .as("the action saw the step the attack started on")
        .containsExactly(TargetingState.NO_SEQUENCE_STEP);
    assertThat(knight.getAttackSequenceIndex()).isEqualTo(1);
  }

  @Test
  @DisplayName("a wind-up-first unit stays loaded when nothing lands and reloads when a hit does")
  void theWindUpFollowsWhetherTheHitLanded() {
    TargetingGlobals globals =
        TargetingGlobals.standard1v1().toBuilder()
            .loadFirstHitResetTimerAfterAttack(true)
            .loadFirstHitKeepLoadedAfterDiscard(true)
            .build();
    knight.setGlobals(globals);
    knight.setConfig(knight.getConfig().toBuilder().loadFirstHit(true).build());
    knight.setReference(tower);

    // One tick short of the hit speed, so this visit's step lands the hit.
    knight.setAttackTimerMs(1150);
    queries.nothingLands = true;
    visit();

    assertThat(queries.hits).hasSize(1);
    assertThat(knight.getLoadTimerMs()).as("nothing landed, so the wind-up stays spent").isZero();

    knight.clearAttack();
    knight.setAttackTimerMs(1150);
    queries.nothingLands = false;
    visit();

    assertThat(knight.getLoadTimerMs())
        .as("the hit landed, so the wind-up is reloaded")
        .isEqualTo(700);
  }

  @Test
  @DisplayName("a unit in range attacks, enters the attacking state and advances its attack timer")
  void aUnitInRangeAttacks() {
    knight.setReference(tower);

    visit();

    assertThat(unit.getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(knight.getAttackTimerMs()).isEqualTo(50);
    assertThat(queries.selectionCalls).isZero();
    assertThat(queries.hits).isEmpty();
  }

  @Test
  @DisplayName("the first hit lands once the attack timer reaches the hit speed")
  void theFirstHitLandsAtTheHitSpeed() {
    knight.setReference(tower);

    for (int i = 0; i < 24; i++) {
      visit();
    }

    assertThat(knight.getAttackTimerMs()).isEqualTo(1200);
    assertThat(queries.hits).hasSize(1);
    assertThat(queries.hits.get(0)).containsExactly(-1, 0, 1);
  }

  @Test
  @DisplayName("a target that dies is given up and the unit is asked to resume")
  void aDeadTargetIsGivenUp() {
    knight.setReference(tower);
    visit();
    assertThat(unit.getState()).isEqualTo(GridEntityState.ATTACKING);

    tower.getEntity().setAlive(false);
    queries.selection = null;
    unit.setState(GridEntityState.ATTACKING);

    visit();

    assertThat(knight.getReference()).isNull();
    assertThat(outcome.isResumeRequested()).isTrue();
    assertThat(knight.getAttackTimerMs()).isZero();
  }

  @Test
  @DisplayName("dropping a target remembers it, asks for a new route and keeps the attack timing")
  void clearingTheReferenceAsksForARoute() {
    knight.setReference(tower);
    knight.setAttackTimerMs(600);

    TargetingVisit.clearReference(knight, unit, outcome);

    assertThat(knight.getReference()).isNull();
    assertThat(knight.getPreviousReference()).isSameAs(tower);
    assertThat(outcome.isRoutePreparationRequested()).isTrue();
    // A unit with none of the wind-up or hit-timer columns keeps the attack time it had built up.
    assertThat(knight.getAttackTimerMs()).isEqualTo(600);
  }

  @Test
  @DisplayName("a unit that restarts its hit timer without a target has its attack cleared")
  void clearingTheReferenceResetsTheAttackWhenTheColumnSaysSo() {
    knight.setConfig(knight.getConfig().toBuilder().resetHitTimerWhenNoTarget(true).build());
    knight.setReference(tower);
    knight.setAttackTimerMs(600);
    knight.setHitInProgress(true);

    TargetingVisit.clearReference(knight, unit, outcome);

    assertThat(knight.getAttackTimerMs()).isZero();
    assertThat(knight.isHitInProgress()).isFalse();
  }

  @Test
  @DisplayName("a unit whose wind-up runs before its first hit reloads it when it loses its target")
  void windUpIsReloadedWhenTheTargetIsLost() {
    knight.setConfig(knight.getConfig().toBuilder().loadFirstHit(true).build());
    knight.setReference(tower);
    knight.setAttackTimerMs(600);

    TargetingVisit.clearReference(knight, unit, outcome);

    assertThat(knight.getAttackTimerMs()).isZero();
    assertThat(knight.getLoadTimerMs()).isEqualTo(700);
  }

  @Test
  @DisplayName("a unit being pushed back does not attack")
  void pushbackStopsTheAttack() {
    knight.setReference(tower);
    movement.setPushbackInFlight(1);

    visit();

    assertThat(unit.getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(knight.getAttackTimerMs()).isZero();
  }

  @Test
  @DisplayName("a unit forbidden to attack clears its attack fields")
  void theNoAttackFlagClearsTheAttack() {
    knight.setReference(tower);
    knight.setAttackTimerMs(600);
    unit.setFlags(EntityFlags.NO_ATTACK);

    visit();

    assertThat(knight.getAttackTimerMs()).isZero();
    assertThat(unit.getState()).isEqualTo(GridEntityState.MOVING);
  }

  @Test
  @DisplayName("the visit skips one selection when it has been told to")
  void selectionCanBeSkippedOnce() {
    unit.setY(10000);
    knight.setSkipSelectionNextVisit(true);

    visit();
    assertThat(queries.selectionCalls).isZero();
    assertThat(knight.isSkipSelectionNextVisit()).isFalse();

    visit();
    assertThat(queries.selectionCalls).isEqualTo(1);
  }

  @Test
  @DisplayName("a running visit hold returns after stepping itself")
  void theVisitHoldReturnsEarly() {
    unit.setY(10000);
    knight.setVisitHoldMs(150);

    visit();

    assertThat(knight.getVisitHoldMs()).isEqualTo(100);
    assertThat(queries.selectionCalls).isZero();
  }
}
