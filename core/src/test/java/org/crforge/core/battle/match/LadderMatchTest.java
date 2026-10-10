/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.UnitData;
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

  /** A deck with the spells the clearing tests cast. */
  private static final List<String> SPELL_DECK =
      List.of(
          "Knight", "Archer", "Giant", "MiniPekka", "Musketeer", "Arrows", "Poison", "Fireball");

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
  @DisplayName(
      "the clearing's kill lands at the damage drain of the update it runs, so the unit it kills"
          + " takes that update's step first")
  void theClearingKillLandsAtTheDrain() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    int timeUp = timeUpTick();
    // Side 0's Knight walks up its lane, out of every tower's reach, as the time is up.
    CharacterEntity knight =
        battle.deploy(timeUp - 40, GameData.unit("Knight"), 11, 0, 3500, 12000);
    List<String> kills = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void clearingKilled(int tick, WorldEntity target) {
                kills.add(
                    target.name() + " " + target.getView().getX() + "," + target.getView().getY());
              }
            });
    stepTo(battle, timeUp + 1);
    int y = knight.getView().getY();
    assertThat(knight.getView().getState()).as("the Knight walks").isEqualTo(1);

    battle.getBattle().step();
    // The clearing queued the kill; the update the clearing runs moved the Knight on, and its
    // drain then killed it where the step left it.
    assertThat(knight.getView().getY()).as("the Knight stepped on").isNotEqualTo(y);
    assertThat(kills)
        .containsExactly("Knight " + knight.getView().getX() + "," + knight.getView().getY());
    assertThat(knight.getHitPoints().getHitPoints()).isZero();
    assertThat(battle.getWorld().getHolder().entities()).doesNotContain(knight);
  }

  @Test
  @DisplayName(
      "the clearing removes a projectile at once, and the object after a removed one waits for the"
          + " next step")
  void theClearingRemovesProjectilesAtOnce() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(SPELL_DECK, SPELL_DECK, 0, 0);
    int timeUp = timeUpTick();
    // A volley thrown from side 0's king across the arena is still in the air when the time is up.
    battle.play(timeUp - 5, GameData.card("Arrows"), 1, 0, 9000, 22000, "volley");
    stepTo(battle, timeUp + 1);
    List<BattleEntity> flying = projectiles(battle);
    assertThat(flying).as("the volley in the air").hasSizeGreaterThanOrEqualTo(3);

    // Each removal moves the rest up one place while the walk steps on: every other one goes.
    while (!flying.isEmpty()) {
      battle.getBattle().step();
      assertThat(projectiles(battle)).isEqualTo(everyOther(flying));
      assertThat(match.isLastTicked()).as("the step removed something, so the update ran").isTrue();
      flying = projectiles(battle);
    }
    battle.getBattle().step();
    assertThat(match.isLastTicked()).as("nothing to clear, no update").isFalse();
  }

  @Test
  @DisplayName(
      "the clearing ends an area effect's life and removes it at once, so the projectile after it"
          + " waits for the next step")
  void theClearingRemovesAnAreaEffectAtOnce() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(SPELL_DECK, SPELL_DECK, 0, 0);
    int timeUp = timeUpTick();
    // Placed directly, as the hand need not hold the card.
    battle.placeAreaEffect(timeUp - 10, "Poison", 1, 1, 9000, 16000, "poison");
    battle.play(timeUp - 5, GameData.card("Arrows"), 1, 0, 9000, 22000, "volley");
    stepTo(battle, timeUp + 1);
    List<BattleEntity> listed = new ArrayList<>(battle.getWorld().getHolder().entities());
    AreaEffectEntity poison = (AreaEffectEntity) listed.get(0);
    List<BattleEntity> flying = projectiles(battle);
    assertThat(flying).as("the volley in the air").hasSizeGreaterThanOrEqualTo(2);

    battle.getBattle().step();
    List<BattleEntity> after = battle.getWorld().getHolder().entities();
    assertThat(after).doesNotContain(poison);
    assertThat(poison.isRemovable()).as("its life ended").isTrue();
    // The first projectile moved into the area effect's place as the walk stepped on.
    assertThat(after).contains(flying.get(0));
    assertThat(after).doesNotContain(flying.get(1));
  }

  @Test
  @DisplayName(
      "the clearing removes a character without hit points at once, so the unit after it is"
          + " killed on the next step")
  void theClearingRemovesACharacterWithoutHitPointsAtOnce() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    int timeUp = timeUpTick();
    UnitData bomb = GameData.unit("BombTowerBomb");
    CharacterEntity dropped = battle.deploy(timeUp - 5, bomb, 11, 0, 9000, 9000);
    CharacterEntity knight = battle.deploy(timeUp - 5, GameData.unit("Knight"), 11, 0, 3500, 9000);
    List<String> kills = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void clearingKilled(int tick, WorldEntity target) {
                kills.add(battle.getBattle().getTick() + " " + target.name());
              }
            });
    stepTo(battle, timeUp + 1);
    assertThat(dropped.getHitPoints()).as("a character without hit points").isNull();
    assertThat(knight.getId()).isGreaterThan(dropped.getId());

    battle.getBattle().step();
    assertThat(battle.getWorld().getHolder().entities()).doesNotContain(dropped).contains(knight);
    assertThat(kills).isEmpty();
    battle.getBattle().step();
    // The battle's tick counter during a step is the step's own: the second step of the tiebreaker.
    assertThat(kills).containsExactly((timeUp + 2) + " Knight");
    assertThat(battle.getWorld().getHolder().entities()).doesNotContain(knight);
  }

  @Test
  @DisplayName("the clearing refuses the stand-in owner of actions")
  void theClearingRefusesAStandIn() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    battle.addActionOwner("owner", 0, 9000, 5000, 0);
    stepTo(battle, timeUpTick() + 1);
    assertThatThrownBy(() -> battle.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("clearing");
  }

  /** Steps the battle until its tick counter reaches a tick. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }

  /** The projectiles in the holder's live list, in its order. */
  private static List<BattleEntity> projectiles(Standard1v1Battle battle) {
    List<BattleEntity> found = new ArrayList<>();
    for (BattleEntity entity : battle.getWorld().getHolder().entities()) {
      if (entity instanceof ProjectileEntity) {
        found.add(entity);
      }
    }
    return found;
  }

  /** The entries at the odd places of a list: what a walk that removes as it steps on leaves. */
  private static List<BattleEntity> everyOther(List<BattleEntity> list) {
    List<BattleEntity> kept = new ArrayList<>();
    for (int i = 1; i < list.size(); i += 2) {
      kept.add(list.get(i));
    }
    return kept;
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
