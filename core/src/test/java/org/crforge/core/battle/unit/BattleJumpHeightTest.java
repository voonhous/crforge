/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit that jumps the river is lifted by its arc: every visit of the jump samples the arc's
 * height into its movement component, and the next pre-hook folds the samples into the unit's
 * height offset, which the contact passes compare. The visit that lands takes no sample, so the
 * unit still stands at its last sample's height through the step it lands in, and is down at the
 * next pre-hook: on the step it lands, the push pass of a unit on the ground leaves it out.
 */
class BattleJumpHeightTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "a Hog Rider's height rises and falls over its jump, stays at its last sample on the step it"
          + " lands and is 0 from the next step")
  void theJumpLiftsTheUnitUntilTheStepAfterItLands() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity hog = match.deploy(0, GameData.unit("HogRider"), LEVEL, 0, 9000, 13000);
    GridEntity view = hog.getView();
    List<Integer> jumping = new ArrayList<>();
    int landing = -1;
    int after = -1;
    boolean jumped = false;
    for (int step = 0; step < 400 && after < 0; step++) {
      match.getBattle().step();
      if (view.getState() == GridEntityState.JUMPING) {
        jumped = true;
        jumping.add(view.getZTotal());
      } else if (jumped && landing < 0) {
        landing = view.getZTotal();
      } else if (landing >= 0) {
        after = view.getZTotal();
      }
    }

    assertThat(jumping).as("the heights of the steps of the jump").isNotEmpty();
    assertThat(jumping.stream().mapToInt(Integer::intValue).max().getAsInt())
        .as("the arc's top")
        .isGreaterThan(0);
    assertThat(landing).as("the height through the step it lands").isGreaterThan(0);
    assertThat(after).as("the height from the next step").isZero();
  }
}
