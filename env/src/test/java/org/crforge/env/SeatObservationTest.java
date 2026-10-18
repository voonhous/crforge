/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.BuffInstance;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What schema version 3 adds to a seat's observation, on the configured tables: the per-entity
 * detail read off the battle core's objects, the enemy's plays and its elixir as a player counts
 * them, the seat's own queue and levels, and what a seat is not shown of an enemy that plays. The
 * decks name rows of the tables; every expected value is read from the battle or the tables.
 */
class SeatObservationTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  /** A deck with spells that leave an area effect and a buff on the field. */
  private static final List<String> AREAS =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Poison", "Freeze");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  /** A deck with a building that hides while it has nothing to attack. */
  private static final List<String> HIDING =
      List.of("Tesla", "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows");

  private static GameTables tables;
  private static Vocabulary vocabulary;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
    vocabulary = VectorEnv.vocabulary(tables);
  }

  private static VectorEnv ladder(EnvConfig config, List<String> deck0, List<String> deck1) {
    VectorEnv env = VectorEnv.ladder(tables, config, 1, 1);
    env.reset(new int[] {100}, deck0, deck1);
    return env;
  }

  private static Standard1v1Battle battle(VectorEnv env) {
    return ((LadderEnv) env.env(0)).battle();
  }

  private static int header(VectorEnv env, int seat, int field) {
    return env.observations().asIntBuffer().get(seat * Schema.SEAT_INTS + field);
  }

  private static int entity(VectorEnv env, int seat, int row, int field) {
    return header(env, seat, Schema.HEADER_INTS + row * Schema.ENTITY_INTS + field);
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

  /** The open play of a seat's hand slot nearest a tile of its frame, or -1 for none. */
  private static int nearestOpen(VectorEnv env, int seat, int slot, int col, int row) {
    int best = -1;
    int bestDistance = Integer.MAX_VALUE;
    for (int r = 0; r < Schema.ROWS; r++) {
      for (int c = 0; c < Schema.COLS; c++) {
        int distance = (c - col) * (c - col) + (r - row) * (r - row);
        if (distance < bestDistance && open(env, seat, Schema.play(slot, c, r))) {
          best = Schema.play(slot, c, r);
          bestDistance = distance;
        }
      }
    }
    return best;
  }

  /** The battle's entities by id. */
  private static Map<Integer, BattleEntity> byId(Standard1v1Battle battle) {
    Map<Integer, BattleEntity> map = new HashMap<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      map.put(entity.getId(), entity);
    }
    return map;
  }

  /** A seat's written rows, by entity id, each its whole row. */
  private static Map<Integer, int[]> rows(VectorEnv env, int seat) {
    Map<Integer, int[]> rows = new HashMap<>();
    for (int r = 0; r < header(env, seat, Schema.H_ENTITY_COUNT); r++) {
      int[] row = new int[Schema.ENTITY_INTS];
      for (int f = 0; f < Schema.ENTITY_INTS; f++) {
        row[f] = entity(env, seat, r, f);
      }
      rows.put(row[Schema.E_ID], row);
    }
    return rows;
  }

  @Test
  void theBuffListNamesEveryBuffTheCoreCanListApartFromTheRowVocabulary() {
    List<String> buffs = vocabulary.buffNames();
    assertThat(buffs).isSorted().doesNotHaveDuplicates();
    // A buff row of the buff table, and one an action's buff spawn writes inline (no table row).
    for (GameRow row : tables.table(Vocabulary.BUFF_TABLE).rows()) {
      assertThat(buffs.get(vocabulary.buffId(row.name()))).isEqualTo(row.name());
    }
    assertThat(tables.inlineBuffNames()).isNotEmpty();
    for (String inline : tables.inlineBuffNames()) {
      assertThat(buffs.get(vocabulary.buffId(inline))).isEqualTo(inline);
    }
    // The row vocabulary is not moved by the buffs.
    assertThat(vocabulary.names()).doesNotContainAnyElementsOf(tables.inlineBuffNames());
  }

  @Test
  void theFirstObservationListsTheQueueTheLevelsAndNoEnemyPlay() {
    try (VectorEnv env = ladder(EnvConfig.DEFAULT, BASIC, OTHER)) {
      Standard1v1Battle battle = battle(env);
      for (int seat = 0; seat < 2; seat++) {
        MatchSide own = battle.getMatch().side(seat);
        Hand hand = own.getHand();
        List<Integer> queue = hand.queue();
        for (int k = 0; k < Schema.QUEUE_SLOTS; k++) {
          int expected = k < queue.size() ? vocabulary.id(own.deck().get(queue.get(k)).name()) : -1;
          assertThat(header(env, seat, Schema.H_QUEUE + k)).isEqualTo(expected);
        }
        assertThat(header(env, seat, Schema.H_QUEUE))
            .isEqualTo(header(env, seat, Schema.H_NEXT_CARD));
        for (int k = 0; k < Schema.DECK_SIZE; k++) {
          assertThat(header(env, seat, Schema.H_DECK_LEVEL + k))
              .isEqualTo(EnvConfig.DEFAULT.level());
        }
        assertThat(header(env, seat, Schema.H_ENEMY_PLAY_COUNT)).isZero();
        for (int r = 0; r < Schema.ENEMY_PLAY_ROWS; r++) {
          int at = Schema.H_ENEMY_PLAYS + r * Schema.ENEMY_PLAY_INTS;
          assertThat(header(env, seat, at)).isEqualTo(-1);
          assertThat(header(env, seat, at + 1)).isEqualTo(-1);
        }
        // Both sides start alike, so the count of the enemy's elixir starts at the seat's own.
        assertThat(header(env, seat, Schema.H_ENEMY_ELIXIR))
            .isEqualTo(header(env, seat, Schema.H_ELIXIR))
            .isPositive();
        for (int r = 0; r < header(env, seat, Schema.H_ENTITY_COUNT); r++) {
          assertThat(entity(env, seat, r, Schema.E_LEVEL)).isPositive();
          assertThat(entity(env, seat, r, Schema.E_STATE)).isNotNegative();
        }
      }
      // Once the battle steps, a king sleeps and the princess towers stand awake.
      act(env, Schema.WAIT, Schema.WAIT);
      for (int seat = 0; seat < 2; seat++) {
        for (int r = 0; r < header(env, seat, Schema.H_ENTITY_COUNT); r++) {
          int flags = entity(env, seat, r, Schema.E_FLAGS);
          boolean king = (flags & Schema.F_KING) != 0;
          assertThat((flags & Schema.F_AWAKE) != 0).isEqualTo(!king);
        }
      }
    }
  }

  @Test
  void theEnemysPlaysAreListedNewestFirstAndItsElixirIsCountedFromThemAlone() {
    try (VectorEnv env = ladder(EnvConfig.DEFAULT, BASIC, OTHER)) {
      env.setAutoreset(false);
      Standard1v1Battle battle = battle(env);
      SplittableRandom random = new SplittableRandom(31);
      int listed = 0;
      for (int step = 0; step < 600 && env.dones().get(0) == 0; step++) {
        act(env, randomAction(env, 0, random, 3), randomAction(env, 1, random, 3));
        int tick = battle.getBattle().getTick();
        for (int seat = 0; seat < 2; seat++) {
          int enemy = 1 - seat;
          MatchSide side = battle.getMatch().side(enemy);
          // These decks hold no card whose play adds elixir: the count is the truth.
          assertThat(header(env, seat, Schema.H_ENEMY_ELIXIR))
              .as("step %d seat %d", step, seat)
              .isEqualTo(side.getElixir());
          List<Standard1v1Battle.Play> placed = new ArrayList<>();
          for (Standard1v1Battle.Play play : battle.getPlays()) {
            if (play.side() == enemy && play.result() != null && play.result().placed()) {
              placed.add(0, play);
            }
          }
          assertThat(header(env, seat, Schema.H_ENEMY_PLAY_COUNT)).isEqualTo(placed.size());
          for (int r = 0; r < Schema.ENEMY_PLAY_ROWS && r < placed.size(); r++) {
            Standard1v1Battle.Play play = placed.get(r);
            EvolutionItem item = play.evolution();
            int at = Schema.H_ENEMY_PLAYS + r * Schema.ENEMY_PLAY_INTS;
            assertThat(header(env, seat, at))
                .isEqualTo(vocabulary.id(side.deck().get(item.index()).name()));
            assertThat(header(env, seat, at + 1)).isEqualTo(vocabulary.id(item.spell().name()));
            assertThat(header(env, seat, at + 2)).isEqualTo(item.field());
            assertThat(header(env, seat, at + 3)).isEqualTo(tick - play.tick());
            assertThat(header(env, seat, at + 4))
                .isEqualTo(Schema.toSeatX(seat, play.result().x()));
            assertThat(header(env, seat, at + 5))
                .isEqualTo(Schema.toSeatY(seat, play.result().y()));
            listed++;
          }
        }
      }
      assertThat(listed).as("enemy plays listed").isPositive();
    }
  }

  @Test
  void everyRowReadsTheCoreAndTheTwoSeatsSeeItHalfATurnApart() {
    try (VectorEnv env = ladder(EnvConfig.DEFAULT, AREAS, OTHER)) {
      env.setAutoreset(false);
      Standard1v1Battle battle = battle(env);
      SplittableRandom random = new SplittableRandom(7);
      List<Map<Integer, int[]>> before = List.of(rows(env, 0), rows(env, 1));
      int buffs = 0;
      int projectiles = 0;
      int areas = 0;
      int targets = 0;
      int deploying = 0;
      int moved = 0;
      for (int step = 0; step < 500 && env.dones().get(0) == 0; step++) {
        act(env, randomAction(env, 0, random, 4), randomAction(env, 1, random, 4));
        Map<Integer, BattleEntity> core = byId(battle);
        List<Map<Integer, int[]>> now = List.of(rows(env, 0), rows(env, 1));
        for (int seat = 0; seat < 2; seat++) {
          for (int[] row : now.get(seat).values()) {
            BattleEntity entity = core.get(row[Schema.E_ID]);
            assertThat(entity).isNotNull();
            checkRow(battle, seat, row, entity);
            int[] last = before.get(seat).get(row[Schema.E_ID]);
            assertThat(row[Schema.E_DX])
                .isEqualTo(last == null ? 0 : row[Schema.E_X] - last[Schema.E_X]);
            assertThat(row[Schema.E_DY])
                .isEqualTo(last == null ? 0 : row[Schema.E_Y] - last[Schema.E_Y]);
            moved += row[Schema.E_DX] != 0 || row[Schema.E_DY] != 0 ? 1 : 0;
            buffs += row[Schema.E_BUFF_COUNT] > 0 ? 1 : 0;
            projectiles += row[Schema.E_KIND] == Schema.KIND_PROJECTILE ? 1 : 0;
            areas += row[Schema.E_KIND] == Schema.KIND_AREA ? 1 : 0;
            targets += row[Schema.E_TARGET] != Schema.NO_TARGET ? 1 : 0;
            deploying += row[Schema.E_DEPLOY_MS] > 0 ? 1 : 0;
          }
        }
        // Both seats list an entity both see, half a turn apart; one listed for one seat alone
        // is one the other does not see (a unit waiting its turn to deploy).
        for (int seat = 0; seat < 2; seat++) {
          for (int[] a : now.get(seat).values()) {
            int[] b = now.get(1 - seat).get(a[Schema.E_ID]);
            if (b == null) {
              assertThat(SeatView.sees(1 - seat, (WorldEntity) core.get(a[Schema.E_ID]))).isFalse();
            } else if (seat == 0) {
              // A move is the same for both seats when both were shown the entity before.
              boolean bothBefore =
                  before.get(0).containsKey(a[Schema.E_ID])
                      && before.get(1).containsKey(a[Schema.E_ID]);
              checkHalfATurn(a, b, bothBefore);
            }
          }
        }
        before = now;
      }
      assertThat(buffs).as("rows with a buff").isPositive();
      assertThat(projectiles).as("projectile rows").isPositive();
      assertThat(areas).as("area effect rows").isPositive();
      assertThat(targets).as("rows with a target").isPositive();
      assertThat(deploying).as("rows deploying").isPositive();
      assertThat(moved).as("rows that moved").isPositive();
    }
  }

  /** One seat's row against the battle core's entity it names. */
  private static void checkRow(Standard1v1Battle battle, int seat, int[] row, BattleEntity entity) {
    assertThat(row[Schema.E_X]).isBetween(-Schema.WIDTH_UNITS, 2 * Schema.WIDTH_UNITS);
    if (entity instanceof WorldEntity world) {
      int flags = row[Schema.E_FLAGS];
      assertThat(row[Schema.E_STATE]).isEqualTo(world.getView().getState());
      assertThat(row[Schema.E_LEVEL]).isEqualTo(world.level());
      assertThat((flags & Schema.F_ALIVE) != 0).isEqualTo(world.getView().isAlive());
      assertThat((flags & Schema.F_AWAKE) != 0)
          .isEqualTo(world instanceof TowerEntity tower && !tower.isInactive());
      long tags = world.getView().getFlags();
      assertThat((flags & Schema.F_NO_MOVE) != 0)
          .isEqualTo((tags & world.getView().getFlagBits().noMove()) != 0);
      assertThat((flags & Schema.F_NO_ATTACK) != 0)
          .isEqualTo((tags & world.getView().getFlagBits().noAttack()) != 0);
      assertThat((flags & Schema.F_CLONE) != 0)
          .isEqualTo(world instanceof CharacterEntity c && c.isClone());
      assertThat(row[Schema.E_DEPLOY_MS])
          .isEqualTo(world instanceof CharacterEntity ? world.getView().getDeployCountdown() : 0);
      assertThat(row[Schema.E_ATTACK_MS]).isEqualTo(world.getTargeting().getAttackTimerMs());
      assertThat(row[Schema.E_LOAD_MS]).isEqualTo(world.getTargeting().getLoadTimerMs());
      assertThat(row[Schema.E_SPEED_PERCENT]).isEqualTo(world.getBuffs().speed(100));
      assertThat(row[Schema.E_HIT_SPEED_PERCENT]).isEqualTo(world.getBuffs().hitSpeed(100));
      List<BuffInstance> items = world.getBuffs().items();
      assertThat(row[Schema.E_BUFF_COUNT]).isEqualTo(items.size());
      for (int b = 0; b < Schema.BUFF_SLOTS; b++) {
        int at = Schema.E_BUFFS + 2 * b;
        if (b < items.size()) {
          assertThat(row[at]).isEqualTo(vocabulary.buffId(items.get(b).getBuff().name()));
          assertThat(row[at + 1]).isEqualTo(items.get(b).getRemaining());
        } else {
          assertThat(row[at]).isEqualTo(-1);
          assertThat(row[at + 1]).isZero();
        }
      }
      TargetView reference = world.getTargeting().getReference();
      GridEntity target = reference == null ? null : reference.getEntity();
      checkTarget(battle, seat, row, target == null ? null : battle.getWorld().entityOf(target));
      assertThat(row[Schema.E_AIM_X]).isZero();
      assertThat(row[Schema.E_TIME_LEFT_MS]).isZero();
    } else if (entity instanceof ProjectileEntity projectile) {
      assertThat(row[Schema.E_FLAGS]).isEqualTo(Schema.F_ALIVE);
      assertThat(row[Schema.E_STATE]).isEqualTo(Schema.NO_STATE);
      assertThat(row[Schema.E_LEVEL]).isEqualTo(PackedLevel.level(projectile.getPackedLevel()));
      assertThat(row[Schema.E_AIM_X]).isEqualTo(Schema.toSeatX(seat, projectile.getAimX()));
      assertThat(row[Schema.E_AIM_Y]).isEqualTo(Schema.toSeatY(seat, projectile.getAimY()));
      checkTarget(battle, seat, row, projectile.getTarget());
      assertThat(row[Schema.E_BUFFS]).isEqualTo(-1);
    } else if (entity instanceof AreaEffectEntity area) {
      assertThat(row[Schema.E_FLAGS] & Schema.F_ALIVE).isNotZero();
      assertThat(row[Schema.E_STATE]).isEqualTo(Schema.NO_STATE);
      assertThat(row[Schema.E_LEVEL]).isEqualTo(PackedLevel.level(area.getPackedLevel()));
      assertThat(row[Schema.E_TIME_LEFT_MS]).isEqualTo(area.getCountdown());
      assertThat(row[Schema.E_RADIUS]).isEqualTo(area.radius()).isPositive();
      assertThat(row[Schema.E_TARGET]).isEqualTo(Schema.NO_TARGET);
    }
  }

  private static void checkTarget(
      Standard1v1Battle battle, int seat, int[] row, WorldEntity target) {
    if (target == null || !SeatView.sees(seat, target)) {
      assertThat(row[Schema.E_TARGET]).isEqualTo(Schema.NO_TARGET);
      assertThat(row[Schema.E_TARGET_X]).isZero();
      assertThat(row[Schema.E_TARGET_Y]).isZero();
      return;
    }
    assertThat(row[Schema.E_TARGET]).isEqualTo(target.getId());
    assertThat(row[Schema.E_TARGET_X]).isEqualTo(Schema.toSeatX(seat, target.getView().getX()));
    assertThat(row[Schema.E_TARGET_Y]).isEqualTo(Schema.toSeatY(seat, target.getView().getY()));
  }

  /** Seat 0's and seat 1's rows of one entity: every point half a turn apart, the rest alike. */
  private static void checkHalfATurn(int[] a, int[] b, boolean moves) {
    int[][] points = {
      {Schema.E_X, Schema.E_Y},
      {Schema.E_AIM_X, Schema.E_AIM_Y},
      {Schema.E_TARGET_X, Schema.E_TARGET_Y}
    };
    for (int f = 0; f < Schema.ENTITY_INTS; f++) {
      if (f == Schema.E_SIDE) {
        assertThat(b[f]).isEqualTo(a[f] == 2 ? 2 : 1 - a[f]);
      } else if (f == Schema.E_DX || f == Schema.E_DY) {
        if (moves) {
          assertThat(b[f]).isEqualTo(-a[f]);
        }
      } else if (!isPoint(points, f)) {
        assertThat(b[f]).as("field %s", Schema.entityFields().get(f)).isEqualTo(a[f]);
      }
    }
    for (int[] point : points) {
      boolean none = point[0] == Schema.E_TARGET_X && a[Schema.E_TARGET] == Schema.NO_TARGET;
      boolean unused = point[0] == Schema.E_AIM_X && a[Schema.E_KIND] != Schema.KIND_PROJECTILE;
      if (none || unused) {
        assertThat(b[point[0]]).isZero();
        assertThat(b[point[1]]).isZero();
      } else {
        assertThat(b[point[0]]).isEqualTo(Schema.WIDTH_UNITS - a[point[0]]);
        assertThat(b[point[1]]).isEqualTo(Schema.HEIGHT_UNITS - a[point[1]]);
      }
    }
  }

  private static boolean isPoint(int[][] points, int field) {
    for (int[] point : points) {
      if (point[0] == field || point[1] == field) {
        return true;
      }
    }
    return false;
  }

  /** One seat's observation and mask, as bytes. */
  private static byte[] seatBytes(VectorEnv env, int seat) {
    ByteBuffer observations = env.observations();
    byte[] bytes = new byte[Schema.SEAT_INTS * 4 + Schema.ACTIONS];
    observations.get(seat * Schema.SEAT_INTS * 4, bytes, 0, Schema.SEAT_INTS * 4);
    env.masks().get(seat * Schema.ACTIONS, bytes, Schema.SEAT_INTS * 4, Schema.ACTIONS);
    return bytes;
  }

  /** Whether a header field is one a placed enemy play may move: the cards seen and counted. */
  private static boolean enemyPlayField(int field) {
    return field >= Schema.H_ENEMY_SEEN && field < Schema.H_ENEMY_SEEN + Schema.DECK_SIZE
        || field >= Schema.H_ENEMY_PLAY_COUNT && field < Schema.H_QUEUE;
  }

  @Test
  void anEnemysHiddenBuildingShowsItsCardButNeitherItsCommandNorItsRowNorItsTiles() {
    // Seat 1 cycles its other cards alike in a and b until the building is open, then plays it
    // in a and waits in b; seat 0 waits in both.
    try (VectorEnv a = ladder(EnvConfig.DEFAULT, BASIC, HIDING);
        VectorEnv b = ladder(EnvConfig.DEFAULT, BASIC, HIDING)) {
      int card = vocabulary.id(HIDING.get(0));
      int action = -1;
      for (int step = 0; step < 400 && action < 0; step++) {
        int cycle = Schema.WAIT;
        for (int k = 0; k < Schema.HAND_SLOTS && action < 0; k++) {
          int open = nearestOpen(a, 1, k, Schema.COLS / 2, 3);
          if (header(a, 1, Schema.H_HAND_CARD + k) == card) {
            action = open;
          } else if (open > 0 && cycle == Schema.WAIT) {
            cycle = open;
          }
        }
        if (action < 0) {
          act(a, Schema.WAIT, cycle);
          act(b, Schema.WAIT, cycle);
        }
      }
      assertThat(action).as("the building in seat 1's hand, open").isPositive();
      assertThat(seatBytes(a, 0)).isEqualTo(seatBytes(b, 0));
      act(a, Schema.WAIT, action);
      act(b, Schema.WAIT, Schema.WAIT);
      // The command waits: seat 0 is shown nothing of it.
      while (header(a, 1, Schema.H_PENDING_COUNT) > 0) {
        assertThat(seatBytes(a, 0)).isEqualTo(seatBytes(b, 0));
        act(a, Schema.WAIT, Schema.WAIT);
        act(b, Schema.WAIT, Schema.WAIT);
      }
      // It ran: seat 0 sees the card played, where and when, and counts its cost.
      List<Integer> seen = new ArrayList<>();
      for (int k = 0; k < Schema.DECK_SIZE; k++) {
        seen.add(header(a, 0, Schema.H_ENEMY_SEEN + k));
      }
      assertThat(seen).contains(card);
      assertThat(header(a, 0, Schema.H_ENEMY_PLAY_COUNT))
          .isEqualTo(header(b, 0, Schema.H_ENEMY_PLAY_COUNT) + 1);
      assertThat(header(a, 0, Schema.H_ENEMY_PLAYS)).isEqualTo(card);
      assertThat(header(a, 0, Schema.H_ENEMY_ELIXIR))
          .isLessThan(header(b, 0, Schema.H_ENEMY_ELIXIR));
      Standard1v1Battle battle = battle(a);
      Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
      assertThat(play.units()).isNotEmpty();
      CharacterEntity building = play.units().get(0);
      int hiddenDecisions = 0;
      for (int step = 0; step < 200 && hiddenDecisions < 20; step++) {
        act(a, Schema.WAIT, Schema.WAIT);
        act(b, Schema.WAIT, Schema.WAIT);
        if (!building.hidden()) {
          continue;
        }
        hiddenDecisions++;
        // Hidden: no row, no target, no closed tile; the header differs from b's only by the
        // play seat 0 saw run.
        Map<Integer, int[]> rows = rows(a, 0);
        assertThat(rows).doesNotContainKey(building.getId());
        for (int[] row : rows.values()) {
          assertThat(row[Schema.E_TARGET]).isNotEqualTo(building.getId());
        }
        byte[] seenA = seatBytes(a, 0);
        byte[] seenB = seatBytes(b, 0);
        IntBuffer intsA = ByteBuffer.wrap(seenA).order(a.observations().order()).asIntBuffer();
        IntBuffer intsB = ByteBuffer.wrap(seenB).order(b.observations().order()).asIntBuffer();
        for (int f = 0; f < Schema.SEAT_INTS; f++) {
          if (!enemyPlayField(f)) {
            assertThat(intsA.get(f)).as("seat 0 field %d", f).isEqualTo(intsB.get(f));
          }
        }
        for (int m = 0; m < Schema.ACTIONS; m++) {
          assertThat(seenA[Schema.SEAT_INTS * 4 + m])
              .as("action %d", m)
              .isEqualTo(seenB[Schema.SEAT_INTS * 4 + m]);
        }
      }
      assertThat(hiddenDecisions).as("decisions with the building hidden").isEqualTo(20);
    }
  }
}
