/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.crforge.desktop.battle.BattleFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text the battle renderer draws besides the arena: the crowns and the result follow the view's
 * orientation, and hiding the annotations drops the tick line, the status column, the messages, the
 * halted line and the name labels, but neither the crowns nor the result.
 */
class HudTextTest {

  private static final List<String> STATUS = List.of("engine: battle core", "a note");

  /** A frame where side 0 has taken two crowns and side 1 one, ended with the given winner. */
  private static BattleFrame frame(boolean ended, int winner, String halted) {
    return new BattleFrame(
        120,
        60000,
        false,
        1,
        List.of(),
        List.of(side(0, 2), side(1, 1)),
        ended,
        winner,
        false,
        halted,
        List.of("[100] red plays Knight on tick 100 (cmd0)"));
  }

  private static BattleFrame.SideView side(int side, int crowns) {
    return new BattleFrame.SideView(
        side, 50000, 5, crowns, Arrays.asList(new BattleFrame.CardView[4]), null);
  }

  @Test
  @DisplayName("standing, the crowns are side 0's first and side 0 wins as blue")
  void standing() {
    HudText text = HudText.of(frame(true, 0, null), ViewState.ladder(), STATUS);

    assertThat(text.crowns()).isEqualTo("crowns 2 - 1");
    assertThat(text.result()).isEqualTo("BLUE WINS!");
  }

  @Test
  @DisplayName("flipped, the crowns are side 1's first and side 0 wins as red")
  void flipped() {
    HudText text = HudText.of(frame(true, 0, null), ViewState.replay(), STATUS);

    assertThat(text.crowns()).isEqualTo("crowns 1 - 2");
    assertThat(text.result()).isEqualTo("RED WINS!");
    assertThat(HudText.of(frame(true, 1, null), ViewState.replay(), STATUS).result())
        .isEqualTo("BLUE WINS!");
    assertThat(HudText.of(frame(true, -1, null), ViewState.replay(), STATUS).result())
        .isEqualTo("DRAW!");
    assertThat(HudText.of(frame(false, -1, null), ViewState.replay(), STATUS).result()).isNull();
  }

  @Test
  @DisplayName("with the annotations shown, every annotation is drawn")
  void annotationsShown() {
    HudText text = HudText.of(frame(false, -1, "a reason"), ViewState.replay(), STATUS);

    assertThat(text.tickLine()).isEqualTo("tick 120  entities 0");
    assertThat(text.status()).isEqualTo(STATUS);
    assertThat(text.messages()).containsExactly("[100] red plays Knight on tick 100 (cmd0)");
    assertThat(text.halted()).isTrue();
    assertThat(text.labels()).isTrue();
  }

  @Test
  @DisplayName(
      "with the annotations hidden, the text goes; the name labels, crowns and result stay")
  void annotationsHidden() {
    ViewState view = ViewState.replay();
    view.toggleAnnotations();

    HudText text = HudText.of(frame(true, 1, "a reason"), view, STATUS);

    assertThat(text.tickLine()).isNull();
    assertThat(text.status()).isEmpty();
    assertThat(text.messages()).isEmpty();
    assertThat(text.halted()).isFalse();
    assertThat(text.labels()).isTrue();
    assertThat(text.crowns()).isEqualTo("crowns 1 - 2");
    assertThat(text.result()).isEqualTo("BLUE WINS!");
  }

  @Test
  @DisplayName("outside a match there are no crowns")
  void noMatch() {
    BattleFrame frame =
        new BattleFrame(5, 250, false, 0, List.of(), List.of(), false, -1, false, null, List.of());

    assertThat(HudText.of(frame, ViewState.ladder(), STATUS).crowns()).isNull();
  }
}
