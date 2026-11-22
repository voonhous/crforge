/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.replay.Scenarios;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.env.Fields.Field;
import org.crforge.env.Fields.Tag;
import org.crforge.env.Fields.V4Source;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The field export of Ladder vectors ({@link Fields}), through the vector and its environments:
 * beside the v4 observation, which it leaves bit for bit as it is, its seat and public fields hold
 * the v4 observation's values, its hidden fields the battle's, on seeded battles with random legal
 * actions, forks, faults and resets.
 */
class FieldExportTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  /**
   * What the seat rules care about: a tunnelling unit, a building that hides, a champion with its
   * ability, a health bar shown from the first damage on, spells launched from the king.
   */
  private static final List<String> TRICKS =
      List.of(
          "Miner",
          "Tesla",
          "ArcherQueen",
          "DarkPrince",
          "GoblinBarrel",
          "Fireball",
          "Poison",
          "Zap");

  private static final List<String> PLAIN =
      List.of("Knight", "Archer", "Musketeer", "Giant", "Valkyrie", "Wizard", "Minions", "Arrows");

  private static final int CAP = Fields.ENTITY_CAPACITY;

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  private static VectorEnv vector(int count, boolean fields) {
    return vector(count, 1, fields);
  }

  private static VectorEnv vector(int count, int threads, boolean fields) {
    return VectorEnv.ladder(tables, EnvConfig.DEFAULT, count, threads, fields);
  }

  private static int[] seeds(int count, int first) {
    int[] seeds = new int[count];
    for (int i = 0; i < count; i++) {
      seeds[i] = first + i;
    }
    return seeds;
  }

  /** An environment's block of the export, as last written. */
  private static int[] block(VectorEnv env, int index) {
    int[] block = new int[Fields.INTS];
    env.fields().asIntBuffer().get(index * Fields.INTS, block);
    return block;
  }

  /** A seat's v4 observation of an environment, as last written. */
  private static int[] observation(VectorEnv env, int index, int seat) {
    int[] o = new int[Schema.SEAT_INTS];
    env.observations().asIntBuffer().get((2 * index + seat) * Schema.SEAT_INTS, o);
    return o;
  }

  private static byte[] bytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.capacity()];
    buffer.get(0, bytes);
    return bytes;
  }

  /** A field's integers past its leading axes: the product of its shape after {@code axes}. */
  private static int inner(Field field, int axes) {
    int n = 1;
    for (int k = axes; k < field.shape().size(); k++) {
      n *= field.shape().get(k);
    }
    return n;
  }

  /** A random open action of each seat of each environment, three in ten a play. */
  private static void drawActions(VectorEnv env, SplittableRandom random) {
    ByteBuffer masks = env.masks();
    IntBuffer actions = env.actions().asIntBuffer();
    for (int i = 0; i < env.count(); i++) {
      for (int seat = 0; seat < 2; seat++) {
        int action = Schema.WAIT;
        if (random.nextInt(10) < 3) {
          int at = (2 * i + seat) * Schema.ACTIONS;
          int open = 0;
          for (int a = 1; a < Schema.ACTIONS; a++) {
            open += masks.get(at + a);
          }
          if (open > 0) {
            int pick = random.nextInt(open);
            for (int a = 1; a < Schema.ACTIONS; a++) {
              if (masks.get(at + a) != 0 && pick-- == 0) {
                action = a;
                break;
              }
            }
          }
        }
        actions.put(2 * i + seat, action);
      }
    }
  }

  /** The v4 rows a seat's observation lists: the export rows, in row order, the k-th is row k. */
  private static int[] listedRows(int[] block, int seat, int count) {
    int[] rows = new int[count];
    int k = 0;
    for (int r = 0; r < CAP && k < count; r++) {
      if (block[Fields.ENTITY_VISIBLE.offset() + seat * CAP + r] == 1) {
        rows[k++] = r;
      }
    }
    assertThat(k).as("rows shown for seat %d", seat).isEqualTo(count);
    return rows;
  }

  /**
   * Holds an environment's block to its observations and its battle: the seat and public fields to
   * the v4 values, the visibility to the seat rule, the aliases to the other seat's fields, and the
   * true values to the seat's where the seat is shown them.
   */
  private static void check(VectorEnv env, int index) {
    int[] block = block(env, index);
    int[][] obs = {observation(env, index, 0), observation(env, index, 1)};
    LadderEnv ladder = (LadderEnv) env.env(index);
    assertThat(block[Fields.ENTITY_EXPORT_OVERFLOW.offset()]).as("export overflow").isZero();
    int[][] listed = new int[2][];
    for (int seat = 0; seat < 2; seat++) {
      listed[seat] = listedRows(block, seat, obs[seat][Schema.H_ENTITY_COUNT]);
    }
    // (b) The seat and public fields hold the v4 values; the seat entity columns 0 elsewhere.
    for (Field field : Fields.fields()) {
      V4Source source = Fields.v4Source(field.name());
      if (source == null) {
        continue;
      }
      List<Integer> idx = source.indices();
      if (field.tag() == Tag.PUBLIC) {
        assertThat(block[field.offset()]).as(field.name()).isEqualTo(obs[0][idx.get(0)]);
        assertThat(block[field.offset()]).as(field.name()).isEqualTo(obs[1][idx.get(0)]);
      } else if (!source.entity()) {
        int n = inner(field, 1);
        for (int seat = 0; seat < 2; seat++) {
          for (int e = 0; e < n; e++) {
            assertThat(block[field.offset() + seat * n + e])
                .as("%s seat %d [%d]", field.name(), seat, e)
                .isEqualTo(obs[seat][idx.get(e)]);
          }
        }
      } else {
        int n = inner(field, 2);
        for (int seat = 0; seat < 2; seat++) {
          int[] values = new int[CAP * n];
          for (int k = 0; k < listed[seat].length; k++) {
            int at = Schema.HEADER_INTS + k * Schema.ENTITY_INTS;
            for (int e = 0; e < n; e++) {
              values[listed[seat][k] * n + e] = obs[seat][at + idx.get(e)];
            }
          }
          int[] exported = new int[CAP * n];
          System.arraycopy(block, field.offset() + seat * CAP * n, exported, 0, CAP * n);
          assertThat(exported).as("%s seat %d", field.name(), seat).isEqualTo(values);
        }
      }
    }
    // (c) entity.visible is the seat rule, row by row, over every entity of the battle.
    Map<Integer, BattleEntity> byId = new HashMap<>();
    List<BattleEntity> entities = ladder.battle().getBattle().getHolder().entities();
    entities.forEach(entity -> byId.put(entity.getId(), entity));
    int rows = block[Fields.ENTITY_COUNT_TRUE.offset()];
    assertThat(rows).isEqualTo(entities.size());
    for (int r = 0; r < CAP; r++) {
      int battleId = block[Fields.ENTITY_BATTLE_ID.offset() + r];
      int present = block[Fields.ENTITY_PRESENT.offset() + r];
      assertThat(present).isEqualTo(r < rows ? 1 : 0);
      if (r >= rows) {
        assertThat(battleId).isEqualTo(-1);
        for (int seat = 0; seat < 2; seat++) {
          assertThat(block[Fields.ENTITY_VISIBLE.offset() + seat * CAP + r]).isZero();
        }
        continue;
      }
      BattleEntity entity = byId.get(battleId);
      assertThat(entity).as("row %d's entity", r).isNotNull();
      for (int seat = 0; seat < 2; seat++) {
        boolean sees = !(entity instanceof WorldEntity world) || SeatView.sees(seat, world);
        assertThat(block[Fields.ENTITY_VISIBLE.offset() + seat * CAP + r])
            .as("row %d visible to seat %d", r, seat)
            .isEqualTo(sees ? 1 : 0);
      }
    }
    // (d) An alias at a seat reads the field it names at the other seat: that seat's own values.
    for (Field alias : Fields.fields()) {
      if (alias.aliasOf() == null) {
        continue;
      }
      Field of = Fields.field(alias.aliasOf());
      assertThat(alias.offset()).isEqualTo(of.offset());
      assertThat(alias.shape()).isEqualTo(of.shape());
      List<Integer> idx = Fields.v4Source(of.name()).indices();
      int n = inner(of, 1);
      for (int seat = 0; seat < 2; seat++) {
        for (int e = 0; e < n; e++) {
          // The alias's element at seat s is its target's at seat 1 - s.
          assertThat(block[alias.offset() + (1 - seat) * n + e])
              .as("%s seat %d [%d]", alias.name(), seat, e)
              .isEqualTo(obs[1 - seat][idx.get(e)]);
        }
      }
    }
    for (int seat = 0; seat < 2; seat++) {
      MatchSide enemy = ladder.battle().getMatch().side(1 - seat);
      assertThat(block[Fields.ENEMY_ELIXIR_TRUE.offset() + (1 - seat)])
          .isEqualTo(enemy.getElixir());
    }
    // (e) The true values equal the seat's where the seat is shown them.
    for (int seat = 0; seat < 2; seat++) {
      for (int k = 0; k < listed[seat].length; k++) {
        checkRow(ladder, block, obs[seat], seat, k, listed[seat][k]);
      }
      checkPlays(block, obs[seat], seat);
    }
  }

  private static int seatEntity(int[] block, Field field, int seat, int row) {
    return block[field.offset() + seat * CAP + row];
  }

  private static int hidden(int[] block, Field field, int row) {
    return block[field.offset() + row];
  }

  /** Holds the true values of a v4 row's export row to the row as the seat is shown it. */
  private static void checkRow(LadderEnv ladder, int[] block, int[] o, int seat, int k, int r) {
    int at = Schema.HEADER_INTS + k * Schema.ENTITY_INTS;
    String row = "seat " + seat + " row " + k;
    assertThat(ladder.battleIdOf(seat, o[at + Schema.E_ID]))
        .as(row)
        .isEqualTo(hidden(block, Fields.ENTITY_BATTLE_ID, r));
    assertThat(o[at + Schema.E_KIND]).as(row).isEqualTo(hidden(block, Fields.ENTITY_KIND_TRUE, r));
    int side = hidden(block, Fields.ENTITY_SIDE_TRUE, r);
    assertThat(o[at + Schema.E_SIDE])
        .as(row)
        .isEqualTo(side == seat ? 0 : side == 1 - seat ? 1 : 2);
    assertThat(o[at + Schema.E_X])
        .as(row)
        .isEqualTo(Schema.toSeatX(seat, hidden(block, Fields.ENTITY_X_TRUE, r)));
    assertThat(o[at + Schema.E_Y])
        .as(row)
        .isEqualTo(Schema.toSeatY(seat, hidden(block, Fields.ENTITY_Y_TRUE, r)));
    assertThat(o[at + Schema.E_STATE])
        .as(row)
        .isEqualTo(hidden(block, Fields.ENTITY_STATE_TRUE, r));
    if (o[at + Schema.E_HP] != Schema.UNKNOWN) {
      // A health bar shows.
      assertThat(o[at + Schema.E_HP]).as(row).isEqualTo(hidden(block, Fields.ENTITY_HP_TRUE, r));
      assertThat(o[at + Schema.E_SHIELD])
          .as(row)
          .isEqualTo(hidden(block, Fields.ENTITY_SHIELD_TRUE, r));
    }
    boolean trail =
        o[at + Schema.E_KIND] == Schema.KIND_CHARACTER && o[at + Schema.E_LEVEL] == Schema.UNKNOWN;
    if (trail) {
      assertThat(o[at + Schema.E_ROW]).isEqualTo(Schema.UNKNOWN);
      assertThat(o[at + Schema.E_FLAGS] & ~hidden(block, Fields.ENTITY_FLAGS_TRUE, r))
          .as(row + ": a trail's flags are its unit's")
          .isZero();
      return;
    }
    assertThat(o[at + Schema.E_ROW]).as(row).isEqualTo(hidden(block, Fields.ENTITY_ROW_TRUE, r));
    assertThat(o[at + Schema.E_MAX_HP])
        .as(row)
        .isEqualTo(hidden(block, Fields.ENTITY_MAX_HP_TRUE, r));
    assertThat(o[at + Schema.E_FLAGS])
        .as(row)
        .isEqualTo(hidden(block, Fields.ENTITY_FLAGS_TRUE, r));
    assertThat(o[at + Schema.E_LEVEL])
        .as(row)
        .isEqualTo(hidden(block, Fields.ENTITY_LEVEL_TRUE, r));
    int[][] same = {
      {Schema.E_DEPLOY_MS, Fields.ENTITY_DEPLOY_MS_TRUE.offset()},
      {Schema.E_ATTACK_MS, Fields.ENTITY_ATTACK_MS_TRUE.offset()},
      {Schema.E_LOAD_MS, Fields.ENTITY_LOAD_MS_TRUE.offset()},
      {Schema.E_SPEED_PERCENT, Fields.ENTITY_SPEED_PERCENT_TRUE.offset()},
      {Schema.E_HIT_SPEED_PERCENT, Fields.ENTITY_HIT_SPEED_PERCENT_TRUE.offset()},
      {Schema.E_BUFF_COUNT, Fields.ENTITY_BUFF_COUNT_TRUE.offset()},
      {Schema.E_TIME_LEFT_MS, Fields.ENTITY_TIME_LEFT_MS_TRUE.offset()},
      {Schema.E_RADIUS, Fields.ENTITY_RADIUS_TRUE.offset()},
      {Schema.E_ABILITY_TICKS, Fields.ENTITY_ABILITY_TICKS_TRUE.offset()},
    };
    for (int[] pair : same) {
      assertThat(o[at + pair[0]]).as(row + " field " + pair[0]).isEqualTo(block[pair[1] + r]);
    }
    for (int b = 0; b < Schema.BUFF_SLOTS; b++) {
      int slot = r * Schema.BUFF_SLOTS + b;
      assertThat(o[at + Schema.E_BUFFS + 2 * b])
          .isEqualTo(block[Fields.ENTITY_BUFF_TRUE.offset() + slot]);
      if (o[at + Schema.E_BUFFS + 2 * b] != -1) {
        assertThat(o[at + Schema.E_BUFFS + 2 * b + 1])
            .isEqualTo(block[Fields.ENTITY_BUFF_MS_TRUE.offset() + slot]);
      }
    }
    if (o[at + Schema.E_TARGET] != Schema.NO_TARGET) {
      assertThat(ladder.battleIdOf(seat, o[at + Schema.E_TARGET]))
          .as(row)
          .isEqualTo(hidden(block, Fields.ENTITY_TARGET_TRUE, r));
      assertThat(o[at + Schema.E_TARGET_X])
          .isEqualTo(Schema.toSeatX(seat, hidden(block, Fields.ENTITY_TARGET_X_TRUE, r)));
      assertThat(o[at + Schema.E_TARGET_Y])
          .isEqualTo(Schema.toSeatY(seat, hidden(block, Fields.ENTITY_TARGET_Y_TRUE, r)));
    }
    if (o[at + Schema.E_KIND] == Schema.KIND_PROJECTILE && o[at + Schema.E_AIM_X] != -1) {
      assertThat(o[at + Schema.E_AIM_X])
          .isEqualTo(Schema.toSeatX(seat, hidden(block, Fields.ENTITY_AIM_X_TRUE, r)));
      assertThat(o[at + Schema.E_AIM_Y])
          .isEqualTo(Schema.toSeatY(seat, hidden(block, Fields.ENTITY_AIM_Y_TRUE, r)));
    }
    // The seat field's own copy of the row: the scatter put it there.
    assertThat(seatEntity(block, Fields.ENTITY_ID, seat, r)).isEqualTo(o[at + Schema.E_ID]);
  }

  /** Holds the true enemy plays to the rows the seat is shown. */
  private static void checkPlays(int[] block, int[] o, int seat) {
    int rows = Schema.ENEMY_PLAY_ROWS;
    int plays = Math.min(o[Schema.H_ENEMY_PLAY_COUNT], rows);
    for (int r = 0; r < rows; r++) {
      int at = Schema.H_ENEMY_PLAYS + r * Schema.ENEMY_PLAY_INTS;
      int t = seat * rows + r;
      int card = block[Fields.ENEMY_PLAYS_CARD_TRUE.offset() + t];
      if (r >= plays) {
        // Past the plays: a kind of 0, no play's.
        assertThat(o[at + 6]).isZero();
        assertThat(card).isEqualTo(-1);
        for (Field field :
            List.of(
                Fields.ENEMY_PLAYS_CAST_TRUE,
                Fields.ENEMY_PLAYS_FORM_TRUE,
                Fields.ENEMY_PLAYS_X_TRUE,
                Fields.ENEMY_PLAYS_Y_TRUE)) {
          assertThat(block[field.offset() + t]).as(field.name()).isEqualTo(-1);
        }
        continue;
      }
      assertThat(card).as("play %d of seat %d's enemy", r, seat).isNotEqualTo(-1);
      if ((o[at + 7] & Schema.PLAY_CARD_SHOWN) != 0) {
        assertThat(o[at]).isEqualTo(card);
        assertThat(o[at + 1]).isEqualTo(block[Fields.ENEMY_PLAYS_CAST_TRUE.offset() + t]);
        assertThat(o[at + 2]).isEqualTo(block[Fields.ENEMY_PLAYS_FORM_TRUE.offset() + t]);
      }
      if ((o[at + 7] & Schema.PLAY_POINT_SHOWN) != 0 && o[at + 6] != Schema.PLAY_TUNNEL) {
        assertThat(o[at + 4])
            .isEqualTo(Schema.toSeatX(seat, block[Fields.ENEMY_PLAYS_X_TRUE.offset() + t]));
        assertThat(o[at + 5])
            .isEqualTo(Schema.toSeatY(seat, block[Fields.ENEMY_PLAYS_Y_TRUE.offset() + t]));
      }
    }
  }

  @Test
  void aVectorExportsNoFieldsUnlessAsked() {
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      assertThat(env.fields()).isNull();
    }
    try (VectorEnv env = vector(3, true)) {
      assertThat(env.fields().capacity()).isEqualTo(3 * Fields.INTS * 4);
      assertThat(env.fields().isDirect()).isTrue();
    }
  }

  @Test
  void theExportLeavesTheObservationsMasksAndRecordsBitForBit() {
    int count = 3;
    // The exporting vector steps its environments on threads of their own, each with its own
    // scratch.
    try (VectorEnv off = vector(count, false);
        VectorEnv on = vector(count, count, true)) {
      off.reset(seeds(count, 40), TRICKS, PLAIN);
      on.reset(seeds(count, 40), TRICKS, PLAIN);
      SplittableRandom random = new SplittableRandom(11);
      for (int step = 0; step < 1500; step++) {
        assertThat(bytes(on.observations())).isEqualTo(bytes(off.observations()));
        assertThat(bytes(on.masks())).isEqualTo(bytes(off.masks()));
        assertThat(bytes(on.rewards())).isEqualTo(bytes(off.rewards()));
        assertThat(bytes(on.dones())).isEqualTo(bytes(off.dones()));
        assertThat(bytes(on.infos())).isEqualTo(bytes(off.infos()));
        if (step % 10 == 0) {
          for (int i = 0; i < count; i++) {
            check(on, i);
          }
        }
        drawActions(off, random);
        on.actions().put(0, bytes(off.actions()));
        off.step();
        on.step();
      }
    }
  }

  @Test
  void theFieldsHoldTheObservationsAndTheBattleOverWholeEpisodes() {
    List<List<String>> decks = List.of(TRICKS, PLAIN, BASIC, OTHER);
    for (int pair = 0; pair < 2; pair++) {
      try (VectorEnv env = vector(3, true)) {
        env.reset(seeds(3, 70 + 10 * pair), decks.get(2 * pair), decks.get(2 * pair + 1));
        SplittableRandom random = new SplittableRandom(pair);
        boolean trail = false;
        boolean hiddenBar = false;
        // Past an episode's end: autoreset starts the next, its first export held as any.
        for (int step = 0; step < 3000; step++) {
          for (int i = 0; i < env.count(); i++) {
            check(env, i);
            trail |= pair == 0 && trailShown(env, i);
            hiddenBar |= pair == 0 && hiddenBarShown(env, i);
          }
          drawActions(env, random);
          env.step();
        }
        if (pair == 0) {
          assertThat(trail).as("a tunnelling unit shown as its trail").isTrue();
          assertThat(hiddenBar).as("an enemy unit whose health bar does not show").isTrue();
        }
      }
    }
  }

  /** Whether a seat of an environment is shown an enemy unit as its trail now. */
  private static boolean trailShown(VectorEnv env, int index) {
    LadderEnv ladder = (LadderEnv) env.env(index);
    for (BattleEntity entity : ladder.battle().getBattle().getHolder().entities()) {
      for (int seat = 0; seat < 2; seat++) {
        if (entity instanceof WorldEntity world
            && SeatView.sees(seat, world)
            && SeatView.trail(seat, world)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether a seat of an environment is shown an enemy character with no health bar now. */
  private static boolean hiddenBarShown(VectorEnv env, int index) {
    for (int seat = 0; seat < 2; seat++) {
      int[] o = observation(env, index, seat);
      for (int k = 0; k < o[Schema.H_ENTITY_COUNT]; k++) {
        int at = Schema.HEADER_INTS + k * Schema.ENTITY_INTS;
        if (o[at + Schema.E_KIND] == Schema.KIND_CHARACTER
            && o[at + Schema.E_HP] == Schema.UNKNOWN
            && o[at + Schema.E_LEVEL] != Schema.UNKNOWN) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  void aForkCarriesTheFieldsOfItsSourceAndPlaysOnWithThem() {
    try (VectorEnv env = vector(4, true);
        VectorEnv plain = vector(1, false);
        VectorEnv twin = vector(1, true)) {
      env.reset(seeds(4, 90), TRICKS, PLAIN);
      plain.reset(new int[] {90}, TRICKS, PLAIN);
      twin.reset(new int[] {90}, TRICKS, PLAIN);
      SplittableRandom random = new SplittableRandom(5);
      for (int step = 0; step < 300; step++) {
        drawActions(env, random);
        plain.actions().put(0, bytes(env.actions()), 0, 8);
        twin.actions().put(0, bytes(env.actions()), 0, 8);
        env.step();
        plain.step();
        twin.step();
      }
      assertThat(env.dones().get(0)).isZero();
      int[] source = block(env, 0);
      // The battle as it is, and a seat's fork with its own random draws: both show the source's
      // last export, as they show its observations.
      env.forkFrom(env, 0, -1, new int[] {1});
      env.forkFrom(env, 0, 1, new int[] {2}, null, new long[] {3});
      assertThat(block(env, 1)).isEqualTo(source);
      assertThat(block(env, 2)).isEqualTo(source);
      check(env, 1);
      check(env, 2);
      // A fork from a vector that exports nothing gets its fields where it lands.
      env.forkFrom(plain, 0, -1, new int[] {3});
      assertThat(block(env, 3)).isEqualTo(block(twin, 0));
      // The forks of the battle as it is play the source's battle on, the same fields each step.
      for (int step = 0; step < 200 && env.dones().get(0) == 0; step++) {
        drawActions(env, random);
        IntBuffer actions = env.actions().asIntBuffer();
        for (int target : new int[] {1, 3}) {
          actions.put(2 * target, actions.get(0));
          actions.put(2 * target + 1, actions.get(1));
        }
        env.step();
        assertThat(block(env, 1)).isEqualTo(block(env, 0));
        assertThat(block(env, 3)).isEqualTo(block(env, 0));
        for (int i = 0; i < env.count(); i++) {
          check(env, i);
        }
      }
    }
  }

  @Test
  void anEpisodeStartedOnAReplayExportsItsFieldsFromItsStart() {
    EnvConfig config = EnvConfig.DEFAULT.withArena(Scenarios.arena(tables));
    String replay;
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.reset(new int[] {170}, TRICKS, PLAIN);
      SplittableRandom random = new SplittableRandom(4);
      for (int step = 0; step < 400; step++) {
        drawActions(env, random);
        env.step();
      }
      assertThat(env.dones().get(0)).isZero();
      replay = env.replayJson(0);
    }
    try (VectorEnv env = VectorEnv.ladder(tables, config, 2, 1, true)) {
      env.reset(seeds(2, 180), BASIC, OTHER);
      int tick = 300 * config.decisionTicks();
      env.startFromReplay(1, replay, tick);
      assertThat(env.infos().asIntBuffer().get(Schema.INFO_INTS + Schema.I_TICK)).isEqualTo(tick);
      check(env, 0);
      check(env, 1);
      SplittableRandom random = new SplittableRandom(6);
      for (int step = 0; step < 100; step++) {
        drawActions(env, random);
        env.step();
        check(env, 1);
      }
    }
  }

  @Test
  void aLookAheadLeavesTheFieldsAlone() {
    try (VectorEnv env = vector(1, true)) {
      env.reset(new int[] {120}, BASIC, OTHER);
      SplittableRandom random = new SplittableRandom(9);
      for (int step = 0; step < 200; step++) {
        drawActions(env, random);
        env.step();
      }
      int[] before = block(env, 0);
      env.lookahead(new int[] {0, 0}, new int[] {0, 1}, new int[] {Schema.WAIT, Schema.WAIT}, 40);
      assertThat(block(env, 0)).isEqualTo(before);
      check(env, 0);
    }
  }

  @Test
  void aFaultClearsTheFieldsAndTheResetAfterItWritesThemAgain() {
    try (VectorEnv env = vector(2, true)) {
      env.reset(seeds(2, 130), BASIC, OTHER);
      SplittableRandom random = new SplittableRandom(2);
      for (int step = 0; step < 100; step++) {
        drawActions(env, random);
        env.step();
      }
      assertThat(block(env, 1)).isNotEqualTo(new int[Fields.INTS]);
      env.env(1).fail(new IllegalStateException("a fault for the test"));
      assertThat(block(env, 1)).isEqualTo(new int[Fields.INTS]);
      check(env, 0);
      // The next step resets the failed environment: its first export is held as any.
      drawActions(env, random);
      env.step();
      assertThat(env.infos().asIntBuffer().get(Schema.INFO_INTS + Schema.I_RESET)).isEqualTo(1);
      check(env, 1);
      env.resetEnv(0, 5);
      check(env, 0);
    }
  }

  @Test
  void theUnitsAChampionsAbilityNamesAreAmongTheRows() {
    // The champion slot's unit is named by the seat's id; the export's own ids give it back.
    try (VectorEnv env = vector(1, true)) {
      env.reset(new int[] {150}, TRICKS, PLAIN);
      SplittableRandom random = new SplittableRandom(3);
      boolean named = false;
      for (int step = 0; step < 1500 && !named; step++) {
        drawActions(env, random);
        env.step();
        int[] block = block(env, 0);
        int[] o = observation(env, 0, 0);
        LadderEnv ladder = (LadderEnv) env.env(0);
        for (int slot = 0; slot < Schema.ABILITY_SLOTS; slot++) {
          int unit = o[Schema.H_ABILITY + slot * Schema.ABILITY_INTS + 1];
          if (unit < 0) {
            continue;
          }
          named = true;
          int battleId = ladder.battleIdOf(0, unit);
          boolean found = false;
          for (int r = 0; r < CAP; r++) {
            if (block[Fields.ENTITY_BATTLE_ID.offset() + r] == battleId) {
              found = true;
              assertThat(seatEntity(block, Fields.ENTITY_ID, 0, r)).isEqualTo(unit);
              BattleEntity entity =
                  ladder.battle().getBattle().getHolder().entities().stream()
                      .filter(e -> e.getId() == battleId)
                      .findFirst()
                      .orElseThrow();
              assertThat(entity).isInstanceOf(CharacterEntity.class);
            }
          }
          assertThat(found).isTrue();
        }
      }
      assertThat(named).as("a champion slot named its unit").isTrue();
    }
  }
}
