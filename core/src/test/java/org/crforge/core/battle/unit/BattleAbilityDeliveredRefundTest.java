/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An ability row with RefundUntilDelivered, on an Archer Queen whose ability row is written by
 * hand: a use opens no refund window; its cost rides on the unit that was paid for until an
 * ActionAbilityDelivered runs on it, and is given back to its side as that unit leaves the battle
 * with the cost still on it, however long after the use. A row without the column keeps its refund
 * window.
 */
class BattleAbilityDeliveredRefundTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String QUEEN = "ArcherQueen";

  /** The hand-written ActionAbilityDelivered row. */
  private static final String DELIVERED = "Test_ability_delivered";

  /** An Archer Queen and seven Skeletons, which cycle her back for 1 elixir a play. */
  private static final List<String> QUEEN_SKELETONS =
      List.of(
          QUEEN,
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /**
   * A battle on tables whose Archer Queen ability row is altered, with one more action row: an
   * ActionAbilityDelivered with no column of its own. The slot's refunds are listed.
   */
  private static final class Scene {
    final Standard1v1Battle battle;
    final LadderMatch match;
    final List<String> slotRefunds = new ArrayList<>();
    final List<String> costReturns = new ArrayList<>();

    Scene(Path folder, Consumer<ObjectNode> ability) throws IOException {
      GameData.altered(
          folder,
          "character_abilities",
          rows -> ability.accept(GameData.columns(rows, abilityName())));
      GameData.alterLoaded(
          folder,
          "actions",
          rows -> {
            ObjectNode row = rows.putObject(DELIVERED);
            row.put("class", "LogicActionAbilityDeliveredData");
            row.put("ClassType", "ActionAbilityDelivered");
            row.putObject("fields").put("ClassType", "ActionAbilityDelivered");
          });
      GameTables tables = GameTables.load(folder);
      Standard1v1Battle made = null;
      LadderMatch started = null;
      // The first player's word whose shuffle deals her into the opening hand.
      for (int word = 0; started == null || !inHand(started, QUEEN); word++) {
        made = new Standard1v1Battle(tables, LEVEL, false);
        started = made.startLadderMatch(QUEEN_SKELETONS, KNIGHTS, word, 0);
      }
      battle = made;
      match = started;
      battle
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void championRefunded(
                    int tick, ChampionController slot, int mana, int before, int after) {
                  slotRefunds.add(mana + " " + before + " " + after);
                }

                @Override
                public void undeliveredCostReturned(
                    int tick, CharacterEntity unit, int mana, int before, int after) {
                  costReturns.add(unit.name() + " " + mana + " " + (after - before));
                }
              });
    }

    /** Plays her on the next tick side 0 holds her cost and her ability's, and runs that tick. */
    CharacterEntity playQueen() {
      int cost = GameData.records().matchCard(QUEEN).cost();
      int more = battle.getWorld().getRecords().unit(QUEEN).ability().manaCost();
      while (battle.getBattle().getTick() < match.getStartCooldownTicks()
          || match.side(0).wholeElixir() < cost + more) {
        battle.getBattle().step();
      }
      int tick = battle.getBattle().getTick();
      battle.play(tick, battle.getWorld().getRecords().card(QUEEN), LEVEL, 0, 3500, 4000, "q");
      run(tick);
      Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
      assertThat(play.matchCode()).as("her play passes the match's gates").isZero();
      CharacterEntity queen = play.units().get(0);
      // Kept where she stands, so she takes no tower.
      queen.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return queen;
    }

    /** Steps past her deploy and a tick, then uses her ability on the next tick; it pays. */
    void useAbilityAfterDeploy() {
      int deployed =
          battle.getBattle().getTick() + Shipped.number(Shipped.unitRow(QUEEN), "DeployTime") / 50;
      while (battle.getBattle().getTick() <= deployed) {
        battle.getBattle().step();
      }
      int tick = battle.getBattle().getTick();
      battle.useAbility(tick, 0, "q_0", "a");
      run(tick);
      List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
      AbilityCommand.Outcome outcome = uses.get(uses.size() - 1).outcome();
      assertThat(outcome.code()).isEqualTo(AbilityCommand.OK);
      assertThat(outcome.elixirAfter()).isLessThan(outcome.elixirBefore());
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        battle.getBattle().step();
      }
    }

    /** Steps the battle until it has run the given tick. */
    void run(int lastTick) {
      while (battle.getBattle().getTick() <= lastTick) {
        battle.getBattle().step();
      }
    }

    /** Kills her, which lands at the damage drain of the next step, and runs a few steps more. */
    void kill(CharacterEntity queen) {
      battle.getWorld().hits().kill(queen, null);
      step(4);
      assertThat(HitPoints.alive(queen.getHitPoints())).isFalse();
      assertThat(battle.getWorld().getHolder().isListed(queen)).as("she has left").isFalse();
    }

    /** Side 0's elixir, in ten-thousandths. */
    int elixir() {
      return battle.getWorld().elixir(0);
    }
  }

  private static String abilityName() {
    return Shipped.text(Shipped.unitRow(QUEEN), "Ability");
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /** The ability's cost, in ten-thousandths of elixir. */
  private static int costScaled(Scene scene) {
    return scene.battle.getWorld().getRecords().unit(QUEEN).ability().manaCost() * KingElixir.SCALE;
  }

  /** A use with no cast time and no trigger delay, its window one step: spent on the use's step. */
  private static Consumer<ObjectNode> instant(boolean untilDelivered, String onActivation) {
    return row -> {
      row.put("RefundWindow", 50).put("TriggerDelay", 0).put("CastTime", 0);
      if (untilDelivered) {
        row.put("RefundUntilDelivered", true);
      }
      if (onActivation != null) {
        row.put("OnActivationAction", onActivation);
      }
    };
  }

  /**
   * Plays her, uses her ability, runs the given steps, kills her and returns side 0's elixir once
   * she has left.
   */
  private static int elixirAfterLeaving(Scene scene, int stepsBeforeKill) {
    CharacterEntity queen = scene.playQueen();
    scene.useAbilityAfterDeploy();
    scene.step(stepsBeforeKill);
    scene.kill(queen);
    return scene.elixir();
  }

  @Test
  @DisplayName(
      "an ability row with RefundUntilDelivered and nothing that delivers it gives the cost back"
          + " as the unit leaves, long after its one-step refund window would have shut")
  void anUndeliveredUseIsGivenBackAtTheLeaving(@TempDir Path folder) throws IOException {
    Scene plain = new Scene(Files.createDirectories(folder.resolve("plain")), instant(false, null));
    int without = elixirAfterLeaving(plain, 20);
    assertThat(plain.slotRefunds).as("the window shut on the use's step").isEmpty();

    Scene untilDelivered =
        new Scene(Files.createDirectories(folder.resolve("until")), instant(true, null));
    int with = elixirAfterLeaving(untilDelivered, 20);
    assertThat(untilDelivered.slotRefunds).as("the slot opens no window").isEmpty();
    assertThat(with - without).isEqualTo(costScaled(untilDelivered));
    int mana = untilDelivered.battle.getWorld().getRecords().unit(QUEEN).ability().manaCost();
    // Given back once, by her: the first unit of the play named "q".
    assertThat(untilDelivered.costReturns)
        .containsExactly("q_0 " + mana + " " + costScaled(untilDelivered));
  }

  @Test
  @DisplayName(
      "an ActionAbilityDelivered the ability runs on the unit takes the cost off it: its death"
          + " gives nothing back")
  void aDeliveredUseIsNotGivenBack(@TempDir Path folder) throws IOException {
    Scene plain = new Scene(Files.createDirectories(folder.resolve("plain")), instant(false, null));
    int without = elixirAfterLeaving(plain, 20);

    Scene delivered =
        new Scene(Files.createDirectories(folder.resolve("delivered")), instant(true, DELIVERED));
    int with = elixirAfterLeaving(delivered, 20);
    assertThat(delivered.slotRefunds).isEmpty();
    assertThat(delivered.costReturns).isEmpty();
    assertThat(with).isEqualTo(without);
  }

  @Test
  @DisplayName(
      "with RefundUntilDelivered a death inside the row's refund window, after the ability"
          + " delivered, gives nothing back, where the window alone would have")
  void theWindowGivesNothingBackOnceDelivered(@TempDir Path folder) throws IOException {
    // A long window and a long cast: she dies while she still casts, past the trigger delay that
    // fired the delivering action.
    Consumer<ObjectNode> windowed =
        row -> row.put("RefundWindow", 2000).put("TriggerDelay", 100).put("CastTime", 2000);
    Scene window = new Scene(Files.createDirectories(folder.resolve("window")), windowed);
    int windowElixir = elixirAfterLeaving(window, 4);
    assertThat(window.slotRefunds).as("the window alone gives it back").hasSize(1);

    Scene delivered =
        new Scene(
            Files.createDirectories(folder.resolve("delivered")),
            windowed.andThen(
                row -> row.put("RefundUntilDelivered", true).put("OnActivationAction", DELIVERED)));
    int deliveredElixir = elixirAfterLeaving(delivered, 4);
    assertThat(delivered.slotRefunds).isEmpty();
    assertThat(windowElixir - deliveredElixir).isEqualTo(costScaled(delivered));
  }

  @Test
  @DisplayName(
      "with RefundUntilDelivered a death while she still casts, before the trigger delay fires"
          + " the delivering action, gives the cost back once, as she leaves")
  void aDeathBeforeTheDeliveryIsGivenBackOnce(@TempDir Path folder) throws IOException {
    Consumer<ObjectNode> late =
        row ->
            row.put("RefundWindow", 50)
                .put("TriggerDelay", 1500)
                .put("CastTime", 2000)
                .put("RefundUntilDelivered", true)
                .put("OnActivationAction", DELIVERED);
    Scene scene = new Scene(Files.createDirectories(folder.resolve("late")), late);
    CharacterEntity queen = scene.playQueen();
    scene.useAbilityAfterDeploy();
    scene.step(2);
    assertThat(queen.getView().getState()).isEqualTo(ChampionController.CASTING);
    int before = scene.elixir();
    scene.kill(queen);
    assertThat(scene.slotRefunds).isEmpty();
    assertThat(scene.costReturns).hasSize(1);
    // Six steps of regeneration at most between the two reads; the cost is far more than that.
    assertThat(scene.elixir() - before).isBetween(costScaled(scene), 2 * costScaled(scene) - 1);
  }
}
