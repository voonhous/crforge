/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.core.battle.unit.KingElixir;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.battle.unit.WorldEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Ladder environment on the configured tables, which the build makes for every test task. The
 * decks name rows of the tables; every expected game value is read from the tables or the battle,
 * or asserted as a relation.
 */
class LadderEnvTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  private static final List<String> MIRROR =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Mirror", "Zap");

  private static final List<String> VARIANT =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "MergeMaiden", "Zap");

  /** A champion, the Archer Queen, and seven cards to cycle her back with. */
  private static final List<String> QUEEN =
      List.of(
          "ArcherQueen",
          "Knight",
          "Archer",
          "Musketeer",
          "Giant",
          "MiniPekka",
          "Fireball",
          "Arrows");

  private static GameTables tables;
  private static Vocabulary vocabulary;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
    vocabulary = VectorEnv.vocabulary(tables);
  }

  private static VectorEnv ladder(
      int count, int threads, EnvConfig config, List<String> deck0, List<String> deck1) {
    VectorEnv env = VectorEnv.ladder(tables, config, count, threads);
    int[] seeds = new int[count];
    for (int i = 0; i < count; i++) {
      seeds[i] = 100 + i;
    }
    env.reset(seeds, deck0, deck1);
    return env;
  }

  private static LadderEnv ladderEnv(VectorEnv env, int index) {
    return (LadderEnv) env.env(index);
  }

  private static int header(VectorEnv env, int index, int seat, int field) {
    return env.observations().asIntBuffer().get((index * 2 + seat) * Schema.SEAT_INTS + field);
  }

  private static int entity(VectorEnv env, int index, int seat, int row, int field) {
    return header(env, index, seat, Schema.HEADER_INTS + row * Schema.ENTITY_INTS + field);
  }

  private static int info(VectorEnv env, int index, int field) {
    return env.infos().asIntBuffer().get(index * Schema.INFO_INTS + field);
  }

  private static boolean open(VectorEnv env, int index, int seat, int action) {
    return env.masks().get((index * 2 + seat) * Schema.ACTIONS + action) == 1;
  }

  /** Whether any tile of a seat's hand slot is open: the slot's card may be played now. */
  private static boolean slotOpen(VectorEnv env, int index, int seat, int slot) {
    for (int t = 0; t < Schema.TILES; t++) {
      if (open(env, index, seat, 1 + slot * Schema.TILES + t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The play of a seat's hand slot on its open tile nearest a tile of the seat's frame: the tile
   * itself when the card lands there unmoved. The slot must be open.
   */
  private static int nearOpen(VectorEnv env, int index, int seat, int slot, int col, int row) {
    int best = -1;
    int bestDistance = Integer.MAX_VALUE;
    for (int r = 0; r < Schema.ROWS; r++) {
      for (int c = 0; c < Schema.COLS; c++) {
        int distance = (c - col) * (c - col) + (r - row) * (r - row);
        if (distance < bestDistance && open(env, index, seat, Schema.play(slot, c, r))) {
          best = Schema.play(slot, c, r);
          bestDistance = distance;
        }
      }
    }
    assertThat(best).as("an open tile of slot %d", slot).isPositive();
    return best;
  }

  private static int openCount(VectorEnv env, int index, int seat) {
    int open = 0;
    for (int a = 0; a < Schema.ACTIONS; a++) {
      open += open(env, index, seat, a) ? 1 : 0;
    }
    return open;
  }

  /** The first hand slot of a seat holding a card, or -1. */
  private static int handSlot(VectorEnv env, int index, int seat, String card) {
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      if (header(env, index, seat, Schema.H_HAND_CARD + k) == vocabulary.id(card)) {
        return k;
      }
    }
    return -1;
  }

  /** The first open hand slot of a seat whose card is not the one named, or -1. */
  private static int otherOpenSlot(VectorEnv env, int index, int seat, String card) {
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      if (header(env, index, seat, Schema.H_HAND_CARD + k) != vocabulary.id(card)
          && slotOpen(env, index, seat, k)) {
        return k;
      }
    }
    return -1;
  }

  /** A random open play of a seat with a probability out of ten, else the wait. */
  private static int randomAction(
      VectorEnv env, int index, int seat, SplittableRandom random, int playsInTen) {
    if (random.nextInt(10) >= playsInTen) {
      return Schema.WAIT;
    }
    List<Integer> open = new ArrayList<>();
    for (int a = 1; a < Schema.ACTIONS; a++) {
      if (open(env, index, seat, a)) {
        open.add(a);
      }
    }
    return open.isEmpty() ? Schema.WAIT : open.get(random.nextInt(open.size()));
  }

  /** One seat's observation and mask, as bytes, for a comparison. */
  private static byte[] seatBytes(VectorEnv env, int index, int seat) {
    ByteBuffer observations = env.observations();
    ByteBuffer masks = env.masks();
    byte[] bytes = new byte[Schema.SEAT_INTS * 4 + Schema.ACTIONS];
    observations.get((index * 2 + seat) * Schema.SEAT_INTS * 4, bytes, 0, Schema.SEAT_INTS * 4);
    masks.get((index * 2 + seat) * Schema.ACTIONS, bytes, Schema.SEAT_INTS * 4, Schema.ACTIONS);
    return bytes;
  }

  private static byte[] all(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.capacity()];
    buffer.get(0, bytes);
    return bytes;
  }

  @Test
  void theFirstObservationShowsTheDeckTheHandTheClockAndTheTowers() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, BASIC, OTHER)) {
      LadderMatch match = ladderEnv(env, 0).battle().getMatch();
      for (int seat = 0; seat < 2; seat++) {
        List<String> deck = seat == 0 ? BASIC : OTHER;
        MatchSide own = match.side(seat);
        for (int k = 0; k < Schema.DECK_SIZE; k++) {
          assertThat(header(env, 0, seat, Schema.H_DECK + k)).isEqualTo(vocabulary.id(deck.get(k)));
          assertThat(header(env, 0, seat, Schema.H_ENEMY_SEEN + k)).isEqualTo(-1);
        }
        List<Integer> hand = new ArrayList<>();
        for (int k = 0; k < Schema.HAND_SLOTS; k++) {
          int card = header(env, 0, seat, Schema.H_HAND_CARD + k);
          assertThat(hand).doesNotContain(card);
          hand.add(card);
          String name = own.deck().get(own.getHand().slots()[k]).name();
          assertThat(card).isEqualTo(vocabulary.id(name));
          assertThat(header(env, 0, seat, Schema.H_HAND_COST + k))
              .isEqualTo(own.deck().get(own.getHand().slots()[k]).cost())
              .isPositive();
          assertThat(header(env, 0, seat, Schema.H_HAND_PENDING + k)).isZero();
        }
        assertThat(header(env, 0, seat, Schema.H_NEXT_CARD)).isNotEqualTo(-1);
        assertThat(header(env, 0, seat, Schema.H_ELIXIR))
            .isEqualTo(own.getElixir())
            .isEqualTo(match.getTimeline().row().startingElixir() * MatchSide.SCALE)
            .isPositive();
        assertThat(header(env, 0, seat, Schema.H_ELIXIR_RESERVED)).isZero();
        assertThat(header(env, 0, seat, Schema.H_RESULT)).isEqualTo(Schema.RUNNING);
        assertThat(header(env, 0, seat, Schema.H_SEAT)).isEqualTo(seat);
        assertThat(header(env, 0, seat, Schema.H_PLAY_DELAY_TICKS))
            .isEqualTo(Standard1v1Battle.PLAY_DELAY_TICKS);
        assertThat(header(env, 0, seat, Schema.H_PLAY_OPEN_IN_TICKS))
            .isEqualTo(match.getStartCooldownTicks())
            .isPositive();
        assertThat(header(env, 0, seat, Schema.H_SECTION_LEFT_MS)).isPositive();
        assertThat(header(env, 0, seat, Schema.H_FULL_BAR_MS)).isPositive();
        assertThat(header(env, 0, seat, Schema.H_OVERTIME)).isZero();
        assertThat(header(env, 0, seat, Schema.H_PENDING_COUNT)).isZero();
        for (int r = 0; r < Schema.PENDING_ROWS; r++) {
          assertThat(header(env, 0, seat, Schema.H_PENDING + r * Schema.PENDING_INTS))
              .isEqualTo(-1);
        }
        for (int t = 0; t < 6; t++) {
          int hp = header(env, 0, seat, Schema.H_TOWERS + 2 * t);
          int max = header(env, 0, seat, Schema.H_TOWERS + 2 * t + 1);
          assertThat(hp).isPositive().isEqualTo(max);
        }
        // Six towers and nothing else, the seat's own at the bottom of its frame.
        assertThat(header(env, 0, seat, Schema.H_ENTITY_COUNT)).isEqualTo(6);
        List<Integer> ownPrincessX = new ArrayList<>();
        for (int row = 0; row < 6; row++) {
          assertThat(entity(env, 0, seat, row, Schema.E_KIND)).isEqualTo(Schema.KIND_TOWER);
          boolean mine = entity(env, 0, seat, row, Schema.E_SIDE) == 0;
          int y = entity(env, 0, seat, row, Schema.E_Y);
          assertThat(y < Schema.HEIGHT_UNITS / 2).as("own towers low in the frame").isEqualTo(mine);
          assertThat(entity(env, 0, seat, row, Schema.E_FLAGS) & Schema.F_ALIVE).isNotZero();
          if (mine && (entity(env, 0, seat, row, Schema.E_FLAGS) & Schema.F_KING) == 0) {
            ownPrincessX.add(entity(env, 0, seat, row, Schema.E_X));
          }
        }
        // The left princess tower is the one on the left of the seat's screen.
        assertThat(ownPrincessX).hasSize(2);
        assertThat(Math.min(ownPrincessX.get(0), ownPrincessX.get(1)))
            .isLessThan(Schema.WIDTH_UNITS / 2);
      }
      assertThat(info(env, 0, Schema.I_WINNER)).isEqualTo(Schema.WINNER_NONE);
      assertThat(env.dones().get(0)).isEqualTo((byte) 0);
    }
  }

  @Test
  void beforeTheStartCooldownTheMaskIsTheWaitAloneAndAPlayIsRefused() {
    EnvConfig config = EnvConfig.DEFAULT.withDecisionTicks(1);
    try (VectorEnv env = ladder(1, 1, config, BASIC, BASIC)) {
      int cooldown = ladderEnv(env, 0).battle().getMatch().getStartCooldownTicks();
      IntBuffer actions = env.actions().asIntBuffer();
      while (header(env, 0, 0, Schema.H_TICK) < cooldown) {
        for (int seat = 0; seat < 2; seat++) {
          assertThat(openCount(env, 0, seat)).isEqualTo(1);
          assertThat(open(env, 0, seat, Schema.WAIT)).isTrue();
          assertThat(header(env, 0, seat, Schema.H_PLAY_OPEN_IN_TICKS))
              .isEqualTo(cooldown - header(env, 0, seat, Schema.H_TICK));
        }
        // A play given anyway is refused as masked and taken as a wait.
        actions.put(0, Schema.play(0, Schema.COLS / 2, 5));
        actions.put(1, Schema.WAIT);
        env.step();
        assertThat(info(env, 0, Schema.I_ILLEGAL_0)).isEqualTo(1);
        assertThat(info(env, 0, Schema.I_ILLEGAL_1)).isZero();
        assertThat(header(env, 0, 0, Schema.H_LAST_REFUSAL)).isEqualTo(Schema.REFUSAL_MASKED);
        assertThat(header(env, 0, 0, Schema.H_PENDING_COUNT)).isZero();
      }
      // From the cooldown on the match takes plays: a card the elixir covers is open.
      assertThat(header(env, 0, 0, Schema.H_TICK)).isEqualTo(cooldown);
      assertThat(header(env, 0, 0, Schema.H_PLAY_OPEN_IN_TICKS)).isZero();
      assertThat(openCount(env, 0, 0)).isGreaterThan(1);
    }
  }

  @Test
  void theMaskIsTheTryOfEachSlotAndAnOpenSlotIsGiven() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, BASIC, OTHER)) {
      LadderEnv ladder = ladderEnv(env, 0);
      SplittableRandom random = new SplittableRandom(3);
      IntBuffer actions = env.actions().asIntBuffer();
      int given = 0;
      int closedSeen = 0;
      for (int decision = 0; decision < 600 && env.dones().get(0) == 0; decision++) {
        int[] chosen = new int[2];
        for (int seat = 0; seat < 2; seat++) {
          for (int k = 0; k < Schema.HAND_SLOTS; k++) {
            boolean tried = ladder.commands().tryCard(seat, k, EnvConfig.DEFAULT.level()).given();
            int from = 1 + k * Schema.TILES;
            int openTiles = 0;
            for (int a = from; a < from + Schema.TILES; a++) {
              openTiles += open(env, 0, seat, a) ? 1 : 0;
            }
            // Some tile of an open slot is open, none of a closed one (which tiles:
            // LandingMaskTest).
            if (tried) {
              assertThat(openTiles).isPositive();
            } else {
              assertThat(openTiles).isZero();
            }
            closedSeen += tried ? 0 : 1;
          }
          chosen[seat] = randomAction(env, 0, seat, random, 3);
          actions.put(seat, chosen[seat]);
        }
        env.step();
        for (int seat = 0; seat < 2; seat++) {
          if (chosen[seat] != Schema.WAIT) {
            // A play of an open slot is given.
            assertThat(info(env, 0, seat == 0 ? Schema.I_ILLEGAL_0 : Schema.I_ILLEGAL_1))
                .as("decision %d seat %d", decision, seat)
                .isZero();
            assertThat(header(env, 0, seat, Schema.H_LAST_REFUSAL)).isZero();
            given++;
          }
          // Towers and characters come before projectiles and area effects.
          boolean other = false;
          for (int row = 0; row < header(env, 0, seat, Schema.H_ENTITY_COUNT); row++) {
            int kind = entity(env, 0, seat, row, Schema.E_KIND);
            boolean world = kind == Schema.KIND_TOWER || kind == Schema.KIND_CHARACTER;
            assertThat(world && other).as("a character after a projectile").isFalse();
            other |= !world;
          }
        }
        assertThat(info(env, 0, Schema.I_FIRST_SIDE)).isEqualTo(decision % 2);
      }
      assertThat(given).isGreaterThan(10);
      assertThat(closedSeen).isPositive();
      assertThat(env.fault(0)).isNull();
    }
  }

  @Test
  void aMirrorDeckAndAVariantDeckPlayAWholeEpisodeWithNoFault() {
    for (List<String> deck : List.of(MIRROR, VARIANT)) {
      try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, deck, deck)) {
        env.setAutoreset(false);
        LadderEnv ladder = ladderEnv(env, 0);
        SplittableRandom random = new SplittableRandom(17);
        IntBuffer actions = env.actions().asIntBuffer();
        int decisions = 0;
        while (env.dones().get(0) == 0) {
          actions.put(0, randomAction(env, 0, 0, random, 5));
          actions.put(1, randomAction(env, 0, 1, random, 5));
          env.step();
          decisions++;
          assertThat(decisions).isLessThan(5000);
        }
        assertThat(env.fault(0)).as("%s", deck).isNull();
        assertThat(env.faultCounts()).isEmpty();
        assertThat(info(env, 0, Schema.I_TRUNCATED)).isZero();
        assertThat(info(env, 0, Schema.I_WINNER)).isNotEqualTo(Schema.WINNER_NONE);
        // The special card was played, and its plays were seen by the enemy as a player sees
        // them: a Mirror's as the Mirror and the card it repeated.
        int special = 0;
        for (Standard1v1Battle.Play play : ladder.battle().getPlays()) {
          boolean placed = play.matchCode() == 0 && play.result() != null && play.result().placed();
          if (!placed || play.mirror() == null && play.variant() == null) {
            continue;
          }
          special++;
          int enemy = 1 - play.side();
          List<Integer> seen = new ArrayList<>();
          for (int k = 0; k < Schema.DECK_SIZE; k++) {
            seen.add(header(env, 0, enemy, Schema.H_ENEMY_SEEN + k));
          }
          if (play.mirror() != null) {
            assertThat(seen)
                .contains(vocabulary.id("Mirror"))
                .contains(vocabulary.id(play.mirror().repeats().name()));
          } else {
            assertThat(seen).contains(vocabulary.id("MergeMaiden"));
          }
        }
        assertThat(special).as("%s", deck).isPositive();
      }
    }
  }

  @Test
  void theElixirReservedHoldsAPlayRefusedAtItsRunForItsPromise() {
    EnvConfig config = EnvConfig.DEFAULT.withDecisionTicks(1);
    try (VectorEnv env = ladder(1, 1, config, BASIC, BASIC)) {
      LadderEnv ladder = ladderEnv(env, 0);
      KingElixir kings = ladder.battle().getWorld().getKingElixir();
      IntBuffer actions = env.actions().asIntBuffer();
      actions.put(0, Schema.WAIT);
      actions.put(1, Schema.WAIT);
      // Wait for the first open slot of seat 0.
      int slot = -1;
      while (slot < 0) {
        env.step();
        for (int k = 0; k < Schema.HAND_SLOTS && slot < 0; k++) {
          slot = slotOpen(env, 0, 0, k) ? k : -1;
        }
      }
      int cost = ladder.commands().tryCard(0, slot, config.level()).cost();
      int givenAt = header(env, 0, 0, Schema.H_TICK);
      actions.put(0, Schema.play(slot, Schema.COLS / 2, 6));
      env.step();
      actions.put(0, Schema.WAIT);
      assertThat(header(env, 0, 0, Schema.H_PENDING_COUNT)).isEqualTo(1);
      assertThat(header(env, 0, 0, Schema.H_ELIXIR_RESERVED)).isEqualTo(cost * MatchSide.SCALE);
      assertThat(header(env, 0, 0, Schema.H_HAND_PENDING + slot)).isEqualTo(1);
      // The pending row: the card, a point in the seat's half, and the ticks to its run.
      assertThat(header(env, 0, 0, Schema.H_PENDING))
          .isEqualTo(header(env, 0, 0, Schema.H_HAND_CARD + slot));
      assertThat(header(env, 0, 0, Schema.H_PENDING + 2)).isLessThan(Schema.HEIGHT_UNITS / 2);
      assertThat(header(env, 0, 0, Schema.H_PENDING + 3))
          .isEqualTo(Math.max(givenAt, 1) + Standard1v1Battle.PLAY_DELAY_TICKS - givenAt - 1);
      // Drain the king's elixir until the play runs, so its run refuses it for the elixir.
      boolean refusedAtRun = false;
      while (header(env, 0, 0, Schema.H_TICK) < givenAt + Standard1v1Battle.PROMISE_TICKS + 4) {
        kings.spend(0, kings.elixir(0));
        env.step();
        int tick = header(env, 0, 0, Schema.H_TICK);
        int reserved = header(env, 0, 0, Schema.H_ELIXIR_RESERVED);
        if (header(env, 0, 0, Schema.H_LAST_RUN_REFUSAL) != 0) {
          assertThat(header(env, 0, 0, Schema.H_LAST_RUN_REFUSAL))
              .isEqualTo(LadderMatch.NOT_ENOUGH_ELIXIR);
          refusedAtRun = true;
        }
        if (tick <= givenAt + Standard1v1Battle.PROMISE_TICKS - 2) {
          assertThat(reserved).as("tick %d", tick).isEqualTo(cost * MatchSide.SCALE);
        } else if (tick > givenAt + Standard1v1Battle.PROMISE_TICKS + 2) {
          assertThat(reserved).as("tick %d", tick).isZero();
        }
        if (refusedAtRun) {
          // The play has run: no longer waiting, though its cost is still held back.
          assertThat(header(env, 0, 0, Schema.H_PENDING_COUNT)).isZero();
          assertThat(header(env, 0, 0, Schema.H_HAND_PENDING + slot)).isZero();
        }
      }
      assertThat(refusedAtRun).isTrue();
      assertThat(env.fault(0)).isNull();
    }
  }

  @Test
  void theSameSeedsAndActionsGiveTheSameBuffersTwiceAndOnOneThreadOrFour() {
    EnvConfig config = EnvConfig.DEFAULT.withMaxTicks(1500);
    try (VectorEnv one = ladder(3, 1, config, MIRROR, VARIANT);
        VectorEnv again = ladder(3, 1, config, MIRROR, VARIANT);
        VectorEnv four = ladder(3, 4, config, MIRROR, VARIANT)) {
      SplittableRandom random = new SplittableRandom(5);
      for (int step = 0; step < 320; step++) {
        for (int index = 0; index < 3; index++) {
          for (int seat = 0; seat < 2; seat++) {
            int action = randomAction(one, index, seat, random, 4);
            for (VectorEnv env : List.of(one, again, four)) {
              env.actions().asIntBuffer().put(2 * index + seat, action);
            }
          }
        }
        for (VectorEnv env : List.of(one, again, four)) {
          env.step();
        }
        for (VectorEnv env : List.of(again, four)) {
          assertThat(all(env.observations()))
              .as("step %d", step)
              .isEqualTo(all(one.observations()));
          assertThat(all(env.masks())).as("step %d", step).isEqualTo(all(one.masks()));
          assertThat(all(env.rewards())).as("step %d", step).isEqualTo(all(one.rewards()));
          assertThat(all(env.dones())).as("step %d", step).isEqualTo(all(one.dones()));
          assertThat(all(env.infos())).as("step %d", step).isEqualTo(all(one.infos()));
        }
      }
      assertThat(one.faultCounts()).isEmpty();
    }
  }

  @Test
  void aSeatsObservationHoldsNothingOfTheEnemysHandOrElixir() {
    try (VectorEnv a = ladder(1, 1, EnvConfig.DEFAULT, BASIC, BASIC);
        VectorEnv b = ladder(1, 1, EnvConfig.DEFAULT, BASIC, OTHER)) {
      KingElixir kingsB = ladderEnv(b, 0).battle().getWorld().getKingElixir();
      SplittableRandom random = new SplittableRandom(23);
      boolean enemyElixirDiffers = false;
      for (int step = 0; step < 300; step++) {
        assertThat(seatBytes(b, 0, 0)).as("step %d", step).isEqualTo(seatBytes(a, 0, 0));
        enemyElixirDiffers |= header(a, 0, 1, Schema.H_ELIXIR) != header(b, 0, 1, Schema.H_ELIXIR);
        int action = randomAction(a, 0, 0, random, 3);
        for (VectorEnv env : List.of(a, b)) {
          env.actions().asIntBuffer().put(0, action);
          env.actions().asIntBuffer().put(1, Schema.WAIT);
        }
        a.step();
        b.step();
        // The enemy of seat 0 in b holds another deck and loses its elixir every decision.
        kingsB.spend(1, kingsB.elixir(1));
      }
      assertThat(enemyElixirDiffers).isTrue();
      assertThat(header(a, 0, 1, Schema.H_DECK + 7))
          .isNotEqualTo(header(b, 0, 1, Schema.H_DECK + 7));
    }
  }

  @Test
  void aPlayWaitsThenRunsAndItsUnitsAppearHalfATurnApartInTheTwoFrames() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, BASIC, OTHER)) {
      IntBuffer actions = env.actions().asIntBuffer();
      actions.put(0, Schema.WAIT);
      actions.put(1, Schema.WAIT);
      // Wait for seat 0's first open troop: a slot whose card is a character card.
      int slot = -1;
      while (slot < 0) {
        env.step();
        for (int k = 0; k < Schema.HAND_SLOTS && slot < 0; k++) {
          String name = vocabulary.names().get(header(env, 0, 0, Schema.H_HAND_CARD + k));
          boolean troop =
              tables.table("spells_characters").rows().stream()
                  .map(GameRow::name)
                  .anyMatch(name::equals);
          slot = troop && slotOpen(env, 0, 0, k) ? k : -1;
        }
      }
      int played = header(env, 0, 0, Schema.H_HAND_CARD + slot);
      actions.put(0, Schema.play(slot, Schema.COLS / 2, 8));
      env.step();
      assertThat(header(env, 0, 0, Schema.H_HAND_PENDING + slot)).isEqualTo(1);
      assertThat(header(env, 0, 0, Schema.H_PENDING_COUNT)).isEqualTo(1);
      // The enemy is shown nothing of a command not yet run.
      assertThat(header(env, 0, 1, Schema.H_PENDING_COUNT)).isZero();
      assertThat(header(env, 0, 1, Schema.H_ENEMY_SEEN)).isEqualTo(-1);
      // The same action again is refused while the play waits.
      env.step();
      assertThat(info(env, 0, Schema.I_ILLEGAL_0)).isEqualTo(1);
      actions.put(0, Schema.WAIT);
      while (header(env, 0, 0, Schema.H_PENDING_COUNT) == 1) {
        env.step();
      }
      int count0 = header(env, 0, 0, Schema.H_ENTITY_COUNT);
      int count1 = header(env, 0, 1, Schema.H_ENTITY_COUNT);
      assertThat(count0).isGreaterThan(6).isEqualTo(count1);
      int unit0 = -1;
      for (int row = 0; row < count0; row++) {
        if (entity(env, 0, 0, row, Schema.E_KIND) == Schema.KIND_CHARACTER
            && entity(env, 0, 0, row, Schema.E_SIDE) == 0) {
          unit0 = row;
        }
      }
      assertThat(unit0).isNotNegative();
      // Each seat names the unit by its own id; both stand for the same unit of the battle.
      int id = ladderEnv(env, 0).battleId(0, entity(env, 0, 0, unit0, Schema.E_ID));
      int unit1 = -1;
      for (int row = 0; row < count1; row++) {
        if (ladderEnv(env, 0).battleId(1, entity(env, 0, 1, row, Schema.E_ID)) == id) {
          unit1 = row;
        }
      }
      assertThat(unit1).isNotNegative();
      assertThat(entity(env, 0, 1, unit1, Schema.E_SIDE)).isEqualTo(1);
      assertThat(entity(env, 0, 1, unit1, Schema.E_X))
          .isEqualTo(Schema.WIDTH_UNITS - entity(env, 0, 0, unit0, Schema.E_X));
      assertThat(entity(env, 0, 1, unit1, Schema.E_Y))
          .isEqualTo(Schema.HEIGHT_UNITS - entity(env, 0, 0, unit0, Schema.E_Y));
      // The other seat has now seen the card; this seat has seen none of the other's.
      assertThat(header(env, 0, 1, Schema.H_ENEMY_SEEN)).isEqualTo(played);
      assertThat(header(env, 0, 0, Schema.H_ENEMY_SEEN)).isEqualTo(-1);
    }
  }

  /**
   * A played card leaves its slot only at the play's run, 20 ticks after its giving, and the next
   * card of the queue takes the slot then, or once the hand's refill cooldown has run out: a second
   * play run while the cooldown of the first one's refill still runs leaves its slot empty that
   * long. No slot offers a card before it joins the hand, so an agent plays the next card no
   * earlier than a player can.
   */
  @Test
  void aPlayedSlotOffersNoCardUntilTheNextOneJoinsTheHand() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT.withDecisionTicks(1), BASIC, OTHER)) {
      IntBuffer actions = env.actions().asIntBuffer();
      actions.put(0, Schema.WAIT);
      actions.put(1, Schema.WAIT);
      // Full elixir pays any two cards of the deck: wait for it and for two open slots.
      int first = -1;
      int second = -1;
      while (first < 0 || second < 0) {
        env.step();
        first = -1;
        second = -1;
        if (header(env, 0, 0, Schema.H_ELIXIR) == 100000) {
          for (int k = 0; k < Schema.HAND_SLOTS; k++) {
            if (slotOpen(env, 0, 0, k)) {
              if (first < 0) {
                first = k;
              } else if (second < 0) {
                second = k;
              }
            }
          }
        }
      }
      // The two plays are given a tick apart, so they run a tick apart.
      actions.put(0, nearOpen(env, 0, 0, first, Schema.COLS / 2, 8));
      env.step();
      actions.put(0, nearOpen(env, 0, 0, second, Schema.COLS / 2, 8));
      env.step();
      actions.put(0, Schema.WAIT);

      int[] shown = new int[Schema.HAND_SLOTS];
      int nextShown = header(env, 0, 0, Schema.H_NEXT_CARD);
      for (int k = 0; k < Schema.HAND_SLOTS; k++) {
        shown[k] = header(env, 0, 0, Schema.H_HAND_CARD + k);
      }
      int emptyTicks = 0;
      int refillMsWhenEmptied = -1;
      boolean[] replaced = new boolean[Schema.HAND_SLOTS];
      for (int tick = 0; tick < 200 && !(replaced[first] && replaced[second]); tick++) {
        env.step();
        for (int k = 0; k < Schema.HAND_SLOTS; k++) {
          int card = header(env, 0, 0, Schema.H_HAND_CARD + k);
          boolean pending = header(env, 0, 0, Schema.H_HAND_PENDING + k) == 1;
          if (pending || card == -1) {
            // A waiting play's slot and an empty slot offer nothing.
            assertThat(slotOpen(env, 0, 0, k)).as("slot %d, tick %d", k, tick).isFalse();
          }
          if (card == -1) {
            // Empty only while the refill cooldown runs.
            assertThat(header(env, 0, 0, Schema.H_HAND_REFILL_MS)).isPositive();
            if (emptyTicks == 0) {
              refillMsWhenEmptied = header(env, 0, 0, Schema.H_HAND_REFILL_MS);
            }
            emptyTicks++;
          } else if (card != shown[k]) {
            // The card that takes a slot is the one the observation named as next.
            assertThat(card).as("slot %d's new card", k).isEqualTo(nextShown);
            replaced[k] = true;
          }
          shown[k] = card;
        }
        nextShown = header(env, 0, 0, Schema.H_NEXT_CARD);
      }
      assertThat(replaced[first] && replaced[second]).as("both slots refilled").isTrue();
      // The second slot stood empty for the rest of the first refill's cooldown, a tick a 50 ms.
      assertThat(emptyTicks).isPositive().isEqualTo(refillMsWhenEmptied / 50);
      assertThat(env.fault(0)).isNull();
    }
  }

  @Test
  void anEpisodeRunsToTheMatchsEndWithAZeroSumRewardAndItsWinner() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, BASIC, OTHER)) {
      env.setAutoreset(false);
      SplittableRandom random = new SplittableRandom(9);
      IntBuffer actions = env.actions().asIntBuffer();
      int steps = 0;
      while (env.dones().get(0) == 0) {
        actions.put(0, randomAction(env, 0, 0, random, 3));
        actions.put(1, randomAction(env, 0, 1, random, 3));
        env.step();
        steps++;
        assertThat(steps).isLessThan(5000);
      }
      assertThat(env.fault(0)).isNull();
      assertThat(info(env, 0, Schema.I_TRUNCATED)).isZero();
      assertThat(info(env, 0, Schema.I_TICK))
          .isGreaterThan(ladderEnv(env, 0).battle().getMatch().getStartCooldownTicks());
      float reward0 = env.rewards().asFloatBuffer().get(0);
      float reward1 = env.rewards().asFloatBuffer().get(1);
      assertThat(reward0 + reward1).isEqualTo(0f);
      int result0 = header(env, 0, 0, Schema.H_RESULT);
      int result1 = header(env, 0, 1, Schema.H_RESULT);
      int winner = info(env, 0, Schema.I_WINNER);
      assertThat(result0).isNotEqualTo(Schema.RUNNING);
      if (result0 == Schema.WON) {
        assertThat(result1).isEqualTo(Schema.LOST);
        assertThat(reward0).isEqualTo(1f);
        assertThat(winner).isEqualTo(0);
      } else if (result0 == Schema.LOST) {
        assertThat(result1).isEqualTo(Schema.WON);
        assertThat(reward0).isEqualTo(-1f);
        assertThat(winner).isEqualTo(1);
      } else {
        assertThat(result1).isEqualTo(Schema.DRAW);
        assertThat(reward0).isEqualTo(0f);
        assertThat(winner).isEqualTo(Schema.WINNER_DRAW);
      }
      // The terminal mask is the wait alone.
      assertThat(openCount(env, 0, 0)).isEqualTo(1);
      assertThat(openCount(env, 0, 1)).isEqualTo(1);
    }
  }

  @Test
  void theTickLimitCutsAnEpisodeShortWithNoResult() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT.withMaxTicks(100), BASIC, BASIC)) {
      env.setAutoreset(false);
      for (int step = 0; step < 20; step++) {
        env.step();
      }
      assertThat(env.dones().get(0)).isEqualTo((byte) 1);
      assertThat(info(env, 0, Schema.I_TRUNCATED)).isEqualTo(1);
      assertThat(info(env, 0, Schema.I_WINNER)).isEqualTo(Schema.WINNER_NONE);
      assertThat(info(env, 0, Schema.I_FAULT)).isZero();
      assertThat(header(env, 0, 0, Schema.H_RESULT)).isEqualTo(Schema.RUNNING);
      assertThat(env.rewards().asFloatBuffer().get(0)).isEqualTo(0f);
    }
  }

  @Test
  void aFullFieldLeavesOutProjectilesBeforeCharacters() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT.withDecisionTicks(1), BASIC, BASIC)) {
      Standard1v1Battle battle = ladderEnv(env, 0).battle();
      UnitData unit = GameData.unit("Skeleton");
      int level = EnvConfig.DEFAULT.level();
      // A few characters of side 0 before side 1's princess towers, until a tower's shot flies.
      int tick = battle.getBattle().getTick() + 1;
      for (int i = 0; i < 6; i++) {
        int x = i < 3 ? 3000 + i * 500 : 14000 + (i - 3) * 500;
        battle.deploy(tick, unit, level, 0, x, Schema.HEIGHT_UNITS - 9000, "lure_" + i);
      }
      int oldShot = -1;
      for (int step = 0; step < 200 && oldShot < 0; step++) {
        env.step();
        for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
          if (entity instanceof ProjectileEntity) {
            oldShot = entity.getId();
          }
        }
      }
      assertThat(oldShot).as("a tower's shot in flight").isPositive();
      // Then more characters than the rows hold, all newer than the shot.
      tick = battle.getBattle().getTick() + 1;
      for (int i = 0; i < Schema.MAX_ENTITIES + 20; i++) {
        int x = 2000 + (i % 15) * 1000;
        int y = 3000 + (i / 15) * 500;
        battle.deploy(tick, unit, level, 0, x, y, "mass_" + i);
      }
      while (header(env, 0, 0, Schema.H_ENTITY_OVERFLOW) == 0
          && battle.getBattle().getTick() <= tick + 1) {
        env.step();
      }
      boolean shotStillFlies = false;
      for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
        shotStillFlies |= entity.getId() == oldShot;
      }
      assertThat(shotStillFlies).as("the older shot still in flight").isTrue();
      assertThat(header(env, 0, 0, Schema.H_ENTITY_OVERFLOW)).isPositive();
      assertThat(header(env, 0, 0, Schema.H_ENTITY_COUNT)).isEqualTo(Schema.MAX_ENTITIES);
      for (int row = 0; row < Schema.MAX_ENTITIES; row++) {
        assertThat(entity(env, 0, 0, row, Schema.E_KIND))
            .isIn(Schema.KIND_TOWER, Schema.KIND_CHARACTER);
      }
    }
  }

  @Test
  void aDeckOfACardTheTablesDoNotHoldIsRefusedBeforeAnyEnvironmentIsTouched() {
    try (VectorEnv env = ladder(2, 1, EnvConfig.DEFAULT, BASIC, BASIC)) {
      env.step();
      byte[] before = all(env.observations());
      List<String> unknown = new ArrayList<>(BASIC);
      unknown.set(3, "NoSuchCardInAnyTable");
      assertThatThrownBy(() -> env.reset(new int[] {1, 2}, BASIC, unknown))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NoSuchCardInAnyTable");
      List<String> twice = new ArrayList<>(BASIC);
      twice.set(3, BASIC.get(0));
      assertThatThrownBy(() -> env.reset(new int[] {1, 2}, twice, BASIC))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("twice");
      assertThat(all(env.observations())).isEqualTo(before);
    }
  }

  @Test
  void theVocabularyIsExportedByNameAndRefusesShiftedIds() throws IOException {
    assertThat(vocabulary.names()).isSorted().doesNotHaveDuplicates();
    for (String table : Vocabulary.DECK_TABLES) {
      for (GameRow row : tables.table(table).rows()) {
        assertThat(vocabulary.isDeckCard(row.name())).isTrue();
        assertThat(vocabulary.names().get(vocabulary.id(row.name()))).isEqualTo(row.name());
      }
    }
    assertThat(vocabulary.dataVersion()).isEqualTo(tables.version());
    assertThat(vocabulary.contentSha()).isEqualTo(tables.contentSha());
    // Its own export passes; one whose ids moved by a name is refused.
    vocabulary.checkSame(vocabulary.toJson());
    ObjectMapper json = new ObjectMapper();
    JsonNode exported = json.readTree(vocabulary.toJson());
    List<String> shifted = new ArrayList<>(vocabulary.names());
    shifted.add(0, "AaaNewRow");
    String moved =
        json.writeValueAsString(
            json.createObjectNode()
                .put("data_version", "other")
                .set("names", json.valueToTree(shifted)));
    assertThatThrownBy(() -> vocabulary.checkSame(moved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remap the ids by name");
    assertThat(exported.get("hash").asText()).isEqualTo(vocabulary.hash());
    // The vector's metadata holds the schema and the vocabulary with its data version.
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      JsonNode metadata = json.readTree(env.metadataJson());
      assertThat(metadata.at("/schema/version").asInt()).isEqualTo(Schema.VERSION);
      assertThat(metadata.at("/schema/header_fields").size()).isEqualTo(Schema.HEADER_INTS);
      assertThat(metadata.at("/vocabulary/data_version").asText()).isEqualTo(tables.version());
      assertThat(metadata.at("/vocabulary/content_sha").asText()).isEqualTo(tables.contentSha());
      assertThat(metadata.at("/config/decision_ticks").asInt())
          .isEqualTo(EnvConfig.DEFAULT.decisionTicks());
      vocabulary.checkSame(env.metadataJson());
    }
  }

  @Test
  void theTablesOfAFolderAreLoadedOnceAndShared() {
    Path folder = GameTables.configuredDirectory().orElseThrow();
    assertThat(VectorEnv.tables(folder)).isSameAs(VectorEnv.tables(folder));
    assertThat(VectorEnv.vocabulary(VectorEnv.tables(folder)))
        .isSameAs(VectorEnv.vocabulary(VectorEnv.tables(folder)));
    assertThat(VectorEnv.vocabulary(tables)).isSameAs(vocabulary);
  }

  @Test
  void aPlayTheCoreDoesNotModelIsRefusedNotAFault() {
    // Counted as illegal with its own code, never a fault: shown on a Mirror deck's episode, whose
    // known not-modelled givings may or may not be reached, so this checks the bookkeeping only.
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, MIRROR, MIRROR)) {
      SplittableRandom random = new SplittableRandom(29);
      IntBuffer actions = env.actions().asIntBuffer();
      for (int step = 0; step < 400 && env.dones().get(0) == 0; step++) {
        actions.put(0, randomAction(env, 0, 0, random, 6));
        actions.put(1, randomAction(env, 0, 1, random, 6));
        env.step();
        int notModelled = info(env, 0, Schema.I_NOT_MODELLED);
        int refusedAsNotModelled = 0;
        for (int seat = 0; seat < 2; seat++) {
          if (header(env, 0, seat, Schema.H_LAST_REFUSAL) == Schema.REFUSAL_NOT_MODELLED) {
            refusedAsNotModelled++;
            assertThat(info(env, 0, seat == 0 ? Schema.I_ILLEGAL_0 : Schema.I_ILLEGAL_1))
                .isEqualTo(1);
          }
        }
        assertThat(notModelled).isEqualTo(refusedAsNotModelled);
        assertThat(info(env, 0, Schema.I_FAULT)).isZero();
      }
    }
  }

  @Test
  void anAbilityActionOpensOnceTheChampionStandsAndClosesAfterItsUse() {
    try (VectorEnv env = ladder(1, 1, EnvConfig.DEFAULT, QUEEN, BASIC)) {
      LadderEnv ladder = ladderEnv(env, 0);
      Standard1v1Battle battle = ladder.battle();
      IntBuffer actions = env.actions().asIntBuffer();
      int queen = vocabulary.id("ArcherQueen");
      boolean played = false;
      boolean openSeen = false;
      int usedSlot = -1;
      boolean closedAfterUse = false;
      boolean unitNamed = false;
      for (int decision = 0; decision < 600 && env.dones().get(0) == 0; decision++) {
        for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
          ChampionController slot = battle.getWorld().kingTower(0).championSlot(k + 1);
          int at = Schema.H_ABILITY + k * Schema.ABILITY_INTS;
          // The header shows the slot as the battle core holds it.
          assertThat(header(env, 0, 0, at))
              .isEqualTo(
                  slot.getChampion() == null ? -1 : vocabulary.id(slot.getChampion().name()));
          assertThat(header(env, 0, 0, at + 2)).isEqualTo(slot.getState());
          assertThat(header(env, 0, 0, at + 3)).isEqualTo(slot.getCooldownMs());
          assertThat(header(env, 0, 0, at + 4)).isEqualTo(slot.getCharges());
          // The slot's unit is named by the id of its row: the seat's own character, the unit
          // the slot's action names.
          int unitId = header(env, 0, 0, at + 1);
          if (unitId != -1) {
            int unitRow = -1;
            for (int row = 0; row < header(env, 0, 0, Schema.H_ENTITY_COUNT); row++) {
              unitRow = entity(env, 0, 0, row, Schema.E_ID) == unitId ? row : unitRow;
            }
            assertThat(unitRow).as("the row of the slot's unit").isNotNegative();
            assertThat(entity(env, 0, 0, unitRow, Schema.E_SIDE)).isZero();
            assertThat(entity(env, 0, 0, unitRow, Schema.E_KIND)).isEqualTo(Schema.KIND_CHARACTER);
            int battleId = ladder.battleId(0, unitId);
            assertThat(
                    battle.getBattle().getHolder().entities().stream()
                        .filter(e -> e.getId() == battleId && e instanceof WorldEntity)
                        .map(e -> ((WorldEntity) e).name())
                        .findFirst())
                .contains(ladder.abilityUnit(0, k));
            unitNamed = true;
          }
          // The action is open exactly when the try of a command naming the slot's unit passes.
          String unit = ladder.abilityUnit(0, k);
          PlayerCommands.Giving tried = unit == null ? null : ladder.commands().tryAbility(0, unit);
          boolean open = open(env, 0, 0, Schema.ability(k));
          assertThat(open)
              .as("decision %d slot %d", decision, k)
              .isEqualTo(tried != null && tried.given());
          if (!played) {
            assertThat(open).as("no champion on the board").isFalse();
          }
          openSeen |= open;
          if (usedSlot == k && !battle.getAbilityUses().isEmpty() && !open) {
            assertThat(tried.refusal())
                .isIn(
                    PlayerCommands.Refusal.COOLDOWN,
                    PlayerCommands.Refusal.NO_CHARGES,
                    PlayerCommands.Refusal.ABILITY_PENDING);
            closedAfterUse = true;
          }
        }
        if (closedAfterUse) {
          break;
        }
        int action = Schema.WAIT;
        int queenSlot = handSlot(env, 0, 0, "ArcherQueen");
        int abilitySlot = -1;
        for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
          if (header(env, 0, 0, Schema.H_ABILITY + k * Schema.ABILITY_INTS) == queen
              && open(env, 0, 0, Schema.ability(k))) {
            abilitySlot = k;
          }
        }
        if (usedSlot < 0 && abilitySlot >= 0) {
          action = Schema.ability(abilitySlot);
          usedSlot = abilitySlot;
        } else if (!played && queenSlot >= 0 && slotOpen(env, 0, 0, queenSlot)) {
          action = nearOpen(env, 0, 0, queenSlot, 4, 6);
          played = true;
        } else if (!played && queenSlot < 0) {
          // Cycle the deck until she is dealt.
          int other = otherOpenSlot(env, 0, 0, "ArcherQueen");
          action = other < 0 ? Schema.WAIT : nearOpen(env, 0, 0, other, 13, 2);
        }
        actions.put(0, action);
        actions.put(1, Schema.WAIT);
        env.step();
        assertThat(info(env, 0, Schema.I_ILLEGAL_0)).as("decision %d", decision).isZero();
        if (Schema.isAbility(action)) {
          int at = Schema.H_ABILITY + usedSlot * Schema.ABILITY_INTS;
          assertThat(header(env, 0, 0, Schema.H_LAST_REFUSAL)).isZero();
          int left = header(env, 0, 0, at + 5);
          assertThat(left).isPositive().isLessThanOrEqualTo(Schema.PLAY_DELAY_TICKS);
        }
      }
      assertThat(played).isTrue();
      assertThat(openSeen).isTrue();
      assertThat(usedSlot).isNotNegative();
      assertThat(battle.getAbilityUses()).isNotEmpty();
      assertThat(battle.getAbilityUses().get(0).outcome().code()).isZero();
      assertThat(closedAfterUse).as("closed after its use").isTrue();
      assertThat(unitNamed).as("a slot's unit named").isTrue();
      assertThat(header(env, 0, 0, Schema.H_LAST_ABILITY_RUN_REFUSAL)).isZero();
      assertThat(env.fault(0)).isNull();
    }
  }

  @Test
  void anEvolutionSlotFlagReachesTheMatchAndItsCountTheHeader() {
    int knight = BASIC.indexOf("Knight");
    int[] slots0 = new int[Schema.DECK_SIZE];
    slots0[knight] = Schema.SLOT_EVOLUTION;
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      env.reset(new int[] {7}, BASIC, OTHER, slots0, null);
      LadderMatch match = ladderEnv(env, 0).battle().getMatch();
      for (int k = 0; k < Schema.DECK_SIZE; k++) {
        assertThat(match.side(0).slotFlags(k)).isEqualTo(slots0[k]);
        assertThat(match.side(1).slotFlags(k)).isZero();
        assertThat(header(env, 0, 0, Schema.H_DECK_SLOTS + k)).isEqualTo(slots0[k]);
        assertThat(header(env, 0, 1, Schema.H_DECK_SLOTS + k)).isZero();
      }
      // Play the Knight whenever it is open, until a play of it reads evolved.
      IntBuffer actions = env.actions().asIntBuffer();
      boolean evolvedSeen = false;
      for (int decision = 0; decision < 1500 && env.dones().get(0) == 0; decision++) {
        MatchSide side = match.side(0);
        assertThat(header(env, 0, 0, Schema.H_DECK_EVOLUTION + knight))
            .isEqualTo(side.evolutionCountUnknown(knight) ? -1 : side.evolutionCount(knight));
        for (int k = 0; k < Schema.HAND_SLOTS; k++) {
          int index = side.getHand().slots()[k];
          int form = index < 0 ? 0 : side.item(index).field();
          assertThat(header(env, 0, 0, Schema.H_HAND_FORM + k)).isEqualTo(form);
        }
        int slot = handSlot(env, 0, 0, "Knight");
        evolvedSeen |=
            slot >= 0 && header(env, 0, 0, Schema.H_HAND_FORM + slot) == Schema.FORM_EVOLVED;
        if (evolvedSeen) {
          break;
        }
        int action = Schema.WAIT;
        if (slot >= 0 && slotOpen(env, 0, 0, slot)) {
          action = nearOpen(env, 0, 0, slot, 13, 2);
        } else if (slot < 0) {
          int other = otherOpenSlot(env, 0, 0, "Knight");
          action = other < 0 ? Schema.WAIT : nearOpen(env, 0, 0, other, 4, 2);
        }
        actions.put(0, action);
        actions.put(1, Schema.WAIT);
        env.step();
      }
      assertThat(evolvedSeen)
          .as("the Knight's form reads evolved once its count is reached")
          .isTrue();
      // A reset with no flags plays every card plain again.
      env.reset(new int[] {7}, BASIC, OTHER);
      LadderMatch plain = ladderEnv(env, 0).battle().getMatch();
      for (int k = 0; k < Schema.DECK_SIZE; k++) {
        assertThat(plain.side(0).slotFlags(k)).isZero();
        assertThat(header(env, 0, 0, Schema.H_DECK_SLOTS + k)).isZero();
      }
    }
  }

  @Test
  void slotFlagsOfTheWrongShapeAreRefusedBeforeAnyEnvironmentIsTouched() {
    try (VectorEnv env = ladder(2, 1, EnvConfig.DEFAULT, BASIC, BASIC)) {
      env.step();
      byte[] before = all(env.observations());
      int[] seeds = {1, 2};
      assertThatThrownBy(() -> env.reset(seeds, BASIC, BASIC, new int[7], null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("slot flags");
      int[] bad = new int[Schema.DECK_SIZE];
      bad[2] = 4;
      assertThatThrownBy(() -> env.reset(seeds, BASIC, BASIC, null, bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("side 1");
      assertThat(all(env.observations())).isEqualTo(before);
    }
  }

  @Test
  void aRefusalCodeNamesTheGivingsRefusal() {
    assertThat(Schema.refusalNames().get(Schema.refusalCode(PlayerCommands.Refusal.CARD_PENDING)))
        .isEqualTo("CARD_PENDING");
  }
}
