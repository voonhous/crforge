package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * A champion slot's refund window, on an Archer Queen whose ability row is written by hand: the
 * window is the row's RefundWindow, or its trigger delay for a row without one; it counts down
 * while a copy casts; and a refund only a held window lets through is refused.
 */
class BattleChampionRefundTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String QUEEN = "ArcherQueen";

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

  /** A battle on tables whose Archer Queen ability row is altered, her refunds listed. */
  private static final class Scene {
    final Standard1v1Battle battle;
    final LadderMatch match;
    final List<String> refunds = new ArrayList<>();

    Scene(Path folder, Consumer<ObjectNode> ability) throws IOException {
      GameTables tables =
          GameData.altered(
              folder,
              "character_abilities",
              rows -> ability.accept(GameData.columns(rows, abilityName())));
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
                  refunds.add(mana + " " + before + " " + after);
                }
              });
    }

    /**
     * Plays her on the next tick side 0 holds her cost and the given whole elixir more, and runs
     * that tick; the play passes.
     */
    CharacterEntity playQueen(int more) {
      int cost = GameData.records().matchCard(QUEEN).cost();
      while (match.side(0).wholeElixir() < cost + more) {
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

    /** Uses her ability on the next tick and runs that tick; the command passes and pays. */
    void useAbility() {
      int tick = battle.getBattle().getTick();
      battle.useAbility(tick, 0, "q_0", "a");
      run(tick);
      List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
      AbilityCommand.Outcome outcome = uses.get(uses.size() - 1).outcome();
      assertThat(outcome.code()).isEqualTo(AbilityCommand.OK);
      assertThat(outcome.elixirAfter()).isLessThan(outcome.elixirBefore());
    }

    /** Steps past her deploy and a tick, until side 0 holds her ability's cost. */
    void afterDeployWithElixir() {
      int deployed =
          battle.getBattle().getTick() + Shipped.number(Shipped.unitRow(QUEEN), "DeployTime") / 50;
      int cost = battle.getWorld().getRecords().unit(QUEEN).ability().manaCost();
      while (battle.getBattle().getTick() <= deployed || match.side(0).wholeElixir() < cost) {
        battle.getBattle().step();
      }
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
      battle.getWorld().kill(queen, null);
      step(4);
      assertThat(HitPoints.alive(queen.getHitPoints())).isFalse();
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

  @Test
  @DisplayName(
      "a champion killed while she casts, past her ability's trigger delay but inside its"
          + " RefundWindow, is given its cost back once")
  void aDeathInsideTheRefundWindowIsRefunded(@TempDir Path folder) throws IOException {
    Scene scene =
        new Scene(
            folder,
            ability ->
                ability.put("RefundWindow", 400).put("TriggerDelay", 50).put("CastTime", 2000));
    CharacterEntity queen = scene.playQueen(0);
    scene.afterDeployWithElixir();
    scene.useAbility();
    // Two steps on she still casts: the trigger delay has passed, the refund window has not.
    scene.step(2);
    assertThat(queen.getView().getState()).isEqualTo(ChampionController.CASTING);
    scene.kill(queen);
    int cost = scene.battle.getWorld().getRecords().unit(QUEEN).ability().manaCost();
    assertThat(scene.refunds).hasSize(1);
    String[] refund = scene.refunds.get(0).split(" ");
    assertThat(Integer.parseInt(refund[0])).isEqualTo(cost);
    assertThat(Integer.parseInt(refund[2]) - Integer.parseInt(refund[1]))
        .isEqualTo(cost * KingElixir.SCALE);
  }

  @Test
  @DisplayName(
      "an ability row without a RefundWindow opens the window for its trigger delay: a champion"
          + " killed inside it is given its cost back, one killed after it is not")
  void noRefundWindowTakesTheTriggerDelay(@TempDir Path folder) throws IOException {
    Consumer<ObjectNode> ability =
        row -> {
          row.remove("RefundWindow");
          row.put("TriggerDelay", 200).put("CastTime", 2000);
        };
    Scene inside = new Scene(Files.createDirectories(folder.resolve("inside")), ability);
    CharacterEntity queen = inside.playQueen(0);
    inside.afterDeployWithElixir();
    inside.useAbility();
    inside.kill(queen);
    assertThat(inside.refunds).hasSize(1);

    Scene after = new Scene(Files.createDirectories(folder.resolve("after")), ability);
    queen = after.playQueen(0);
    after.afterDeployWithElixir();
    after.useAbility();
    after.step(5);
    assertThat(queen.getView().getState()).isEqualTo(ChampionController.CASTING);
    after.kill(queen);
    assertThat(after.refunds).isEmpty();
  }

  @Test
  @DisplayName("the refund window runs out while she casts: a later death gives nothing back")
  void theWindowRunsOutWhileSheCasts(@TempDir Path folder) throws IOException {
    Scene scene =
        new Scene(
            folder,
            ability ->
                ability.put("RefundWindow", 100).put("TriggerDelay", 1000).put("CastTime", 2000));
    CharacterEntity queen = scene.playQueen(0);
    scene.afterDeployWithElixir();
    scene.useAbility();
    scene.step(4);
    assertThat(queen.getView().getState()).isEqualTo(ChampionController.CASTING);
    scene.kill(queen);
    assertThat(scene.refunds).isEmpty();
  }

  @Test
  @DisplayName(
      "a refund window that holds while she waits to cast, her death past its length, is refused"
          + " as she dies rather than refunded")
  void aRefundOnlyAHeldWindowAllowsIsRefused(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder, ability -> ability.put("RefundWindow", 100));
    // With her ability's cost left over, to use it at once.
    CharacterEntity queen =
        scene.playQueen(scene.battle.getWorld().getRecords().unit(QUEEN).ability().manaCost());
    // Used while she deploys: the ability waits, and the window holds.
    scene.useAbility();
    scene.step(3);
    assertThat(queen.getView().getState()).isNotEqualTo(ChampionController.CASTING);
    scene.battle.getWorld().kill(queen, null);
    assertThatThrownBy(() -> scene.step(4))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("refund window held");
  }
}
