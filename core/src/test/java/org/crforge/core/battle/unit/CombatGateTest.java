/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the combat gate does to an entity casting its ability. */
class CombatGateTest {

  private static GridEntity casting() {
    GridEntity view = new GridEntity();
    view.setName("caster");
    view.setState(GridEntityState.CASTING);
    view.setDeployCountdown(0);
    return view;
  }

  private static TargetingState targetingOf(GridEntity view) {
    GridEntity other = new GridEntity();
    other.setName("target");
    TargetingState targeting = new TargetingState();
    targeting.setOwner(view);
    targeting.setReference(
        new TargetView(other, TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false)));
    return targeting;
  }

  @Test
  @DisplayName("a cast that keeps its target switches the component off and keeps the reference")
  void aCastKeepingItsTargetSwitchesOff() {
    GridEntity view = casting();
    TargetingState targeting = targetingOf(view);
    TargetView reference = targeting.getReference();

    boolean on =
        CombatGate.targetingOn(view, targeting, true, true, 50, true, () -> {}, () -> {}, true);

    assertThat(on).isFalse();
    assertThat(targeting.getReference()).isSameAs(reference);
  }

  @Test
  @DisplayName("a cast that does not keep its target goes through the gate as any acting entity")
  void aCastNotKeepingItsTargetActs() {
    GridEntity view = casting();
    TargetingState targeting = targetingOf(view);

    boolean on =
        CombatGate.targetingOn(view, targeting, true, true, 50, true, () -> {}, () -> {}, false);

    assertThat(on).isTrue();
  }
}
