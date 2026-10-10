package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a buff's damage over time lands: the game's damage entry queues every hit it is handed for
 * the holder's damage drain, the buff pass's hits included, so a unit its buff's damage kills is
 * still alive for the rest of the buff pass and dies at the drain, after the post-hooks. What a
 * later unit's buff makes in that buff pass - a spawner buff's child - is made before what the
 * dying unit's death leaves, and takes its id first.
 *
 * <p>The scene is the Goblin Curse's: the top side's Skeletons are played in front of its right
 * princess tower and the bottom side's Goblin Curse is cast on them as they deploy; its damage
 * kills each Skeleton as it walks off, and the curse makes a goblin for the bottom side where it
 * stood. The bottom side's Knight, played after the Skeletons at its own end of the arena, so
 * visited after them, starts with a buff whose spawner makes a Skeleton in each of its visits.
 */
class BattleBuffDamageDrainTest {

  /** The Skeletons' and the Knight's level. */
  private static final int LEVEL = 1;

  /** The curse's level, whose damage kills a Skeleton of {@link #LEVEL} in two ticks. */
  private static final int CURSE_LEVEL = 6;

  /** The tick by which every Skeleton has died. */
  private static final int LAST_TICK = 200;

  /** The Skeletons the card makes, written into its row. */
  private static final int SKELETONS = 3;

  /** A Skeleton's hit points at the first level, written into its row. */
  private static final int SKELETON_HIT_POINTS = 32;

  /** The curse's damage a second, one hit a second, written into its damage buff's row. */
  private static final int CURSE_DAMAGE_PER_SECOND = 14;

  /** The Knight's buff while not attacking, whose spawner fires in each of its visits. */
  private static final String SPAWNER = "Test_Every_Visit_Spawner";

  /**
   * The Knight's buff while not attacking: how long it waits to come back, longer than the scene.
   */
  private static final int SPAWNER_TIME_MS = 100_000;

  /** The spawner's firings, more than the scene's visits. */
  private static final int SPAWNER_FIRINGS = 1000;

  /** A buff visit's step of the spawner's timer at the plain spawn rate. */
  private static final int VISIT_MS = 50;

  /**
   * The configured tables with the Knight given a spawner buff to start with that makes a Skeleton
   * in each of its visits, and the Skeletons' count and hit points, the curse's damage and its one
   * goblin a death written.
   */
  private static GameTables withKnightSpawner(Path folder) throws IOException {
    GameData.altered(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(SPAWNER);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("SpawnObject", "Skeleton");
          columns.put("SpawnInterval", VISIT_MS);
          columns.put("SpawnNumber", 1);
          columns.put("SpawnPauseTime", VISIT_MS);
          columns.put("SpawnLimit", SPAWNER_FIRINGS);
          columns.put("Rarity", "Common");
          GameData.columns(rows, "GoblinCurseDamage")
              .put("DamagePerSecond", CURSE_DAMAGE_PER_SECOND)
              .put("HitFrequency", 1000);
          GameData.columns(rows, "GoblinCurse").put("DeathSpawnCount", 1);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          // Played units may not carry a starting buff; a buff while not attacking, which the
          // Knight starts with and keeps far from any target, gives it the spawner as it is made.
          GameData.columns(rows, "Knight")
              .put("BuffWhenNotAttacking", SPAWNER)
              .put("BuffWhenNotAttackingTime", SPAWNER_TIME_MS);
          GameData.columns(rows, "Skeleton").put("Hitpoints", SKELETON_HIT_POINTS);
        });
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "Skeletons").put("SummonNumber", SKELETONS));
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a unit its buff's damage kills dies at the drain: a later unit's spawner child of that buff"
          + " pass takes its id before the dying unit's death spawn")
  void theBuffDamageKillsAtTheDrain(@TempDir Path folder) throws IOException {
    GameTables tables = withKnightSpawner(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    // The goblins each tick's curse deaths leave, and the Knight's children of each tick, by id.
    Map<Integer, List<Integer>> goblins = new LinkedHashMap<>();
    Map<Integer, List<Integer>> children = new LinkedHashMap<>();
    CharacterEntity[] knight = new CharacterEntity[1];
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffDeathSpawn(
                  int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> spawned) {
                for (CharacterEntity goblin : spawned) {
                  goblins.computeIfAbsent(tick, t -> new ArrayList<>()).add(goblin.getId());
                }
              }

              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                if (source instanceof CharacterEntity carrier
                    && carrier.getData().name().equals("Knight")) {
                  knight[0] = carrier;
                  children.computeIfAbsent(tick, t -> new ArrayList<>()).add(child.getId());
                }
              }
            });
    match.play(20, records.card("Skeletons"), LEVEL, 1, 14500, 23000, "Skeletons");
    match.play(22, records.card("Knight"), LEVEL, 0, 3500, 9500, "Knight");
    match.play(24, records.card("GoblinCurse"), CURSE_LEVEL, 0, 14500, 23000, "GoblinCurse");
    while (match.getBattle().getTick() < LAST_TICK) {
      match.getBattle().step();
    }

    assertThat(goblins.values().stream().mapToInt(List::size).sum())
        .as("each Skeleton died cursed and left a goblin")
        .isEqualTo(SKELETONS);
    assertThat(knight[0]).as("the Knight's spawner made its Skeletons").isNotNull();
    for (Map.Entry<Integer, List<Integer>> deaths : goblins.entrySet()) {
      int tick = deaths.getKey();
      assertThat(children.get(tick))
          .as("the Knight's spawner fired in the buff pass of tick %d", tick)
          .hasSize(1);
      int child = children.get(tick).get(0);
      assertThat(deaths.getValue())
          .as(
              "on tick %d the Knight's child %d is made in the buff pass, before the goblins the"
                  + " curse's damage leaves at the drain",
              tick, child)
          .allSatisfy(goblin -> assertThat(goblin).isGreaterThan(child));
    }
  }
}
