package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The draws a battle's setup takes for its players' data, before the decks are dealt: they move the
 * battle's random source, so the shuffles that follow are seeded by later draws.
 */
class BattlePlayerDataTest {

  /** The seed of the recorded battle. */
  private static final int SEED = 1131;

  private static final List<String> DECK =
      List.of("Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static Standard1v1Battle battle() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), 1);
    battle.getWorld().seed(SEED);
    return battle;
  }

  @Test
  @DisplayName(
      "each player's data draws once before the decks are dealt: the recorded battle's opening"
          + " hands, queues and random state")
  void thePlayersDataDrawBeforeTheShuffles() {
    Standard1v1Battle battle = battle();
    // Each player's data lists one choice: a draw below 1 answers 0 and still moves the source.
    assertThat(battle.addPlayerData(1)).isZero();
    assertThat(battle.addPlayerData(1)).isZero();
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 1, 2);

    assertThat(match.side(0).getHand().slots()).containsExactly(7, 1, 0, 2);
    assertThat(match.side(0).getHand().queue()).containsExactly(5, 4, 3, 6);
    assertThat(match.side(1).getHand().slots()).containsExactly(5, 6, 7, 3);
    assertThat(match.side(1).getHand().queue()).containsExactly(0, 1, 4, 2);
    assertThat(Integer.toUnsignedLong(battle.getWorld().getRandom().getState()))
        .isEqualTo(4153772180L);
    assertThat(battle.getPlayerDataPicks()).containsExactly(0, 0);
  }

  @Test
  @DisplayName("the shuffles are seeded by the draws after the players' data's")
  void theShufflesTakeTheLaterDraws() {
    Standard1v1Battle battle = battle();
    battle.addPlayerData(1);
    battle.addPlayerData(1);
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 1, 2);

    BattleRandom stream = new BattleRandom(SEED);
    stream.next(1);
    stream.next(1);
    assertThat(match.shuffleDraw(0)).isEqualTo(stream.next(0x0fffffff));
    assertThat(match.shuffleDraw(1)).isEqualTo(stream.next(0x0fffffff));
  }

  @Test
  @DisplayName("data that lists no choice draws nothing")
  void dataWithoutChoicesDrawsNothing() {
    Standard1v1Battle battle = battle();

    assertThat(battle.addPlayerData(0)).isEqualTo(Standard1v1Battle.NO_PICK);

    assertThat(battle.getWorld().getRandom().getState()).isEqualTo(SEED);
    assertThat(battle.getPlayerDataPicks()).containsExactly(Standard1v1Battle.NO_PICK);
  }

  @Test
  @DisplayName("a pick among several choices is the draw below their number")
  void aPickAmongSeveralChoices() {
    Standard1v1Battle battle = battle();
    int expected = new BattleRandom(SEED).next(5);

    assertThat(battle.addPlayerData(5)).isEqualTo(expected);
  }

  @Test
  @DisplayName("a player's data is handed over before the decks are dealt, never after")
  void dataAfterTheMatchIsSetUpIsRefused() {
    Standard1v1Battle battle = battle();
    battle.startLadderMatch(DECK, DECK, 1, 2);

    assertThatThrownBy(() -> battle.addPlayerData(1)).isInstanceOf(IllegalStateException.class);
  }
}
