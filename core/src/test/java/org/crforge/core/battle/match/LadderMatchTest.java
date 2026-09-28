package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A Ladder match's gates and its end. */
class LadderMatchTest {

  private static final List<String> DECK =
      List.of(
          "Knight",
          "Archer",
          "Giant",
          "MiniPekka",
          "Musketeer",
          "Valkyrie",
          "Barbarians",
          "Minions");

  @Test
  @DisplayName("a card in the queue is refused with 9, one the elixir does not cover with 0xd")
  void theGatesRefuse() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 0, 0);
    // Side 0's opening hand is Valkyrie, Giant, Archer, Minions; the Knight waits in the queue.
    assertThat(match.gate(0, match.deckIndex(0, "Knight"))).isEqualTo(LadderMatch.NOT_IN_HAND);
    assertThat(match.gate(0, match.deckIndex(0, "Archer"))).isZero();
    // The Giant costs 5 and the side starts with 6: after the Archer's 3 it is 3.
    match.play(0, match.deckIndex(0, "Archer"));
    assertThat(match.gate(0, match.deckIndex(0, "Giant"))).isEqualTo(LadderMatch.NOT_ENOUGH_ELIXIR);
  }

  @Test
  @DisplayName(
      "a fallen king ends the match: the winner, the frozen timeline, plays refused with 4, and"
          + " the battle stopped after the end screen's delay")
  void aFallenKingEndsTheMatch() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 0, 0);
    battle.getBattle().step();
    battle.getWorld().kill(battle.getWorld().kingTower(1), null);
    // The next step's head sees it and ends the match.
    battle.getBattle().step();

    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isZero();
    assertThat(match.crowns(0)).isEqualTo(3);
    assertThat(match.getTimeline().isFrozen()).isTrue();
    assertThat(match.getEndTimerMs()).isEqualTo(51);
    assertThat(match.gate(0, match.deckIndex(0, "Archer"))).isEqualTo(LadderMatch.OVER);
    int steps = 0;
    while (!match.isOver()) {
      battle.getBattle().step();
      steps++;
    }
    // From 51, 50 an update: 78 more updates tick the entities, the 79th only cleans up.
    assertThat(steps).isEqualTo(79);
    assertThat(match.isLastTicked()).isFalse();
    int tick = battle.getBattle().getTick();
    battle.getBattle().step();
    assertThat(battle.getBattle().getTick()).isEqualTo(tick);
  }

  @Test
  @DisplayName("both kings falling together leave the crowns equal: the tiebreaker, refused")
  void theTiebreakerIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    battle.getBattle().step();
    battle.getWorld().kill(battle.getWorld().kingTower(0), null);
    battle.getWorld().kill(battle.getWorld().kingTower(1), null);

    assertThatThrownBy(() -> battle.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("tiebreaker");
  }
}
