package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Ladder timeline's boundaries: the elixir rate, the cooldown, overtime and the time up. */
class TimelineTest {

  private static Timeline ladder() {
    return new Timeline(GameData.records().gameModeTimeline(LadderMatch.GAME_MODE));
  }

  @Test
  @DisplayName("the Ladder timeline starts at 1x elixir with a 1000 ms cooldown")
  void itStartsAtOneX() {
    Timeline timeline = ladder();
    timeline.advance(0);

    assertThat(timeline.isFirstAdvance()).isTrue();
    assertThat(timeline.getFullBarMs()).isEqualTo(28000);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(1000);
    assertThat(timeline.getRate()).isZero();
    assertThat(timeline.getSection()).isZero();
  }

  @Test
  @DisplayName("2x elixir from tick 2400 and 3x from 4800, the cooldown with them")
  void theRateFollowsTheBattleTime() {
    Timeline timeline = ladder();
    timeline.advance(2399);
    assertThat(timeline.getFullBarMs()).isEqualTo(28000);
    timeline.advance(2400);
    assertThat(timeline.getFullBarMs()).isEqualTo(14000);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(500);
    timeline.advance(4799);
    assertThat(timeline.getRate()).isEqualTo(1);
    timeline.advance(4800);
    assertThat(timeline.getFullBarMs()).isEqualTo(9300);
    assertThat(timeline.getNextCardCooldownMs()).isEqualTo(350);
  }

  @Test
  @DisplayName("at 3:00 equal crowns go into overtime, whose end at 5:00 is the time up")
  void equalCrownsGoIntoOvertime() {
    Timeline timeline = ladder();
    timeline.advance(3599);
    timeline.advance(3600);

    assertThat(timeline.overtime()).isTrue();
    assertThat(timeline.getSection()).isEqualTo(1);
    assertThat(timeline.timeUp()).isFalse();
    timeline.advance(5999);
    assertThat(timeline.timeUp()).isFalse();
    timeline.advance(6000);
    assertThat(timeline.timeUp()).isTrue();
  }

  @Test
  @DisplayName("at 3:00 unequal crowns stay in the regular section, and the time is up")
  void unequalCrownsEndAtThreeMinutes() {
    Timeline timeline = ladder();
    timeline.advance(3599);
    timeline.setCrownsEqual(false);
    timeline.advance(3600);

    assertThat(timeline.overtime()).isFalse();
    assertThat(timeline.getSection()).isZero();
    assertThat(timeline.timeUp()).isTrue();
  }

  @Test
  @DisplayName("a frozen timeline keeps its time but its counters take no more")
  void aFrozenTimelineStops() {
    Timeline timeline = ladder();
    timeline.advance(2000);
    timeline.freeze();
    timeline.advance(3000);

    assertThat(timeline.getTimeMs()).isEqualTo(150000);
    assertThat(timeline.getRate()).isZero();
  }
}
