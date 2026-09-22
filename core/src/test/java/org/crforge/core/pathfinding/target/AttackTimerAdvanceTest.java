package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.IntUnaryOperator;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How one attack tick moves the attack time, the load countdown and the burst timer. */
class AttackTimerAdvanceTest {

  /** Knight columns: hit speed 1200 ms, load 700 ms. */
  private static final int HIT_SPEED = 1200;

  private static final int LOAD = 700;

  private TargetingState t;
  private TargetingConfig cfg;
  private TargetView tower;
  private Queries queries;

  /** The two answers the advance pulls from outside. */
  private static final class Queries implements TargetingQueries {
    private boolean held;
    private IntUnaryOperator scale = base -> base;

    @Override
    public boolean attackTimersHeld() {
      return held;
    }

    @Override
    public int scaleTimeStep(int baseMs) {
      return scale.applyAsInt(baseMs);
    }

    @Override
    public TargetView runSelection() {
      return null;
    }

    @Override
    public boolean validateReference(int mode) {
      return true;
    }

    @Override
    public HitSink hitSink() {
      return (target, sequenceIndex, extra, last) -> false;
    }
  }

  @BeforeEach
  void setUp() {
    GridEntity owner = new GridEntity();
    owner.setName("owner");
    cfg = TargetingConfig.forUnit(1200, 5500, 500, HIT_SPEED, LOAD, true, false);
    t = new TargetingState();
    t.setOwner(owner);
    t.setConfig(cfg);
    GridEntity towerEntity = new GridEntity();
    towerEntity.setName("tower");
    tower =
        new TargetView(towerEntity, TargetingConfig.tower("tower", 7500, 7500, 1000, 800, 0, true));
    queries = new Queries();
  }

  private void advance() {
    AttackTimerAdvance.advance(t, cfg, queries);
  }

  @Test
  @DisplayName("an attack starting from zero is credited its whole load when the countdown is out")
  void aFullLoadIsCreditedAtTheStart() {
    t.setReference(tower);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(LOAD + 50);
    assertThat(t.getLoadTimerMs()).as("the countdown is reloaded").isEqualTo(LOAD);
    assertThat(t.isHitInProgressWithoutReference())
        .as("the loaded time is at least the load, and a reference is held")
        .isTrue();
  }

  @Test
  @DisplayName("a countdown still running is credited only the part that has recharged")
  void aPartlyRechargedLoadIsCreditedInPart() {
    t.setReference(tower);
    t.setLoadTimerMs(500);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(200 + 50);
    assertThat(t.getLoadTimerMs()).isEqualTo(LOAD);
    assertThat(t.isHitInProgressWithoutReference()).isFalse();
  }

  @Test
  @DisplayName("a full credit without a reference does not raise the keep-attacking flag")
  void aFullCreditWithoutAReferenceLeavesTheFlag() {
    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(LOAD + 50);
    assertThat(t.isHitInProgressWithoutReference()).isFalse();
  }

  @Test
  @DisplayName("an attack already under way just takes the step and leaves the countdown alone")
  void aRunningAttackOnlySteps() {
    t.setAttackTimerMs(750);
    t.setLoadTimerMs(650);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(800);
    assertThat(t.getLoadTimerMs()).isEqualTo(650);
  }

  @Test
  @DisplayName("a load longer than the hit speed credits nothing and clears a short countdown")
  void aLongLoadWithAShortCountdownIsCleared() {
    cfg = cfg.toBuilder().loadTime(1500).build();
    t.setConfig(cfg);
    t.setReference(tower);
    t.setLoadTimerMs(1000);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(50);
    assertThat(t.getLoadTimerMs()).isZero();
    assertThat(t.isHitInProgressWithoutReference()).isTrue();
  }

  @Test
  @DisplayName("a load longer than the hit speed with a long countdown ends the advance")
  void aLongLoadWithALongCountdownEndsTheAdvance() {
    cfg = cfg.toBuilder().loadTime(1500).build();
    t.setConfig(cfg);
    t.setReference(tower);
    t.setLoadTimerMs(1300);
    t.setHitInProgressWithoutReference(true);

    advance();

    assertThat(t.getAttackTimerMs()).isZero();
    assertThat(t.getLoadTimerMs()).isEqualTo(1300);
    assertThat(t.isHitInProgressWithoutReference()).isFalse();
  }

  @Test
  @DisplayName("a battle that holds the attack timers clears the attack time and nothing else")
  void aHeldBattleClearsTheAttackTime() {
    queries.held = true;
    t.setAttackTimerMs(600);
    t.setLoadTimerMs(300);
    t.setHitInProgressWithoutReference(true);
    t.setAttackTimeRoundedUp(true);

    advance();

    assertThat(t.getAttackTimerMs()).isZero();
    assertThat(t.getLoadTimerMs()).isEqualTo(300);
    assertThat(t.isHitInProgressWithoutReference()).isFalse();
    assertThat(t.isAttackTimeRoundedUp()).isFalse();
  }

  @Test
  @DisplayName("a step scaled below one millisecond moves nothing")
  void aStepUnderOneMovesNothing() {
    queries.scale = base -> 0;
    t.setAttackTimerMs(600);
    t.setHitInProgressWithoutReference(true);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(600);
    assertThat(t.isHitInProgressWithoutReference()).isFalse();
  }

  @Test
  @DisplayName("the step is half the current sequence step's multiplier, halved toward zero")
  void theStepFollowsTheSequenceStepsMultiplier() {
    cfg =
        cfg.toBuilder()
            .attackSequenceLength(2)
            .attackSequenceStepIds(List.of(0, 1))
            .attackSequenceEntries(
                List.of(
                    new AttackSequenceEntry(-1, -1, -1, 200),
                    new AttackSequenceEntry(-1, -1, -1, -3)))
            .build();
    t.setConfig(cfg);
    t.setAttackTimerMs(600);

    t.setAttackSequenceIndex(0);
    advance();
    assertThat(t.getAttackTimerMs()).as("multiplier 200 steps 100").isEqualTo(700);

    t.setAttackSequenceIndex(1);
    advance();
    assertThat(t.getAttackTimerMs()).as("multiplier -3 halves to -1, under one").isEqualTo(700);

    t.setAttackSequenceIndex(TargetingState.NO_SEQUENCE_STEP);
    advance();
    assertThat(t.getAttackTimerMs()).as("no active step: the plain tick").isEqualTo(750);
  }

  @Test
  @DisplayName("a restored component rounds its attack time up to the next hit, once")
  void aRestoreFlagRoundsUpOnce() {
    t.setAttackTimeRoundUpB(true);
    t.setAttackTimerMs(750);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(HIT_SPEED);
    assertThat(t.isAttackTimeRoundUpA()).isFalse();
    assertThat(t.isAttackTimeRoundUpB()).isFalse();
    assertThat(t.isAttackTimeRoundedUp()).isTrue();

    advance();

    assertThat(t.getAttackTimerMs())
        .as("the next advance steps as usual")
        .isEqualTo(HIT_SPEED + 50);
    assertThat(t.isAttackTimeRoundedUp()).isFalse();
  }

  @Test
  @DisplayName("a restore flag on a zero attack time skips the load and rounds to one hit")
  void aRestoreFlagSkipsTheLoad() {
    t.setAttackTimeRoundUpA(true);

    advance();

    assertThat(t.getAttackTimerMs()).isEqualTo(HIT_SPEED);
    assertThat(t.getLoadTimerMs()).as("no load happened").isZero();
  }

  @Test
  @DisplayName(
      "a running burst timer takes the step, and freezes the attack time only under the column")
  void aRunningBurstTakesTheStep() {
    t.setAttackTimerMs(600);
    t.setBurstProgressMs(50);

    advance();

    assertThat(t.getBurstProgressMs()).isEqualTo(100);
    assertThat(t.getAttackTimerMs()).isEqualTo(650);

    cfg = cfg.toBuilder().burstAffectAnimation(true).build();
    t.setConfig(cfg);
    advance();

    assertThat(t.getBurstProgressMs()).isEqualTo(150);
    assertThat(t.getAttackTimerMs()).as("frozen while the burst runs").isEqualTo(650);

    t.setBurstProgressMs(0);
    advance();

    assertThat(t.getBurstProgressMs()).as("a burst timer at zero is not started").isZero();
    assertThat(t.getAttackTimerMs()).as("and the column is skipped").isEqualTo(700);
  }
}
