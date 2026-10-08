package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Elixir the battle's units pay the kings in a match: a death's to the side that killed the unit,
 * and a collector's to its own king, which only a match has.
 */
class BattleElixirTest {

  /** Level 11, packed. */
  private static final int LEVEL_11 = 10;

  private static final List<String> DECK = Collections.nCopies(8, "Knight");

  /** Lists every death payout as "unit side amount". */
  private static List<String> deathPayouts(Standard1v1Battle battle) {
    List<String> paid = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void deathElixirPaid(int tick, WorldEntity dying, int side, int amount) {
                paid.add(dying.name() + " " + side + " " + amount);
              }
            });
    return paid;
  }

  @Test
  @DisplayName(
      "an Elixir Golem killed by a buff's damage over time pays the side the buff was applied for")
  void aBuffKillPaysTheBuffsSide() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(DECK, DECK, 0, 0);
    CharacterEntity golem = battle.deploy(0, GameData.unit("ElixirGolem1"), 11, 0, 9000, 6000);
    List<String> paid = deathPayouts(battle);
    battle.getBattle().step();
    golem.getHitPoints().setHitPoints(1);
    golem.getBuffs().apply(GameData.records().buff("Poison"), 5000, LEVEL_11, null, 1);
    int before = match.side(1).getElixir();
    for (int i = 0; i < 40 && paid.isEmpty(); i++) {
      battle.getBattle().step();
    }
    assertThat(paid).containsExactly("ElixirGolem1 1 10000");
    assertThat(match.side(1).getElixir()).isGreaterThanOrEqualTo(before + 10000);
  }

  @Test
  @DisplayName("a kill with no side pays nothing")
  void aKillWithNoSidePaysNothing() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(DECK, DECK, 0, 0);
    CharacterEntity golem = battle.deploy(0, GameData.unit("ElixirGolem1"), 11, 0, 9000, 6000);
    List<String> paid = deathPayouts(battle);
    battle.getBattle().step();
    battle.getWorld().kill(golem, null);
    // The kill lands at the damage drain of the next step.
    battle.getBattle().step();
    assertThat(HitPoints.alive(golem.getHitPoints())).isFalse();
    assertThat(paid).isEmpty();
  }

  @Test
  @DisplayName("an elixir collector outside a match is refused where its elixir block first runs")
  void aCollectorOutsideAMatchIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.deploy(0, GameData.unit("ElixirCollector"), 11, 0, 14500, 8000);
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 60; i++) {
                battle.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("outside a match");
  }
}
