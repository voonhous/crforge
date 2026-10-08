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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A buff's death spawn is a character the spawner queues for the holder like any other, so the
 * closing cleanup's fold that takes it into the live list also starts it: its row's starting action
 * is scheduled then, and runs in its first pending pass of the next tick. A newer data version
 * gives the Witch Mother's curse hog such a start (the jump check of the units that jump the
 * river).
 *
 * <p>The scene is the Goblin Curse's, with its goblin given a starting action that puts a buff on
 * itself: the top side's Skeletons are played in front of its right princess tower and the bottom
 * side's Goblin Curse is cast on them as they deploy; its damage kills each Skeleton as it walks
 * off, and the curse makes a goblin for the bottom side where it stood.
 */
class BattleBuffDeathSpawnStartTest {

  /** The Skeletons' level. */
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

  /** The buff the goblin's starting action puts on it. */
  private static final String BUFF = "Test_Cursed_Goblin_Start_Buff";

  /** The goblin's starting action. */
  private static final String START = "Test_Cursed_Goblin_Start";

  /**
   * The configured tables with the curse goblin given a starting action that buffs it, and the
   * Skeletons' count and hit points, the curse's damage and its one goblin a death written.
   */
  private static GameTables withGoblinStart(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(START);
          row.put("class", "LogicActionSpawnData");
          row.put("ClassType", "ActionSpawn");
          ObjectNode fields = row.putObject("fields");
          fields.put("ClassType", "ActionSpawn");
          fields.put("SpawnData", BUFF);
          fields.put("SpawnTime", 5000);
          fields.put("SpawnType", "BuffType");
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(BUFF);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("IgnorePushBack", true);
          columns.put("Rarity", "Common");
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "GoblinCurseGoblin").put("OnStartingAction", START);
          GameData.columns(rows, "Skeleton").put("Hitpoints", SKELETON_HIT_POINTS);
        });
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "Skeletons").put("SummonNumber", SKELETONS));
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          GameData.columns(rows, "GoblinCurseDamage")
              .put("DamagePerSecond", CURSE_DAMAGE_PER_SECOND)
              .put("HitFrequency", 1000);
          GameData.columns(rows, "GoblinCurse").put("DeathSpawnCount", 1);
        });
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a cursed unit's death spawn runs its row's starting action in its first pending pass, the"
          + " tick after the one it is made in")
  void theDeathSpawnRunsItsStartingAction(@TempDir Path folder) throws IOException {
    GameTables tables = withGoblinStart(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Map<CharacterEntity, Integer> made = new LinkedHashMap<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffDeathSpawn(
                  int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> spawned) {
                for (CharacterEntity goblin : spawned) {
                  made.put(goblin, tick);
                }
              }
            });
    match.play(20, records.card("Skeletons"), LEVEL, 1, 14500, 23000, "Skeletons");
    match.play(24, records.card("GoblinCurse"), CURSE_LEVEL, 0, 14500, 23000, "GoblinCurse");
    Map<CharacterEntity, List<String>> seen = new LinkedHashMap<>();
    while (match.getBattle().getTick() < LAST_TICK) {
      int tick = match.getBattle().getTick();
      match.getBattle().step();
      for (Map.Entry<CharacterEntity, Integer> entry : made.entrySet()) {
        int age = tick - entry.getValue();
        if (age <= 1) {
          seen.computeIfAbsent(entry.getKey(), goblin -> new ArrayList<>())
              .add(entry.getKey().getBuffs().carries(BUFF) ? "buffed" : "bare");
        }
      }
    }

    assertThat(made).as("each Skeleton died cursed and left a goblin").hasSize(SKELETONS);
    for (Map.Entry<CharacterEntity, List<String>> entry : seen.entrySet()) {
      assertThat(entry.getValue())
          .as("%s after the tick it is made in, then after the next", entry.getKey().name())
          .containsExactly("bare", "buffed");
    }
  }
}
