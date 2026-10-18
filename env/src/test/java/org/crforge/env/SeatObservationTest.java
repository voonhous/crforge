/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
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

  /** The battle id an id of a seat's observation stands for. */
  private static int battleId(VectorEnv env, int seat, int id) {
    return ((LadderEnv) env.env(0)).battleId(seat, id);
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

  /** A seat's written rows, by the battle id of the entity each shows. */
  private static Map<Integer, int[]> byBattleId(VectorEnv env, int seat, Map<Integer, int[]> rows) {
    Map<Integer, int[]> map = new HashMap<>();
    rows.forEach((id, row) -> map.put(battleId(env, seat, id), row));
    return map;
  }

  /** Every id a seat's observation names: its rows', their targets' and its champion units'. */
  private static Set<Integer> idsNamed(VectorEnv env, int seat) {
    Set<Integer> ids = new TreeSet<>();
    for (int[] row : rows(env, seat).values()) {
      ids.add(row[Schema.E_ID]);
      if (row[Schema.E_TARGET] != Schema.NO_TARGET) {
        ids.add(row[Schema.E_TARGET]);
      }
    }
    for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
      int unit = header(env, seat, Schema.H_ABILITY + k * Schema.ABILITY_INTS + 1);
      if (unit != -1) {
        ids.add(unit);
      }
    }
    return ids;
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
      // Per seat, the battle id each of its ids stood for when first named.
      List<Map<Integer, Integer>> firstNamed = List.of(new HashMap<>(), new HashMap<>());
      int buffs = 0;
      int projectiles = 0;
      int areas = 0;
      int targets = 0;
      int targetRows = 0;
      int deploying = 0;
      int moved = 0;
      int numberedApart = 0;
      for (int step = 0; step < 500 && env.dones().get(0) == 0; step++) {
        act(env, randomAction(env, 0, random, 4), randomAction(env, 1, random, 4));
        Map<Integer, BattleEntity> core = byId(battle);
        List<Map<Integer, int[]>> now = List.of(rows(env, 0), rows(env, 1));
        for (int seat = 0; seat < 2; seat++) {
          // An id stands for one entity through the episode, and an entity keeps its id.
          Map<Integer, Integer> first = firstNamed.get(seat);
          for (int id : idsNamed(env, seat)) {
            first.putIfAbsent(id, battleId(env, seat, id));
            assertThat(battleId(env, seat, id))
                .as("seat %d id %d", seat, id)
                .isEqualTo(first.get(id));
          }
          assertThat(new HashSet<>(first.values())).hasSameSizeAs(first.keySet());
          for (int[] row : now.get(seat).values()) {
            BattleEntity entity = core.get(battleId(env, seat, row[Schema.E_ID]));
            assertThat(entity).isNotNull();
            checkRow(env, battle, seat, row, entity);
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
            // A target the seat has a row of is named by that row's id.
            int[] target = now.get(seat).get(row[Schema.E_TARGET]);
            if (row[Schema.E_TARGET] != Schema.NO_TARGET && target != null) {
              assertThat(target[Schema.E_X]).isEqualTo(row[Schema.E_TARGET_X]);
              assertThat(target[Schema.E_Y]).isEqualTo(row[Schema.E_TARGET_Y]);
              targetRows++;
            }
          }
        }
        // Both seats list an entity both see, half a turn apart; one listed for one seat alone
        // is one the other does not see (a unit waiting its turn to deploy). Each seat numbers
        // by its own sight, so the two ids of one entity may differ.
        List<Map<Integer, int[]>> nowByBattle =
            List.of(byBattleId(env, 0, now.get(0)), byBattleId(env, 1, now.get(1)));
        List<Map<Integer, int[]>> beforeByBattle =
            List.of(byBattleId(env, 0, before.get(0)), byBattleId(env, 1, before.get(1)));
        for (int seat = 0; seat < 2; seat++) {
          for (Map.Entry<Integer, int[]> entry : nowByBattle.get(seat).entrySet()) {
            int[] a = entry.getValue();
            int[] b = nowByBattle.get(1 - seat).get(entry.getKey());
            if (b == null) {
              assertThat(SeatView.sees(1 - seat, (WorldEntity) core.get(entry.getKey()))).isFalse();
            } else if (seat == 0) {
              // A move is the same for both seats when both were shown the entity before.
              boolean bothBefore =
                  beforeByBattle.get(0).containsKey(entry.getKey())
                      && beforeByBattle.get(1).containsKey(entry.getKey());
              checkHalfATurn(env, a, b, bothBefore);
              numberedApart += a[Schema.E_ID] != b[Schema.E_ID] ? 1 : 0;
            }
          }
        }
        before = now;
      }
      assertThat(buffs).as("rows with a buff").isPositive();
      assertThat(projectiles).as("projectile rows").isPositive();
      assertThat(areas).as("area effect rows").isPositive();
      assertThat(targets).as("rows with a target").isPositive();
      assertThat(targetRows).as("rows whose target has a row").isPositive();
      assertThat(deploying).as("rows deploying").isPositive();
      assertThat(moved).as("rows that moved").isPositive();
      assertThat(numberedApart).as("rows both seats show under other ids").isPositive();
    }
  }

  @Test
  void theIdRuleIsExportedApartFromTheSchema() throws IOException {
    // The rule moves the ids' values alone, not the layout: a saved export of schema version 3
    // still passes the schema's check, and the metadata tells which rule wrote the ids.
    JsonNode ladder = new ObjectMapper().readTree(VectorEnv.metadataJson(EnvConfig.DEFAULT, null));
    assertThat(ladder.path("id_version").asInt()).isEqualTo(Schema.ID_SEAT_FIRST_SIGHT);
    assertThat(Schema.ID_VERSION).isEqualTo(Schema.ID_SEAT_FIRST_SIGHT);
    assertThat(ladder.path("schema").has("id_version")).isFalse();
    assertThat(ladder.path("config").has("id_version")).isFalse();
    assertThat(VectorEnv.metadataJson(null, null)).doesNotContain("id_version");
  }

  @Test
  void aSeatNumbersItsEntitiesInOrderOfFirstSightLeavingNoGapForAnEnemyItIsNotShown() {
    // Goblins and Archers place units that wait their turn to deploy, hidden from the enemy.
    try (VectorEnv env = ladder(EnvConfig.DEFAULT, OTHER, OTHER)) {
      env.setAutoreset(false);
      Standard1v1Battle battle = battle(env);
      SplittableRandom random = new SplittableRandom(11);
      int skipped = 0;
      for (int episode = 0; episode < 2; episode++) {
        // Per seat: the battle ids it was shown, by its id.
        List<Map<Integer, Integer>> shown = List.of(new HashMap<>(), new HashMap<>());
        for (int step = 0; step < 400 && env.dones().get(0) == 0; step++) {
          for (int seat = 0; seat < 2; seat++) {
            Map<Integer, Integer> known = shown.get(seat);
            // The ids first named now follow on from the ids named before, with no gap.
            List<Integer> fresh = new ArrayList<>();
            for (int id : idsNamed(env, seat)) {
              if (!known.containsKey(id)) {
                fresh.add(id);
              }
            }
            for (int k = 0; k < fresh.size(); k++) {
              assertThat(fresh.get(k))
                  .as("seat %d step %d", seat, step)
                  .isEqualTo(known.size() + k);
            }
            for (int id : fresh) {
              known.put(id, battleId(env, seat, id));
            }
            // Count the entities shown first now that the battle made after an enemy unit the
            // seat is not shown and was never shown, and after one it was shown before.
            for (int id : fresh) {
              int made = known.get(id);
              int kind = made / BattleEntity.IDS_PER_KIND;
              boolean before = false;
              for (int other : known.values()) {
                before |= other / BattleEntity.IDS_PER_KIND == kind && other < made;
              }
              for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
                boolean between =
                    entity instanceof CharacterEntity unit
                        && unit.side() != seat
                        && !SeatView.sees(seat, unit)
                        && !known.containsValue(unit.getId())
                        && unit.getId() / BattleEntity.IDS_PER_KIND == kind
                        && unit.getId() < made;
                skipped += before && between ? 1 : 0;
              }
            }
          }
          act(env, randomAction(env, 0, random, 4), randomAction(env, 1, random, 4));
        }
        env.reset(new int[] {200 + episode}, OTHER, OTHER);
        battle = battle(env);
        // A new episode numbers afresh: its first rows are 0, 1, 2, ... in row order.
        for (int seat = 0; seat < 2; seat++) {
          for (int r = 0; r < header(env, seat, Schema.H_ENTITY_COUNT); r++) {
            assertThat(entity(env, seat, r, Schema.E_ID)).isEqualTo(r);
          }
        }
      }
      assertThat(skipped)
          .as("entities shown after an enemy unit the seat was not shown")
          .isPositive();
    }
  }

  /** One seat's row against the battle core's entity it names. */
  private static void checkRow(
      VectorEnv env, Standard1v1Battle battle, int seat, int[] row, BattleEntity entity) {
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
      checkTarget(env, seat, row, target == null ? null : battle.getWorld().entityOf(target));
      assertThat(row[Schema.E_AIM_X]).isZero();
      assertThat(row[Schema.E_TIME_LEFT_MS]).isZero();
    } else if (entity instanceof ProjectileEntity projectile) {
      assertThat(row[Schema.E_FLAGS]).isEqualTo(Schema.F_ALIVE);
      assertThat(row[Schema.E_STATE]).isEqualTo(Schema.NO_STATE);
      assertThat(row[Schema.E_LEVEL]).isEqualTo(PackedLevel.level(projectile.getPackedLevel()));
      assertThat(row[Schema.E_AIM_X]).isEqualTo(Schema.toSeatX(seat, projectile.getAimX()));
      assertThat(row[Schema.E_AIM_Y]).isEqualTo(Schema.toSeatY(seat, projectile.getAimY()));
      checkTarget(env, seat, row, projectile.getTarget());
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

  private static void checkTarget(VectorEnv env, int seat, int[] row, WorldEntity target) {
    if (target == null || !SeatView.sees(seat, target)) {
      assertThat(row[Schema.E_TARGET]).isEqualTo(Schema.NO_TARGET);
      assertThat(row[Schema.E_TARGET_X]).isZero();
      assertThat(row[Schema.E_TARGET_Y]).isZero();
      return;
    }
    assertThat(battleId(env, seat, row[Schema.E_TARGET])).isEqualTo(target.getId());
    assertThat(row[Schema.E_TARGET_X]).isEqualTo(Schema.toSeatX(seat, target.getView().getX()));
    assertThat(row[Schema.E_TARGET_Y]).isEqualTo(Schema.toSeatY(seat, target.getView().getY()));
  }

  /**
   * Seat 0's and seat 1's rows of one entity: every point half a turn apart, the ids each seat's
   * own, the target the same entity, the rest alike.
   */
  private static void checkHalfATurn(VectorEnv env, int[] a, int[] b, boolean moves) {
    int[][] points = {
      {Schema.E_X, Schema.E_Y},
      {Schema.E_AIM_X, Schema.E_AIM_Y},
      {Schema.E_TARGET_X, Schema.E_TARGET_Y}
    };
    for (int f = 0; f < Schema.ENTITY_INTS; f++) {
      if (f == Schema.E_SIDE) {
        assertThat(b[f]).isEqualTo(a[f] == 2 ? 2 : 1 - a[f]);
      } else if (f == Schema.E_ID) {
        assertThat(battleId(env, 1, b[f])).isEqualTo(battleId(env, 0, a[f]));
      } else if (f == Schema.E_TARGET) {
        assertThat(b[f] == Schema.NO_TARGET ? -1 : battleId(env, 1, b[f]))
            .isEqualTo(a[f] == Schema.NO_TARGET ? -1 : battleId(env, 0, a[f]));
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

  /** Whether a seat field holds an id: a row's or its target's, a champion slot's unit's. */
  private static boolean idField(int field) {
    if (field >= Schema.HEADER_INTS) {
      int f = (field - Schema.HEADER_INTS) % Schema.ENTITY_INTS;
      return f == Schema.E_ID || f == Schema.E_TARGET;
    }
    return field >= Schema.H_ABILITY
        && field < Schema.H_ABILITY + Schema.ABILITY_SLOTS * Schema.ABILITY_INTS
        && (field - Schema.H_ABILITY) % Schema.ABILITY_INTS == 1;
  }

  /** The battle id behind an id field's value of seat 0, -1 for none. */
  private static int entityOf(VectorEnv env, int id) {
    return id == -1 ? -1 : battleId(env, 0, id);
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
        for (int id : idsNamed(a, 0)) {
          assertThat(battleId(a, 0, id)).isNotEqualTo(building.getId());
        }
        byte[] seenA = seatBytes(a, 0);
        byte[] seenB = seatBytes(b, 0);
        IntBuffer intsA = ByteBuffer.wrap(seenA).order(a.observations().order()).asIntBuffer();
        IntBuffer intsB = ByteBuffer.wrap(seenB).order(b.observations().order()).asIntBuffer();
        for (int f = 0; f < Schema.SEAT_INTS; f++) {
          if (idField(f)) {
            // Seat 0 saw the building in a before it hid, so its later ids in a run one past
            // b's: an id field names the same entity in both.
            assertThat(entityOf(b, intsB.get(f)))
                .as("seat 0 field %d", f)
                .isEqualTo(entityOf(a, intsA.get(f)));
          } else if (!enemyPlayField(f)) {
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
