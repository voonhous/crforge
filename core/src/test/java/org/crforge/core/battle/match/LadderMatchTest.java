package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A Ladder match's gates, and the end it does not model yet. */
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
  @DisplayName("a match whose king falls is refused at the next step, its end not modelled")
  void theEndIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    battle.getBattle().step();
    TowerEntity king = battle.getWorld().kingTower(1);
    battle.getWorld().kill(king, null);

    assertThatThrownBy(() -> battle.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("end of a match");
  }
}
