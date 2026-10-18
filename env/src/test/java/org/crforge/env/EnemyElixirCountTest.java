/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.AbilityCommand;
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The enemy elixir a seat counts against the enemy's true elixir, on the configured tables: the
 * gains the game shows the other player (a collector's production, the elixir a death pays) are
 * counted when they are paid, a champion's refund, which the game does not show, is not, and an
 * ability command is charged as the battle charges it. The decks name rows of the tables; every
 * expected amount is read from the battle; ability rows altered for a test are written by hand.
 */
class EnemyElixirCountTest {

  private static final String COLLECTOR = "Elixir Collector";

  private static final String GOLEM = "ElixirGolem";

  /** Two decks with a collector and an Elixir Golem, whose pieces pay the side that kills them. */
  private static final List<String> PUMP_0 =
      List.of(COLLECTOR, GOLEM, "Knight", "Archer", "Musketeer", "Giant", "Fireball", "Arrows");

  private static final List<String> PUMP_1 =
      List.of(COLLECTOR, GOLEM, "Knight", "Archer", "MiniPekka", "Giant", "Fireball", "Zap");

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final String QUEEN = "ArcherQueen";

  private static final List<String> QUEEN_DECK =
      List.of(QUEEN, "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows");

  /** The hero Wizard's deck, the Wizard in the hero slot. */
  private static final List<String> HERO_DECK =
      List.of("Wizard", "Archer", "Knight", "Giant", "Minions", "Valkyrie", "Fireball", "Arrows");

  /** What the battle paid each side's king beyond the regeneration, as the core reports it. */
  private static final class Gains implements WorldObserver {
    final int[] collected = new int[2];
    final int[] deathMana = new int[2];
    final int[] deathReward = new int[2];

    /** Each refund: its side and what it added to the king, in ten-thousandths. */
    final List<int[]> refunds = new ArrayList<>();

    @Override
    public void elixirCollected(int tick, WorldEntity collector, int side, int amount) {
      collected[side]++;
    }

    @Override
    public void deathManaPaid(int tick, WorldEntity dying, int side, int amount) {
      deathMana[side]++;
    }

    @Override
    public void deathElixirPaid(int tick, WorldEntity dying, int side, int amount) {
      deathReward[side]++;
    }

    @Override
    public void championRefunded(
        int tick, ChampionController slot, int mana, int elixirBefore, int elixirAfter) {
      refunds.add(new int[] {slot.side(), elixirAfter - elixirBefore});
    }
  }

  private static Standard1v1Battle battle(VectorEnv env) {
    return ((LadderEnv) env.env(0)).battle();
  }

  private static int header(VectorEnv env, int seat, int field) {
    return env.observations().asIntBuffer().get(seat * Schema.SEAT_INTS + field);
  }

  private static boolean open(VectorEnv env, int seat, int action) {
    return env.masks().get(seat * Schema.ACTIONS + action) == 1;
  }

  private static void act(VectorEnv env, int action0, int action1) {
    IntBuffer actions = env.actions().asIntBuffer();
    actions.put(0, action0);
    actions.put(1, action1);
    env.step();
  }

  /** The true elixir of a side, in ten-thousandths. */
  private static int truth(VectorEnv env, int side) {
    return battle(env).getMatch().side(side).getElixir();
  }

  /** The enemy elixir a seat's header shows. */
  private static int counted(VectorEnv env, int seat) {
    return header(env, seat, Schema.H_ENEMY_ELIXIR);
  }

  /** The hand slot holding a card, or -1. */
  private static int handSlot(VectorEnv env, int seat, Vocabulary vocabulary, String card) {
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      if (header(env, seat, Schema.H_HAND_CARD + k) == vocabulary.id(card)) {
        return k;
      }
    }
    return -1;
  }

  /** A random open play of a hand slot, or -1 for none. */
  private static int randomTile(VectorEnv env, int seat, int slot, SplittableRandom random) {
    List<Integer> open = new ArrayList<>();
    for (int r = 0; r < Schema.ROWS; r++) {
      for (int c = 0; c < Schema.COLS; c++) {
        if (open(env, seat, Schema.play(slot, c, r))) {
          open.add(Schema.play(slot, c, r));
        }
      }
    }
    return open.isEmpty() ? -1 : open.get(random.nextInt(open.size()));
  }

  /** A random open action of a seat with a probability out of ten, else the wait. */
  private static int randomAction(VectorEnv env, int seat, SplittableRandom random, int inTen) {
    if (random.nextInt(10) >= inTen) {
      return Schema.WAIT;
    }
    List<Integer> open = new ArrayList<>();
    for (int a = 1; a < Schema.ACTIONS; a++) {
      if (open(env, seat, a)) {
        open.add(a);
      }
    }
    return open.isEmpty() ? Schema.WAIT : open.get(random.nextInt(open.size()));
  }

  /**
   * A seat's action: its collector once it holds the elixir for it (saving for it while it is in
   * the hand), else its golem wherever it is open, else a random action.
   */
  private static int pumpAction(
      VectorEnv env, int seat, Vocabulary vocabulary, SplittableRandom random) {
    int collector = handSlot(env, seat, vocabulary, COLLECTOR);
    if (collector >= 0) {
      return Math.max(Schema.WAIT, randomTile(env, seat, collector, random));
    }
    int golem = handSlot(env, seat, vocabulary, GOLEM);
    int play = golem < 0 ? -1 : randomTile(env, seat, golem, random);
    return play >= 0 ? play : randomAction(env, seat, random, 3);
  }

  /** A Ladder vector of one environment, reset on the first seed whose seat holds a card. */
  private static VectorEnv withCardInHand(
      GameTables tables,
      List<String> deck0,
      List<String> deck1,
      int[] slots1,
      int seat,
      String card) {
    VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1);
    env.setAutoreset(false);
    Vocabulary vocabulary = VectorEnv.vocabulary(tables);
    for (int seed = 100; seed < 300; seed++) {
      env.reset(new int[] {seed}, deck0, deck1, null, slots1);
      if (handSlot(env, seat, vocabulary, card) >= 0) {
        return env;
      }
    }
    env.close();
    throw new AssertionError("no seed deals " + card + " to seat " + seat);
  }

  /** Tables whose ability row of a unit is altered by hand. */
  private static GameTables abilityAltered(Path folder, String unit, Consumer<ObjectNode> edit)
      throws IOException {
    String ability = GameData.unit(unit).ability().name();
    return GameData.altered(
        folder, "character_abilities", rows -> edit.accept(GameData.columns(rows, ability)));
  }

  @Test
  void aCollectorsProductionAndTheElixirDeathsPayAreCountedAsTheyArePaid() {
    GameTables tables = GameData.tables();
    Vocabulary vocabulary = VectorEnv.vocabulary(tables);
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      env.setAutoreset(false);
      env.reset(new int[] {100}, PUMP_0, PUMP_1);
      Gains gains = new Gains();
      battle(env).getWorld().addObserver(gains);
      SplittableRandom random = new SplittableRandom(17);
      // About 250 seconds of battle, short of the tiebreaker: past a collector's whole life. The
      // count is held at every decision; the last observation, after the end, takes none.
      for (int step = 0; step < 1000 && env.dones().get(0) == 0; step++) {
        act(env, pumpAction(env, 0, vocabulary, random), pumpAction(env, 1, vocabulary, random));
        for (int seat = 0; seat < 2 && env.dones().get(0) == 0; seat++) {
          assertThat(counted(env, seat))
              .as("step %d seat %d", step, seat)
              .isEqualTo(truth(env, 1 - seat));
        }
      }
      // The battle paid every kind of gain the count is held to, and refunded nothing.
      assertThat(gains.collected[0] + gains.collected[1]).as("collector payouts").isPositive();
      assertThat(gains.deathMana[0] + gains.deathMana[1]).as("collector deaths").isPositive();
      assertThat(gains.deathReward[0] + gains.deathReward[1]).as("golem kills").isPositive();
      assertThat(gains.refunds).isEmpty();
    }
  }

  @Test
  void aChampionsRefundTheGameDoesNotShowIsNotCounted(@TempDir Path folder) throws IOException {
    // A long cast and a refund window, so the queen killed as she casts is refunded.
    GameTables tables =
        abilityAltered(
            folder,
            QUEEN,
            row -> row.put("RefundWindow", 400).put("TriggerDelay", 50).put("CastTime", 2000));
    Vocabulary vocabulary = VectorEnv.vocabulary(tables);
    try (VectorEnv env = withCardInHand(tables, BASIC, QUEEN_DECK, null, 1, QUEEN)) {
      Gains gains = new Gains();
      battle(env).getWorld().addObserver(gains);
      SplittableRandom random = new SplittableRandom(5);
      CharacterEntity queen = null;
      boolean killed = false;
      for (int step = 0; step < 400 && gains.refunds.isEmpty(); step++) {
        int action = Schema.WAIT;
        int slot = handSlot(env, 1, vocabulary, QUEEN);
        if (queen == null && slot >= 0) {
          action = Math.max(Schema.WAIT, randomTile(env, 1, slot, random));
        } else if (queen != null && battle(env).getAbilityUses().isEmpty()) {
          for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
            if (open(env, 1, Schema.ability(k))) {
              action = Schema.ability(k);
            }
          }
        }
        act(env, Schema.WAIT, action);
        for (Standard1v1Battle.Play play : battle(env).getPlays()) {
          if (play.side() == 1 && !play.units().isEmpty()) {
            queen = play.units().get(0);
          }
        }
        List<Standard1v1Battle.AbilityUse> uses = battle(env).getAbilityUses();
        if (!killed && !uses.isEmpty()) {
          assertThat(uses.get(0).outcome().code()).isEqualTo(AbilityCommand.OK);
          // She dies as she casts, inside the window: her king gets the cost back.
          battle(env).getWorld().kill(queen, null);
          killed = true;
        }
        if (gains.refunds.isEmpty()) {
          assertThat(counted(env, 0)).as("step %d", step).isEqualTo(truth(env, 1));
        }
      }
      assertThat(gains.refunds).as("the queen's refund").hasSize(1);
      int refund = gains.refunds.get(0)[1];
      assertThat(refund).isPositive();
      // The count leaves the refund out: it is the truth less the refund.
      assertThat(counted(env, 0)).isEqualTo(truth(env, 1) - refund);
    }
  }

  @Test
  void anAbilityThatRequestsNoCopyIsChargedAsTheBattleChargesIt(@TempDir Path folder)
      throws IOException {
    // Charges enough for a second use while the hero Wizard flies on its air row, whose copies its
    // slot does not request.
    GameTables tables = abilityAltered(folder, "WizardHero", row -> row.put("MaxCharges", 3));
    Vocabulary vocabulary = VectorEnv.vocabulary(tables);
    int[] heroFirst = new int[Schema.DECK_SIZE];
    heroFirst[0] = MatchSide.HERO_SLOT;
    try (VectorEnv env = withCardInHand(tables, BASIC, HERO_DECK, heroFirst, 1, "Wizard")) {
      SplittableRandom random = new SplittableRandom(9);
      CharacterEntity hero = null;
      String groundRow = null;
      int given = 0;
      for (int step = 0; step < 400 && battle(env).getAbilityUses().size() < 2; step++) {
        int action = Schema.WAIT;
        int slot = handSlot(env, 1, vocabulary, "Wizard");
        if (hero == null && slot >= 0) {
          action = Math.max(Schema.WAIT, randomTile(env, 1, slot, random));
        } else if (hero != null
            && (given == 0 || given == 1 && !hero.getData().name().equals(groundRow))) {
          // The first use once it is open, the second once the hero flies on its air row.
          for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
            if (action == Schema.WAIT && open(env, 1, Schema.ability(k))) {
              action = Schema.ability(k);
              given++;
            }
          }
        }
        act(env, Schema.WAIT, action);
        for (Standard1v1Battle.Play play : battle(env).getPlays()) {
          if (hero == null && play.side() == 1 && !play.units().isEmpty()) {
            hero = play.units().get(0);
            groundRow = hero.getData().name();
          }
        }
        assertThat(counted(env, 0)).as("step %d", step).isEqualTo(truth(env, 1));
      }
      List<Standard1v1Battle.AbilityUse> uses = battle(env).getAbilityUses();
      assertThat(uses).hasSize(2);
      // The second use ran on the air row: it passed and paid, and requested no copy.
      AbilityCommand.Outcome second = uses.get(1).outcome();
      assertThat(second.code()).isEqualTo(AbilityCommand.OK);
      assertThat(second.requested()).isEmpty();
      assertThat(second.elixirAfter()).isLessThan(second.elixirBefore());
      assertThat(counted(env, 0)).isEqualTo(truth(env, 1));
    }
  }
}
