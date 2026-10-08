package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A Ladder match's gates, its end and its tiebreaker. */
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

  /**
   * The tick the Ladder timeline's time is up on with the crowns equal: the end of its sections,
   * the regular one and overtime, each SectionLength seconds of 20 ticks.
   */
  private static int timeUpTick() {
    GameRow timeline =
        Shipped.row(
            "battle_timelines",
            Shipped.text(Shipped.row("game_modes", "Ladder"), "BattleTimeline"));
    return Shipped.numbers(timeline, "SectionLength").stream().mapToInt(s -> s * 20).sum();
  }

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
    // The kill lands at the next step's damage drain; the head of the step after sees it and ends
    // the match.
    battle.getBattle().step();
    battle.getBattle().step();

    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isZero();
    assertThat(match.crowns(0)).isEqualTo(3);
    assertThat(match.getTimeline().isFrozen()).isTrue();
    assertThat(match.getEndTimerMs()).isEqualTo(51);
    assertThat(match.gate(0, match.deckIndex(0, "Archer"))).isEqualTo(LadderMatch.OVER);
    // From the end every ordinary hit is refused.
    TowerEntity king = battle.getWorld().kingTower(0);
    int hitPoints = king.getHitPoints().getHitPoints();
    assertThat(king.takeDamage(400, 0, 0, 1).landed()).isFalse();
    assertThat(king.getHitPoints().getHitPoints()).isEqualTo(hitPoints);
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
  @DisplayName(
      "both kings falling together leave the crowns equal: the tiebreaker, which idles to 3250 ms,"
          + " drains once and ends it a draw")
  void bothKingsFallingTogetherEndInADraw() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 0, 0);
    battle.getBattle().step();
    battle.getWorld().kill(battle.getWorld().kingTower(0), null);
    battle.getWorld().kill(battle.getWorld().kingTower(1), null);

    int steps = 0;
    while (!match.isEnded()) {
      battle.getBattle().step();
      steps++;
    }
    // The kills land at the first step's damage drain; then 66 steps of the tiebreaker: the 66th,
    // which begins at 3250 ms, drains and finds a king at 0; the next ends the match.
    assertThat(steps).isEqualTo(68);
    assertThat(match.getTiebreakMs()).isEqualTo(3300);
    assertThat(match.getWinner()).isEqualTo(-1);
    assertThat(List.of(match.crowns(0), match.crowns(1))).containsExactly(3, 3);
    assertThat(battle.getWorld().isHitsHeld()).isTrue();
    assertThat(battle.getWorld().isMatchEnded()).isTrue();
  }

  @Test
  @DisplayName(
      "the tiebreaker's clearing kills every unit, and ticks the entities on that step only")
  void theClearingKillsEveryUnit() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 0, 0);
    int timeUp = timeUpTick();
    CharacterEntity knight = battle.deploy(timeUp - 10, GameData.unit("Knight"), 11, 0, 3500, 5000);
    List<String> kills = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void clearingKilled(int tick, WorldEntity target) {
                // The world's tick is the last entity tick's; the battle's is the step's.
                kills.add(battle.getBattle().getTick() + " " + target.name());
              }
            });
    // The time is up at the end of overtime with the crowns equal; the tiebreaker's first step is
    // the one after.
    while (battle.getBattle().getTick() < timeUp + 1) {
      battle.getBattle().step();
    }
    assertThat(match.getTiebreakMs()).isZero();
    assertThat(battle.getWorld().isHitsHeld()).isFalse();
    assertThat(kills).isEmpty();

    battle.getBattle().step();
    assertThat(kills).containsExactly((timeUp + 1) + " Knight");
    assertThat(match.getTiebreakMs()).isEqualTo(50);
    assertThat(match.isLastTicked()).as("the update ran").isTrue();
    assertThat(battle.getWorld().getHolder().entities()).doesNotContain(knight);
    // From the tiebreaker's first step every ordinary hit is refused, before any end.
    assertThat(battle.getWorld().isHitsHeld()).isTrue();
    assertThat(battle.getWorld().isMatchEnded()).isFalse();
    TowerEntity king = battle.getWorld().kingTower(1);
    int hitPoints = king.getHitPoints().getHitPoints();
    assertThat(king.takeDamage(400, 0, 0, 1).landed()).isFalse();
    assertThat(king.getHitPoints().getHitPoints()).isEqualTo(hitPoints);

    battle.getBattle().step();
    assertThat(match.getTiebreakMs()).isEqualTo(100);
    assertThat(match.isLastTicked()).as("nothing to clear, no update").isFalse();
    assertThat(match.isEnded()).isFalse();
  }

  @Test
  @DisplayName("the clearing refuses an object the holder would remove at once")
  void theClearingRefusesWhatItRemoves() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    battle.addActionOwner("owner", 0, 9000, 5000, 0);
    while (battle.getBattle().getTick() < timeUpTick() + 1) {
      battle.getBattle().step();
    }
    assertThatThrownBy(() -> battle.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("clearing");
  }

  @Test
  @DisplayName("the drain's step by the lowest tower: 1, 10, 20, 40 or 50")
  void theDrainSteps() {
    int[][] cases = {
      {1, 1},
      {20, 1},
      {21, 10},
      {199, 10},
      {200, 20},
      {499, 20},
      {500, 40},
      {999, 40},
      {1000, 50},
      {4824, 50}
    };
    for (int[] c : cases) {
      assertThat(LadderMatch.drainStep(c[0])).as("lowest %d", c[0]).isEqualTo(c[1]);
    }
  }
}
