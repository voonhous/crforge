/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hide counter of a Tesla, 800 ms down and 800 up, visit by visit: where no reference run takes
 * it, the turn back from part-way down, the count up from a negative value, the step of 0, the
 * deploying state and an entity that is not a building.
 */
class HideHandlerTest {

  private static final int HIDE = 800;
  private static final int UP = 800;
  private static final int STEP = 50;

  /** One visit of a hiding building's counter; the effects it plays are added to the list. */
  private static int visit(int counter, int state, int step, List<String> effects) {
    return HideHandler.visit(counter, state, step, true, HIDE, UP, true, effects);
  }

  @Test
  @DisplayName("idle, it plays its hide effect as it leaves 0 and is hidden from 800 on")
  void idleItGoesDownAndStays() {
    List<String> effects = new ArrayList<>();
    int counter = visit(0, GridEntityState.STANDING, STEP, effects);
    assertThat(counter).isEqualTo(50);
    assertThat(effects).containsExactly(HideHandler.HIDE_EFFECT);
    for (int i = 0; i < 15; i++) {
      assertThat(HideHandler.hidden(counter, HIDE)).isFalse();
      counter = visit(counter, GridEntityState.STANDING, STEP, effects);
    }
    assertThat(counter).isEqualTo(HIDE);
    assertThat(HideHandler.hidden(counter, HIDE)).isTrue();
    assertThat(visit(counter, GridEntityState.STANDING, STEP, effects)).isEqualTo(HIDE);
    assertThat(effects).containsExactly(HideHandler.HIDE_EFFECT);
  }

  @Test
  @DisplayName("attacking from hidden, it rises and comes up through 1550 to 0")
  void attackingItRisesAndComesUp() {
    List<String> effects = new ArrayList<>();
    int counter = visit(HIDE, GridEntityState.ATTACKING, STEP, effects);
    assertThat(counter).isEqualTo(850);
    assertThat(effects).containsExactly(HideHandler.APPEAR_EFFECT);
    for (int i = 0; i < 14; i++) {
      counter = visit(counter, GridEntityState.ATTACKING, STEP, effects);
    }
    assertThat(counter).isEqualTo(1550);
    assertThat(visit(counter, GridEntityState.ATTACKING, STEP, effects)).isZero();
    assertThat(visit(0, GridEntityState.ATTACKING, STEP, effects)).isZero();
    assertThat(effects).containsExactly(HideHandler.APPEAR_EFFECT);
  }

  @Test
  @DisplayName(
      "attacking part-way down, it turns back into a negative count, rising at once past"
          + " half-way")
  void attackingPartWayDownItTurnsBack() {
    List<String> past = new ArrayList<>();
    assertThat(visit(400, GridEntityState.ATTACKING, STEP, past)).isEqualTo(-450);
    assertThat(past).containsExactly(HideHandler.APPEAR_EFFECT);
    List<String> before = new ArrayList<>();
    assertThat(visit(300, GridEntityState.ATTACKING, STEP, before)).isEqualTo(-350);
    assertThat(before).isEmpty();
    int counter = -550;
    for (int i = 0; i < 10; i++) {
      counter = visit(counter, GridEntityState.ATTACKING, STEP, before);
      assertThat(HideHandler.hidden(counter, HIDE)).isFalse();
    }
    assertThat(counter).isEqualTo(-50);
    assertThat(visit(counter, GridEntityState.ATTACKING, STEP, before)).isZero();
    assertThat(before).isEmpty();
  }

  @Test
  @DisplayName("a step of 0 holds the counter, going down or hidden")
  void aStepOfZeroHoldsTheCounter() {
    List<String> effects = new ArrayList<>();
    assertThat(visit(400, GridEntityState.STANDING, 0, effects)).isEqualTo(400);
    assertThat(visit(HIDE, GridEntityState.ATTACKING, 0, effects)).isEqualTo(HIDE);
    assertThat(effects).isEmpty();
  }

  @Test
  @DisplayName(
      "deploying counts as attacking, a unit that is not a building plays nothing, and a row"
          + " that does not hide stays up")
  void deployingBuildingsAndRowsThatDoNotHide() {
    List<String> effects = new ArrayList<>();
    assertThat(visit(0, GridEntityState.DEPLOYING, STEP, effects)).isZero();
    assertThat(HideHandler.visit(0, GridEntityState.STANDING, STEP, true, HIDE, UP, false, effects))
        .isEqualTo(50);
    assertThat(
            HideHandler.visit(
                HIDE, GridEntityState.ATTACKING, STEP, true, HIDE, UP, false, effects))
        .isEqualTo(850);
    assertThat(effects).isEmpty();
    assertThat(HideHandler.visit(0, GridEntityState.STANDING, STEP, false, HIDE, UP, true, effects))
        .isZero();
    assertThat(effects).isEmpty();
  }

  @Test
  @DisplayName(
      "coming back up past 0, it counts a whole period on: one that stops attacking there is"
          + " hidden at once")
  void comingBackUpPastZeroCountsAWholePeriodOn() {
    List<String> effects = new ArrayList<>();
    assertThat(visit(-50, GridEntityState.STANDING, STEP, effects)).isEqualTo(HIDE);
    assertThat(visit(-50, GridEntityState.ATTACKING, STEP, effects)).isZero();
    assertThat(visit(-30, GridEntityState.ATTACKING, STEP, effects)).isZero();
    assertThat(effects).isEmpty();
  }

  @Test
  @DisplayName("it is hidden only at the hide time, not on its way up")
  void hiddenOnlyAtTheHideTime() {
    assertThat(HideHandler.hidden(HIDE, HIDE)).isTrue();
    assertThat(HideHandler.hidden(HIDE - STEP, HIDE)).isFalse();
    assertThat(HideHandler.hidden(HIDE + STEP, HIDE)).isFalse();
    assertThat(HideHandler.hidden(HIDE + UP - STEP, HIDE)).isFalse();
    assertThat(HideHandler.hidden(0, HIDE)).isFalse();
  }
}
