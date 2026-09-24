package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a targeting component does with the news that an entity has left. */
class RemovalNoticeTest {

  private static TargetingConfig knight() {
    return TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false);
  }

  private static TargetingState attacking(TargetingConfig config, TargetView reference) {
    TargetingState t = new TargetingState();
    t.setOwner(new GridEntity());
    t.setConfig(config);
    t.setReference(reference);
    t.setHitStarted(true);
    t.setAttackTimerMs(1200);
    t.setLoadTimerMs(700);
    return t;
  }

  private static TargetView view(String name) {
    GridEntity entity = new GridEntity();
    entity.setName(name);
    return new TargetView(entity, null);
  }

  @Test
  @DisplayName("a plain unit whose target left drops it and starts the target-lost countdown")
  void aPlainUnitStartsTheTargetLostCountdown() {
    TargetView tower = view("tower");
    TargetingState t = attacking(knight(), tower);

    RemovalNotice.entityRemoved(t, tower, null);

    assertThat(t.getReference()).isNull();
    assertThat(t.getTargetLostTimerMs()).isEqualTo(1);
    assertThat(t.getAttackTimerMs()).as("the attack keeps running").isEqualTo(1200);
    assertThat(t.getLoadTimerMs()).isEqualTo(700);
    assertThat(t.isHitStarted()).as("the notice does not touch the hit-started flag").isTrue();
  }

  @Test
  @DisplayName("a unit that was not attacking, or whose target was another entity, is left alone")
  void anUnrelatedRemovalChangesNothing() {
    TargetView tower = view("tower");
    TargetView other = view("other");
    TargetingState t = attacking(knight(), tower);
    RemovalNotice.entityRemoved(t, other, null);
    assertThat(t.getReference()).isSameAs(tower);
    assertThat(t.getTargetLostTimerMs()).isZero();

    TargetingState idle = attacking(knight(), tower);
    idle.setAttackTimerMs(0);
    RemovalNotice.entityRemoved(idle, tower, null);
    assertThat(idle.getReference()).isNull();
    assertThat(idle.getTargetLostTimerMs()).as("no attack was running").isZero();
  }

  @Test
  @DisplayName("a reference kept by the pending-damage check is dropped without the retarget load")
  void aReferenceKeptWithPendingDamageIsDroppedWithoutTheLoad() {
    TargetView tower = view("tower");
    TargetingState t = attacking(knight(), tower);
    t.setKeptByPendingDamageCheck(true);

    RemovalNotice.entityRemoved(t, tower, null);

    assertThat(t.getReference()).isNull();
    assertThat(t.isKeptByPendingDamageCheck()).isFalse();
    assertThat(t.getTargetLostTimerMs()).isZero();
  }

  @Test
  @DisplayName("LoadAfterRetarget reloads the load countdown and clears the attack")
  void loadAfterRetargetReloads() {
    TargetView tower = view("tower");
    TargetingState t = attacking(knight().toBuilder().loadAfterRetarget(true).build(), tower);
    t.setLoadTimerMs(100);

    RemovalNotice.entityRemoved(t, tower, null);

    assertThat(t.getReference()).isNull();
    assertThat(t.getAttackTimerMs()).isZero();
    assertThat(t.getLoadTimerMs()).isEqualTo(700);
    assertThat(t.getTargetLostTimerMs()).isZero();
  }

  @Test
  @DisplayName("LoadFirstHit credits the load time less the attack time, never below zero")
  void loadFirstHitCredits() {
    TargetView tower = view("tower");
    TargetingConfig config = knight().toBuilder().loadFirstHit(true).build();
    TargetingState t = attacking(config, tower);
    t.setAttackTimerMs(300);
    RemovalNotice.entityRemoved(t, tower, null);
    assertThat(t.getAttackTimerMs()).isZero();
    assertThat(t.getLoadTimerMs()).isEqualTo(400);

    TargetingState late = attacking(config, tower);
    late.setAttackTimerMs(1000);
    RemovalNotice.entityRemoved(late, tower, null);
    assertThat(late.getLoadTimerMs()).isZero();
  }

  @Test
  @DisplayName("a replacement takes the reference's place, and a previous reference is forgotten")
  void aReplacementTakesThePlace() {
    TargetView tower = view("tower");
    TargetView successor = view("successor");
    TargetingState t = attacking(knight(), tower);
    t.setPreviousReference(tower);

    RemovalNotice.entityRemoved(t, tower, successor);

    assertThat(t.getReference()).isSameAs(successor);
    assertThat(t.getPreviousReference()).isNull();
    assertThat(t.getTargetLostTimerMs()).as("the retarget load still runs").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "no countdown starts while a burst runs, the visit is suspended or the finish time is overridden")
  void theCountdownHasThreeMoreGuards() {
    TargetView tower = view("tower");
    TargetingState burst = attacking(knight(), tower);
    burst.setBurstProgressMs(50);
    RemovalNotice.entityRemoved(burst, tower, null);
    assertThat(burst.getTargetLostTimerMs()).isZero();

    TargetingState suspended = attacking(knight(), tower);
    suspended.setVisitSuspended(true);
    RemovalNotice.entityRemoved(suspended, tower, null);
    assertThat(suspended.getTargetLostTimerMs()).isZero();

    TargetingState overridden =
        attacking(knight().toBuilder().overrideAttackFinishTime(true).build(), tower);
    RemovalNotice.entityRemoved(overridden, tower, null);
    assertThat(overridden.getTargetLostTimerMs()).isZero();
  }
}
