/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The row a shape selector runs on its owner for a pick by the side the pick lies on, which it
 * reads by the owner's team: sides 0 and 2 are team 0, sides 1 and 3 team 1, the neutral side
 * neither.
 */
class ShapeSelectorSideActionTest {

  private static final ShapeSelector.Columns COLUMNS =
      ShapeSelector.Columns.builder()
          .delaysMs(List.of(0))
          .actions(List.of("slap"))
          .actionOnSelfLeft("left")
          .actionOnSelfRight("right")
          .build();

  /** The row run for an owner of the side at x 9000 and a pick at the given x. */
  private static String run(int side, int pickX) {
    return ShapeSelector.sideAction(COLUMNS, side, 9000, pickX);
  }

  @Test
  @DisplayName(
      "team 0 runs the right row for a pick below its x and the left row otherwise, team 1 the"
          + " other way round: side 2 as side 0, side 3 as side 1")
  void theSideActionGoesByTeam() {
    for (int side : new int[] {0, 2}) {
      assertThat(run(side, 8000)).as("side " + side).isEqualTo("right");
      assertThat(run(side, 10000)).as("side " + side).isEqualTo("left");
      assertThat(run(side, 9000)).as("side " + side).isEqualTo("left");
    }
    for (int side : new int[] {1, 3}) {
      assertThat(run(side, 8000)).as("side " + side).isEqualTo("left");
      assertThat(run(side, 10000)).as("side " + side).isEqualTo("right");
    }
  }

  @Test
  @DisplayName("the neutral side runs neither row")
  void theNeutralSideRunsNeither() {
    assertThat(run(100, 8000)).isNull();
    assertThat(run(100, 10000)).isNull();
  }
}
