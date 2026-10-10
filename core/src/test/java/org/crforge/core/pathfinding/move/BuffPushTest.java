/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pull of an attracting buff on the push accumulators, case by case against the reference's
 * translation: the Tornado's 360 percent at a speed factor of 100, and the branches no reference
 * run takes.
 */
class BuffPushTest {

  private static final int TORNADO = 360;

  /** A pull with no lateral share and no mass factor, at a speed factor of 100. */
  private static int[] pull(
      MovementState component,
      int dx,
      int dy,
      int state,
      int speed,
      int jumpHeight,
      int side,
      boolean air,
      boolean hovering) {
    BuffPush.push(
        component, dx, dy, TORNADO, 0, 0, 100, state, speed, jumpHeight, side, 6, air, hovering);
    return accumulators(component);
  }

  private static int[] accumulators(MovementState c) {
    return new int[] {
      c.getPushX(), c.getPushY(), c.getPushCount(), c.getPushStuck(), c.getPushUnclamped()
    };
  }

  private static MovementState fresh() {
    return MovementState.forSide(0, 3500, 10000);
  }

  @Test
  @DisplayName("the pull is a share of the configured speed: a Giant 162, a Knight 216 a step")
  void theShareOfTheConfiguredSpeed() {
    assertThat(pull(fresh(), 4083, -517, GridEntityState.MOVING, 45, 0, 1, false, false))
        .containsExactly(160, -20, 1, 1, 1);
    assertThat(pull(fresh(), 2463, -1537, GridEntityState.MOVING, 60, 0, 1, false, false))
        .containsExactly(183, -114, 1, 1, 1);
    assertThat(pull(fresh(), -7, -3, GridEntityState.MOVING, 60, 0, 1, false, false))
        .as("past the centre, truncating toward zero")
        .containsExactly(-216, -92, 1, 1, 1);
  }

  @Test
  @DisplayName("a flying or hovering unit does not ask the grid move to keep it off the water")
  void flyingAndHoveringUnitsAreNotClamped() {
    assertThat(pull(fresh(), -3000, 4000, GridEntityState.MOVING, 90, 0, 0, true, false))
        .containsExactly(-194, 259, 1, 0, 1);
    assertThat(pull(fresh(), -3000, 4000, GridEntityState.MOVING, 60, 0, 0, false, true))
        .containsExactly(-129, 172, 1, 0, 1);
  }

  @Test
  @DisplayName("a unit on the centre itself is pulled toward its own back line")
  void onTheCentreItIsPulledTowardItsBackLine() {
    assertThat(pull(fresh(), 0, 0, GridEntityState.MOVING, 60, 0, 0, false, false))
        .containsExactly(0, -216, 1, 1, 1);
    assertThat(pull(fresh(), 0, 0, GridEntityState.MOVING, 60, 0, 1, false, false))
        .containsExactly(0, 216, 1, 1, 1);
    assertThat(pull(fresh(), 0, 0, GridEntityState.MOVING, 60, 0, 2, false, false))
        .as("by the side's low bit")
        .containsExactly(0, -216, 1, 1, 1);
  }

  @Test
  @DisplayName("a jumping unit, and a dashing one whose row jumps, is not pulled")
  void jumpingAndJumpingDashesAreSkipped() {
    assertThat(pull(fresh(), 100, 0, GridEntityState.JUMPING, 60, 0, 1, false, false))
        .containsExactly(0, 0, 0, 0, 0);
    assertThat(pull(fresh(), 100, 0, GridEntityState.DASHING, 60, 500, 1, false, false))
        .containsExactly(0, 0, 0, 0, 0);
    assertThat(pull(fresh(), 100, 0, GridEntityState.DASHING, 60, 0, 1, false, false))
        .containsExactly(216, 0, 1, 1, 1);
  }

  @Test
  @DisplayName("it adds to what the accumulators hold, as one more push")
  void itAddsToTheAccumulators() {
    MovementState component = fresh();
    component.setPushX(10);
    component.setPushY(-20);
    component.setPushCount(2);
    assertThat(pull(component, 3000, 4000, GridEntityState.MOVING, 60, 0, 1, false, false))
        .containsExactly(139, 152, 3, 1, 1);
  }

  @Test
  @DisplayName(
      "a lateral share pushes across the way to the centre, a mass factor weakens the pull, and"
          + " without a speed factor the percentages are taken as they are")
  void lateralMassAndNoSpeedFactor() {
    MovementState lateral = fresh();
    BuffPush.push(
        lateral, 3000, 4000, 0, 200, 0, 100, GridEntityState.MOVING, 60, 0, 1, 6, false, false);
    assertThat(accumulators(lateral)).containsExactly(-96, 72, 1, 1, 1);

    MovementState mass = fresh();
    BuffPush.push(
        mass, 3000, 4000, TORNADO, 0, 50, 100, GridEntityState.MOVING, 60, 0, 1, 6, false, false);
    assertThat(accumulators(mass)).containsExactly(90, 120, 1, 1, 1);

    MovementState heavy = fresh();
    BuffPush.push(
        heavy, 3000, 4000, TORNADO, 0, 500, 100, GridEntityState.MOVING, 60, 0, 1, 6, false, false);
    assertThat(accumulators(heavy)).as("at most to nothing").containsExactly(0, 0, 1, 1, 1);

    MovementState plain = fresh();
    BuffPush.push(
        plain, 3000, 4000, TORNADO, 0, 0, 0, GridEntityState.MOVING, 60, 0, 1, 6, false, false);
    assertThat(accumulators(plain)).containsExactly(1, 2, 1, 1, 1);
  }
}
