package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The balance switches the targeting visit reads, as the standard mode answers them. */
class TargetingGlobalsTest {

  @Test
  @DisplayName("every switch carries its published value, none is left at a placeholder zero")
  void standardSwitchesArePublishedValues() {
    TargetingGlobals globals = TargetingGlobals.standard1v1();

    assertThat(globals.rangeExtensionToKeepTarget()).isEqualTo(25);
    assertThat(globals.attackFinishTimeMs()).isEqualTo(250);
    assertThat(globals.preserveTargetIfHitStarted()).isTrue();
    assertThat(globals.currentTargetIgnoresPendingDamage()).isTrue();
    assertThat(globals.compareUsingHitStarted()).isTrue();
    assertThat(globals.clearSpecialLoadOnReferenceLoss()).isTrue();
    assertThat(globals.loadFirstHitResetTimerWhenZapped()).isTrue();
    assertThat(globals.loadFirstHitResetTimerAfterAttack()).isTrue();
    assertThat(globals.loadFirstHitKeepLoadedAfterDiscard()).isTrue();
    assertThat(globals.pendingDamageIgnoreIfDurationLess()).isEqualTo(600);
  }
}
