package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A king's elixir and hand over the steps of a match. */
class MatchSideTest {

  private static final int MAX_MANA = 10;

  private static MatchSide side() {
    List<MatchCard> deck = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      deck.add(new MatchCard("Card" + i, 3, false, false, 0, false, null));
    }
    MatchSide side = new MatchSide(deck, 6);
    side.getHand().deal(List.of(0, 1, 2, 3, 4, 5, 6, 7));
    return side;
  }

  private static Timeline ladder() {
    return new Timeline(GameData.records().gameModeTimeline(LadderMatch.GAME_MODE));
  }

  @Test
  @DisplayName("from 6 elixir, 178 a step at 1x: full on the visit of tick 224, the rest wasted")
  void theElixirFillsAtOneX() {
    MatchSide side = side();
    Timeline timeline = ladder();
    for (int tick = 0; tick <= 223; tick++) {
      timeline.advance(tick);
      side.visit(timeline, MAX_MANA);
    }
    assertThat(side.getElixir()).isEqualTo(60000 + 178 * 224);
    timeline.advance(224);
    side.visit(timeline, MAX_MANA);
    assertThat(side.getElixir()).isEqualTo(100000);
    assertThat(side.getWasted()).isEqualTo(50);
  }

  @Test
  @DisplayName("357 a step at 2x and 537 at 3x")
  void theElixirStepFollowsTheRate() {
    MatchSide side = side();
    side.play(0);
    Timeline timeline = ladder();
    timeline.advance(2400);
    int before = side.getElixir();
    side.visit(timeline, MAX_MANA);
    assertThat(side.getElixir() - before).isEqualTo(357);
    timeline.advance(4800);
    before = side.getElixir();
    side.visit(timeline, MAX_MANA);
    assertThat(side.getElixir() - before).isEqualTo(537);
  }

  @Test
  @DisplayName("a play takes its cost and sends its card to the back of the queue")
  void aPlayPaysAndCycles() {
    MatchSide side = side();
    side.play(2);

    assertThat(side.getElixir()).isEqualTo(30000);
    assertThat(side.wholeElixir()).isEqualTo(3);
    assertThat(side.getHand().slots()).containsExactly(0, 1, Hand.EMPTY, 3);
    assertThat(side.getHand().queue()).containsExactly(4, 5, 6, 7, 2);
  }

  @Test
  @DisplayName("an empty slot takes the next card at once, and a second one a cooldown later")
  void theRefillWaitsOneCooldown() {
    MatchSide side = side();
    Timeline timeline = ladder();
    timeline.advance(0);
    side.play(0);
    side.play(1);
    side.visit(timeline, MAX_MANA);
    assertThat(side.getHand().slots()).containsExactly(4, Hand.EMPTY, 2, 3);
    assertThat(side.getHand().getCooldownMs()).isEqualTo(1000);
    for (int visit = 1; visit < 20; visit++) {
      side.visit(timeline, MAX_MANA);
    }
    assertThat(side.getHand().slots()).containsExactly(4, Hand.EMPTY, 2, 3);
    side.visit(timeline, MAX_MANA);
    assertThat(side.getHand().slots()).containsExactly(4, 5, 2, 3);
  }

  @Test
  @DisplayName("an add from a collector or a death clamps at the cap and counts the rest as wasted")
  void anAddClampsAtTheCap() {
    MatchSide side = side();
    side.add(10000, MAX_MANA);
    assertThat(side.getElixir()).isEqualTo(70000);
    side.add(35000, MAX_MANA);
    assertThat(side.getElixir()).isEqualTo(100000);
    assertThat(side.getWasted()).isEqualTo(5000);
  }
}
