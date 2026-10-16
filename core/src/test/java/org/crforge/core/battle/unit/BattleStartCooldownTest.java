/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.PlayerCommands.Giving;
import org.crforge.core.battle.unit.PlayerCommands.Refusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The start cooldown of a Ladder battle: a card play is refused with its own code while the
 * battle's tick counter is below the game mode's start cooldown, in the client's try at the giving
 * and again at the run, after the hand slot and before the elixir; an ability command is not.
 */
class BattleStartCooldownTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** A point on side 0's half the map takes, away from the bridges. */
  private static final int X = 3500;

  private static final int Y = 8500;

  /** The second point, on the other lane, so two plays do not share a tile. */
  private static final int OTHER_X = 14500;

  private static final String MODE_COLUMN = "BattleStartCooldown";

  @TempDir Path folder;

  @Test
  @DisplayName(
      "a play given while the tick counter is below the start cooldown is refused with its code"
          + " and changes nothing; given at the cooldown it runs and is placed")
  void aPlayGivenBeforeTheCooldownIsRefused() {
    int cooldown = Shipped.startCooldownTicks();
    Standard1v1Battle battle = ladder(GameData.tables());
    MatchSide side = battle.getMatch().side(0);
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, cooldown - 1);
    String hand = side.getHand().toString();

    Giving early = commands.playCard(0, 0, LEVEL, X, Y, "early");
    Giving tried = commands.tryCard(0, 1, LEVEL);

    assertThat(early.refusal()).isEqualTo(Refusal.START_COOLDOWN);
    assertThat(early.code()).isEqualTo(LadderMatch.START_COOLDOWN);
    assertThat(tried.refusal()).isEqualTo(Refusal.START_COOLDOWN);
    assertThat(commands.promised(0)).isZero();
    battle.getBattle().step();
    assertThat(battle.getBattle().getTick()).isEqualTo(cooldown);
    assertThat(side.getHand().toString()).isEqualTo(hand);

    Giving onTime = commands.playCard(0, 0, LEVEL, X, Y, "onTime");

    assertThat(onTime.given()).isTrue();
    stepTo(battle, onTime.runTick() + 1);
    assertThat(battle.getPlays())
        .extracting(Standard1v1Battle.Play::name)
        .containsExactly("onTime");
    assertThat(battle.getPlays().get(0).units()).hasSize(1);
  }

  @Test
  @DisplayName(
      "a play queued to run below the start cooldown is refused at its run with its code: no"
          + " unit, the elixir and the hand unchanged; one run at the cooldown is placed")
  void aPlayRunBeforeTheCooldownIsRefusedAtItsRun() {
    GameTables tables = GameData.tables();
    int cooldown = Shipped.startCooldownTicks(tables);
    int delay = Standard1v1Battle.PLAY_DELAY_TICKS;
    Standard1v1Battle battle = ladder(tables);
    MatchSide side = battle.getMatch().side(0);
    DeployCard knight = battle.getWorld().getRecords().card("Knight");
    battle.play(cooldown - 1 - delay, cooldown - 1, knight, LEVEL, 0, X, Y, "early");
    battle.play(cooldown - delay, cooldown, knight, LEVEL, 0, OTHER_X, Y, "onTime");
    stepTo(battle, cooldown - 1);
    String hand = side.getHand().toString();
    int spent = side.getSpent();

    battle.getBattle().step();

    Standard1v1Battle.Play early = battle.getPlays().get(0);
    assertThat(early.name()).isEqualTo("early");
    assertThat(early.tick()).isEqualTo(cooldown - 1);
    assertThat(early.matchCode()).isEqualTo(LadderMatch.START_COOLDOWN);
    assertThat(early.units()).isEmpty();
    assertThat(side.getHand().toString()).isEqualTo(hand);
    assertThat(side.getSpent()).isEqualTo(spent);

    battle.getBattle().step();

    Standard1v1Battle.Play onTime = battle.getPlays().get(1);
    assertThat(onTime.name()).isEqualTo("onTime");
    assertThat(onTime.tick()).isEqualTo(cooldown);
    assertThat(onTime.matchCode()).isZero();
    assertThat(onTime.units()).hasSize(1);
    assertThat(side.getSpent() - spent).isEqualTo(Shipped.cost("Knight") * MatchSide.SCALE);
  }

  @Test
  @DisplayName(
      "the gate comes after the hand slot: a card in no hand slot is refused for that before the"
          + " start cooldown")
  void theHandSlotComesFirst() {
    Standard1v1Battle battle = ladder(GameData.tables());
    LadderMatch match = battle.getMatch();
    // Side 0's fifth card in its battle order waits in the queue.
    int queued = match.side(0).getHand().slots()[0];
    int waiting = -1;
    for (int index = 0; index < KNIGHTS.size() && waiting < 0; index++) {
      if (match.side(0).getHand().slotOf(index) < 0) {
        waiting = index;
      }
    }

    assertThat(match.gate(0, waiting, 0, battle.getBattle().getTick()))
        .isEqualTo(LadderMatch.NOT_IN_HAND);
    assertThat(match.gate(0, queued, 0, battle.getBattle().getTick()))
        .isEqualTo(LadderMatch.START_COOLDOWN);
  }

  @Test
  @DisplayName("a game mode row that sets its own start cooldown is gated on it, not on the global")
  void theModeRowsColumnWins() throws IOException {
    int global = Shipped.startCooldownTicks();
    int own = 2 * global;
    GameTables tables =
        GameData.altered(
            folder,
            "game_modes",
            rows ->
                GameData.columns(rows, LadderMatch.GAME_MODE)
                    .put(MODE_COLUMN, own * Battle.STEP_MS));
    assertThat(Shipped.startCooldownTicks(tables)).isEqualTo(own);
    int delay = Standard1v1Battle.PLAY_DELAY_TICKS;
    Standard1v1Battle battle = ladder(tables);
    DeployCard knight = battle.getWorld().getRecords().card("Knight");
    battle.play(global - delay, global, knight, LEVEL, 0, X, Y, "atGlobal");
    battle.play(own - delay, own, knight, LEVEL, 0, OTHER_X, Y, "atOwn");

    stepTo(battle, own + 1);

    assertThat(battle.getPlays())
        .extracting(Standard1v1Battle.Play::name, Standard1v1Battle.Play::matchCode)
        .containsExactly(tuple("atGlobal", LadderMatch.START_COOLDOWN), tuple("atOwn", 0));
  }

  @Test
  @DisplayName(
      "an ability command given and run below the start cooldown meets its own gates, not the"
          + " start cooldown")
  void anAbilityCommandIsNotGated() {
    int cooldown = Shipped.startCooldownTicks();
    int delay = Standard1v1Battle.PLAY_DELAY_TICKS;
    Standard1v1Battle battle = ladder(GameData.tables());
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, cooldown - 1 - delay);
    int king = battle.getWorld().kingTower(0).getId();

    Giving given = commands.useAbility(0, "nobody_0", "given");
    battle.useAbility(cooldown - 1 - delay, cooldown - 1, 0, king, "queued");
    stepTo(battle, cooldown);

    assertThat(given.refusal()).isEqualTo(Refusal.NO_CHAMPION);
    assertThat(battle.getAbilityUses()).hasSize(1);
    assertThat(battle.getAbilityUses().get(0).tick()).isEqualTo(cooldown - 1);
    assertThat(battle.getAbilityUses().get(0).outcome().code())
        .isEqualTo(AbilityCommand.NO_CHAMPION);
  }

  /** A Ladder battle of Knights on a set of tables. */
  private static Standard1v1Battle ladder(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(KNIGHTS, KNIGHTS, 0, 0);
    return battle;
  }

  /** Steps the battle until its tick counter reaches a tick. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
