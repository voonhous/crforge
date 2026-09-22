package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a hit writes back into the attacker's targeting component. */
class HitApplicationTest {

  private TargetingState t;

  @BeforeEach
  void setUp() {
    GridEntity owner = new GridEntity();
    owner.setName("owner");
    t = new TargetingState();
    t.setOwner(owner);
    t.setConfig(TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    t.setLoadTimerMs(150);
    t.setSpecialLoadPending(true);
  }

  @Test
  @DisplayName(
      "a hit marks the hit started, reloads the countdown, counts itself and clears a load")
  void aHitIsRecordedOnTheAttacker() {
    boolean nothingLanded = HitApplication.record(t, false);

    assertThat(nothingLanded).isFalse();
    assertThat(t.isHitStarted()).isTrue();
    assertThat(t.getLoadTimerMs()).isEqualTo(700);
    assertThat(t.getSpecialChargeTimerMs()).as("one hit counted").isEqualTo(1);
    assertThat(t.getAttackBlockTimerMs()).as("no stop time after an attack").isZero();
    assertThat(t.isSpecialLoadPending()).isFalse();
  }

  @Test
  @DisplayName("a stop time after the attack is stored into the attack block timer")
  void aStopTimeBlocksTheNextAttack() {
    t.setConfig(t.getConfig().toBuilder().stopTimeAfterAttack(300).build());

    HitApplication.record(t, false);

    assertThat(t.getAttackBlockTimerMs()).isEqualTo(300);
  }

  @Test
  @DisplayName("a unit charging a special attack does not count hits in the charge")
  void aChargingUnitDoesNotCountHits() {
    t.setConfig(t.getConfig().toBuilder().specialChargeTime(2000).build());
    t.setSpecialChargeTimerMs(400);

    HitApplication.record(t, false);

    assertThat(t.getSpecialChargeTimerMs()).isEqualTo(400);
  }

  @Test
  @DisplayName("a wind-up-first unit whose hit missed stays loaded when the match says so")
  void aMissedWindUpFirstHitKeepsTheLoad() {
    t.setConfig(t.getConfig().toBuilder().loadFirstHit(true).build());
    t.setGlobals(
        TargetingGlobals.standard1v1().toBuilder()
            .loadFirstHitKeepLoadedAfterDiscard(true)
            .build());

    boolean nothingLanded = HitApplication.record(t, true);

    assertThat(nothingLanded).isTrue();
    assertThat(t.getLoadTimerMs()).as("the countdown is not reloaded").isEqualTo(150);
    assertThat(t.isHitStarted()).as("the hit still counts as started").isTrue();
  }

  @Test
  @DisplayName("an ordinary unit reloads even when its hit missed")
  void anOrdinaryMissStillReloads() {
    HitApplication.record(t, true);

    assertThat(t.getLoadTimerMs()).isEqualTo(700);
  }
}
