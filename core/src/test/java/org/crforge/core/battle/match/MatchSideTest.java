package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
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

  /** A list column of the Ladder game mode's battle timeline row. */
  private static List<Integer> timelineColumn(String name) {
    GameRow timeline =
        Shipped.row(
            "battle_timelines",
            Shipped.text(Shipped.row("game_modes", "Ladder"), "BattleTimeline"));
    return Shipped.numbers(timeline, name);
  }

  /**
   * The elixir a step adds at a full bar time: a bar of the most elixir in ten-thousandths over the
   * full bar time in steps of 50 ms, rounded down.
   */
  private static int step(int fullBarMs) {
    return MAX_MANA * 10000 * 50 / fullBarMs;
  }

  @Test
  @DisplayName("from 6 elixir at the first rate's step: full on the visit that crosses the cap")
  void theElixirFillsAtOneX() {
    int step = step(timelineColumn("ElixirFullBarMS").get(0));
    // The visits it takes from 6 elixir to the cap; the last one crosses it.
    int visits = (100000 - 60000 + step - 1) / step;
    MatchSide side = side();
    Timeline timeline = ladder();
    for (int tick = 0; tick < visits - 1; tick++) {
      timeline.advance(tick);
      side.visit(timeline, MAX_MANA);
    }
    assertThat(side.getElixir()).isEqualTo(60000 + step * (visits - 1));
    timeline.advance(visits - 1);
    side.visit(timeline, MAX_MANA);
    assertThat(side.getElixir()).isEqualTo(100000);
    assertThat(side.getWasted()).isEqualTo(60000 + step * visits - 100000);
  }

  @Test
  @DisplayName("the elixir step follows the rate: the second and the third rate's full bar")
  void theElixirStepFollowsTheRate() {
    List<Integer> fullBar = timelineColumn("ElixirFullBarMS");
    List<Integer> rateLengths = timelineColumn("ElixirRateLength");
    MatchSide side = side();
    side.play(0);
    Timeline timeline = ladder();
    timeline.advance(rateLengths.get(0) * 20);
    int before = side.getElixir();
    side.visit(timeline, MAX_MANA);
    assertThat(side.getElixir() - before).isEqualTo(step(fullBar.get(1)));
    timeline.advance((rateLengths.get(0) + rateLengths.get(1)) * 20);
    before = side.getElixir();
    side.visit(timeline, MAX_MANA);
    assertThat(side.getElixir() - before).isEqualTo(step(fullBar.get(2)));
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
    int cooldownMs = timelineColumn("NextSpellCooldownMS").get(0);
    assertThat(side.getHand().getCooldownMs()).isEqualTo(cooldownMs);
    // Each visit counts the cooldown down by 50 ms: the second slot fills on the visit that runs
    // it out.
    for (int visit = 1; visit < (cooldownMs + 49) / 50; visit++) {
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
