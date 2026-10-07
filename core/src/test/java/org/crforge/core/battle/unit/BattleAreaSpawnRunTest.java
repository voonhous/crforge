package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version's area-effect spawn rows, as the Ice Wizard hero's ability spawns its cube's
 * areas: a location row places the area at the point its two expressions give, read with the
 * context the spawn carries; a plain row runs ActionToRunOnSpawned on the area it spawned, sharing
 * the context under ShareContext; and an area whose row sets LinkToInstigatorLife ends as its
 * parent leaves the battle.
 */
class BattleAreaSpawnRunTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String AREA = "Test_area";

  /** An action row of a class, its fields to fill. */
  private static ObjectNode action(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + type + "Data");
    row.put("ClassType", type);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", type);
    return fields;
  }

  /** A spawn row of the area, the Knight its source. */
  private static ObjectNode spawn(ObjectNode rows, String name, String type) {
    ObjectNode fields = action(rows, name, type);
    fields.put("SpawnType", "AreaEffectType");
    fields.put("SpawnData", AREA);
    fields.put("ParentGOAsSource", true);
    return fields;
  }

  /**
   * The configured tables with a plain long-lived area, linked to its parent's life when asked, and
   * the Knight's starting action the given group's row.
   */
  private static GameTables tables(Path folder, boolean linked, String start, ActionsEdit edit)
      throws IOException {
    GameData.altered(folder, "actions", edit::accept);
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          ObjectNode row = rows.putObject(AREA);
          row.put("index", rows.size());
          row.put("class", "LogicAreaEffectObjectData");
          ObjectNode columns = row.putObject("columns");
          columns.put("LifeDuration", 99999);
          columns.put("Rarity", "Common");
          if (linked) {
            columns.put("LinkToInstigatorLife", true);
          }
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("OnStartingAction", start));
    GameData.addTestVariable(folder);
    return GameTables.load(folder);
  }

  /** An edit of the actions table's rows. */
  private interface ActionsEdit {
    void accept(ObjectNode rows);
  }

  /** The tick and point of each area a spawn row made, and its removal. */
  private static List<String> watch(Standard1v1Battle battle) {
    List<String> log = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectSpawned(
                  int tick,
                  SpawnHost owner,
                  String action,
                  int phase,
                  SpawnHost source,
                  AreaEffectEntity areaEffect) {
                log.add(action + " at " + areaEffect.x() + " " + areaEffect.y());
              }

              @Override
              public void areaEffectRemoved(int tick, AreaEffectEntity areaEffect) {
                log.add(tick + " area removed");
              }

              @Override
              public void entityRemoved(int tick, WorldEntity removed) {
                log.add(tick + " " + removed.name() + " removed");
              }
            });
    return log;
  }

  @Test
  @DisplayName(
      "a location row places the area at its expressions' point, read with the context; a plain"
          + " row runs its spawned action on the area, which writes the shared context the"
          + " Knight's wait then reads")
  void locationAndActionOnSpawned(@TempDir Path folder) throws IOException {
    GameTables tables =
        tables(
            folder,
            false,
            "Test_start",
            rows -> {
              ObjectNode group = action(rows, "Test_start", "ActionGroup");
              group.put("ContextMode", "Create");
              ArrayNode parts = group.putArray("SubActions");
              for (String part : List.of("Test_mark", "Test_at", "Test_run", "Test_wait")) {
                parts.addObject().put("action", part);
              }
              group.putArray("SubActionsDelay").add(0).add(50).add(50).add(0);
              ObjectNode mark = action(rows, "Test_mark", "ActionBlackboardSetInt");
              mark.put("Key", "Mark");
              mark.put("Value", "4");
              ObjectNode at = spawn(rows, "Test_at", "ActionSpawnToLocation");
              at.put("XPositionExpression", "x + 1000");
              at.put("YPositionExpression", "y + as_int(#Mark, 0) * 500");
              ObjectNode run = spawn(rows, "Test_run", "ActionSpawn");
              run.put("ShareContext", true);
              run.putObject("ActionToRunOnSpawned").put("action", "Test_seen");
              ObjectNode seen = action(rows, "Test_seen", "ActionBlackboardSetInt");
              seen.put("Key", "Seen");
              seen.put("Value", "x");
              ObjectNode wait = action(rows, "Test_wait", "ActionWaitToActivate");
              wait.put("Condition", "as_int(#Seen) != -1");
              wait.putObject("OnActivateAction").put("action", "Test_set");
              ObjectNode set = action(rows, "Test_set", "ActionSetVariable");
              set.put("Variable", "TestVariable");
              set.put("Value", "as_int(#Seen, 0) + 1");
            });
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<String> log = watch(battle);
    battle.play(1, battle.getWorld().getRecords().card("Knight"), LEVEL, 0, 3500, 10000, "K");
    while (battle.getBattle().getTick() <= 60) {
      battle.getBattle().step();
    }
    CharacterEntity knight =
        battle.getWorld().getHolder().entities().stream()
            .filter(CharacterEntity.class::isInstance)
            .map(CharacterEntity.class::cast)
            .filter(unit -> unit.getData().name().equals("Knight"))
            .findFirst()
            .orElseThrow();

    // The Knight stands still while it deploys, so both spawns read the point it was played at.
    assertThat(log).contains("Test_at at 4499 12500", "Test_run at 3499 10500");
    assertThat(knight.variable(battle.getWorld().variableKey("TestVariable"))).isEqualTo(3500);
  }

  @Test
  @DisplayName(
      "an area linked to its instigator's life is removed as the Knight that spawned it leaves;"
          + " one not linked stays")
  void linkedAreaEndsWithItsParent(@TempDir Path folder) throws IOException {
    assertThat(knightKilledWith(folder.resolve("linked"), true))
        .containsSubsequence("Knight removed", "area removed");
    assertThat(knightKilledWith(folder.resolve("plain"), false))
        .noneMatch(line -> line.contains("area removed"));
  }

  /** A blue Knight of 1 hit point that spawns the area at its start, killed by a red Knight. */
  private static List<String> knightKilledWith(Path folder, boolean linked) throws IOException {
    Files.createDirectories(folder);
    GameTables tables =
        tables(folder, linked, "Test_spawn", rows -> spawn(rows, "Test_spawn", "ActionSpawn"));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<String> log = watch(battle);
    CharacterEntity blue =
        battle.deploy(0, battle.getWorld().getRecords().unit("Knight"), LEVEL, 0, 3500, 10000);
    blue.getHitPoints().setHitPoints(1);
    battle.deploy(0, battle.getWorld().getRecords().unit("Knight"), LEVEL, 1, 3500, 11000);
    for (int tick = 0; tick < 120; tick++) {
      battle.getBattle().step();
    }
    List<String> lines = new ArrayList<>();
    for (String line : log) {
      lines.add(line.replaceFirst("^\\d+ ", "").replaceFirst("Knight_\\d+", "Knight"));
    }
    return lines;
  }
}
