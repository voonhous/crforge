package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The published tower scaling values. */
class ScalingGlobalsTest {

  @Test
  @DisplayName("the published values: cap at level index 9, king hit points 7, all else 8, then 10")
  void thePublishedValues() {
    ScalingGlobals globals = ScalingGlobals.standard();
    assertThat(globals.towerScalingStartExpLevel()).isEqualTo(9);
    assertThat(globals.percentages(ScalingMode.KING_HITPOINTS))
        .isEqualTo(new ScalingGlobals.Percentages(7, 10, 10));
    assertThat(globals.percentages(ScalingMode.KING_DAMAGE))
        .isEqualTo(new ScalingGlobals.Percentages(8, 10, 10));
    assertThat(globals.percentages(ScalingMode.TOWER_HITPOINTS))
        .isEqualTo(new ScalingGlobals.Percentages(8, 10, 10));
    assertThat(globals.percentages(ScalingMode.TOWER_DAMAGE))
        .isEqualTo(new ScalingGlobals.Percentages(8, 10, 10));
  }

  @Test
  @DisplayName("the card modes and no mode compound by nothing")
  void theCardModesCompoundByNothing() {
    ScalingGlobals globals = ScalingGlobals.standard();
    ScalingGlobals.Percentages none = new ScalingGlobals.Percentages(0, 0, 0);
    assertThat(globals.percentages(ScalingMode.NONE)).isEqualTo(none);
    assertThat(globals.percentages(ScalingMode.CARD_DAMAGE)).isEqualTo(none);
    assertThat(globals.percentages(ScalingMode.CARD_HITPOINTS)).isEqualTo(none);
  }
}
