package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Ladder timeline's boundaries: the elixir rate, the cooldown, overtime and the time up. The
 * boundaries and the values at each are the Ladder timeline row's, read as the table writes them:
 * lengths in seconds of 20 ticks.
 */
class TimelineTest {

  private static Timeline ladder() {
    return new Timeline(GameData.records().gameModeTimeline(LadderMatch.GAME_MODE));
  }

  /** The Ladder game mode's battle timeline row. */
  private static GameRow row() {
    return Shipped.row(
        "battle_timelines", Shipped.text(Shipped.row("game_modes", "Ladder"), "BattleTimeline"));
  }

  /** A list column of the timeline row. */
  private static List<Integer> column(String name) {
    return Shipped.numbers(row(), name);
  }

  @Test
  @DisplayName("the Ladder timeline starts at its first elixir rate and its first cooldown")
  void itStartsAtOneX() {
    Timeline timeline = ladder();
    timeline.advance(0);

    assertThat(timeline.isFirstAdvance()).isTrue();
    assertThat(timeline.getFullBarMs()).isEqualTo(column("ElixirFullBarMS").get(0));
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(column("NextSpellCooldownMS").get(0));
    assertThat(timeline.getRate()).isZero();
    assertThat(timeline.getSection()).isZero();
  }

  @Test
  @DisplayName("the elixir rate and the cooldown step on at the end of each of their lengths")
  void theRateFollowsTheBattleTime() {
    List<Integer> fullBar = column("ElixirFullBarMS");
    List<Integer> rateLengths = column("ElixirRateLength");
    int secondRate = rateLengths.get(0) * 20;
    int thirdRate = secondRate + rateLengths.get(1) * 20;
    Timeline timeline = ladder();
    timeline.advance(secondRate - 1);
    assertThat(timeline.getFullBarMs()).isEqualTo(fullBar.get(0));
    timeline.advance(secondRate);
    assertThat(timeline.getFullBarMs()).isEqualTo(fullBar.get(1));
    timeline.advance(thirdRate - 1);
    assertThat(timeline.getRate()).isEqualTo(1);
    timeline.advance(thirdRate);
    assertThat(timeline.getFullBarMs()).isEqualTo(fullBar.get(2));

    List<Integer> cooldowns = column("NextSpellCooldownMS");
    List<Integer> cooldownLengths = column("NextSpellCooldownLength");
    int secondCooldown = cooldownLengths.get(0) * 20;
    int thirdCooldown = secondCooldown + cooldownLengths.get(1) * 20;
    timeline = ladder();
    timeline.advance(secondCooldown - 1);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(cooldowns.get(0));
    timeline.advance(secondCooldown);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(cooldowns.get(1));
    timeline.advance(thirdCooldown - 1);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(cooldowns.get(1));
    timeline.advance(thirdCooldown);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(cooldowns.get(2));
  }

  @Test
  @DisplayName(
      "at the regular section's end equal crowns go into overtime, whose end is the time up")
  void equalCrownsGoIntoOvertime() {
    assertThat(Shipped.texts(row(), "SectionType")).containsExactly("Normal", "Overtime");
    int overtime = column("SectionLength").get(0) * 20;
    int end = overtime + column("SectionLength").get(1) * 20;
    Timeline timeline = ladder();
    timeline.advance(overtime - 1);
    timeline.advance(overtime);

    assertThat(timeline.overtime()).isTrue();
    assertThat(timeline.getSection()).isEqualTo(1);
    assertThat(timeline.timeUp()).isFalse();
    timeline.advance(end - 1);
    assertThat(timeline.timeUp()).isFalse();
    timeline.advance(end);
    assertThat(timeline.timeUp()).isTrue();
  }

  @Test
  @DisplayName(
      "at the regular section's end unequal crowns stay in the regular section, and the time is"
          + " up")
  void unequalCrownsEndAtThreeMinutes() {
    int overtime = column("SectionLength").get(0) * 20;
    Timeline timeline = ladder();
    timeline.advance(overtime - 1);
    timeline.setCrownsEqual(false);
    timeline.advance(overtime);

    assertThat(timeline.overtime()).isFalse();
    assertThat(timeline.getSection()).isZero();
    assertThat(timeline.timeUp()).isTrue();
  }

  @Test
  @DisplayName("a frozen timeline keeps its time but its counters take no more")
  void aFrozenTimelineStops() {
    // Frozen before the first rate's end, the time runs on and the rate stays the first.
    int frozenAt = column("ElixirRateLength").get(0) * 20 - 1;
    Timeline timeline = ladder();
    timeline.advance(frozenAt);
    timeline.freeze();
    timeline.advance(frozenAt + 1000);

    assertThat(timeline.getTimeMs()).isEqualTo((frozenAt + 1000) * 50);
    assertThat(timeline.getRate()).isZero();
  }
}
