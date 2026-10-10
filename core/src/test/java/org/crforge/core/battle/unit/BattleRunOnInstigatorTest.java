package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
 * A run on the instigator hands its action to the entity that caused it, as the Ice Wizard hero's
 * kill hook places its cube on the troop it killed: the action is built for the instigator, so the
 * expressions of the row it runs there read the instigator - its point, its side - and not the
 * owner that handed it over.
 */
class BattleRunOnInstigatorTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String AREA = "Test_area";

  /** How far along the length, toward its own side's direction, the area stands from its point. */
  private static final int AHEAD = 1000;

  /** An action row of a class, its fields to fill. */
  private static ObjectNode action(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + type + "Data");
    row.put("ClassType", type);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", type);
    return fields;
  }

  /**
   * The configured tables with a plain long-lived area, and the Knight's killed-done action a run
   * on the instigator of a location spawn of the area, its point read from the expressions' entity:
   * its own point, moved along the length by its side's direction.
   */
  private static GameTables tables(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode run = action(rows, "Test_onKill", "ActionRunOnInstigator");
          run.putObject("ActionToExecute").put("action", "Test_at");
          ObjectNode at = action(rows, "Test_at", "ActionSpawnToLocation");
          at.put("SpawnType", "AreaEffectType");
          at.put("SpawnData", AREA);
          at.put("UseDeploy", true);
          at.put("XPositionExpression", "x");
          at.put("YPositionExpression", "y + " + AHEAD + " * team_y_direction(team_index)");
        });
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
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("OnKilledDoneAction", "Test_onKill"));
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a Knight's killed-done run on the instigator places the area by the expressions of the"
          + " Skeleton it killed: at the Skeleton's point, ahead along its own side's direction,"
          + " not at the Knight's")
  void theHandedActionReadsTheInstigator(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(tables(folder), LEVEL, false);
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
            });
    CharacterEntity knight =
        battle.deploy(
            0, battle.getWorld().getRecords().unit("Knight"), LEVEL, 0, 9000, 10000, "knight");
    CharacterEntity skeleton =
        battle.deploy(
            0, battle.getWorld().getRecords().unit("Skeleton"), LEVEL, 1, 9000, 11200, "skeleton");
    skeleton.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    for (int i = 0; i < 400 && skeleton.getView().isAlive(); i++) {
      battle.getBattle().step();
    }
    assertThat(skeleton.getView().isAlive()).as("the Skeleton killed").isFalse();
    battle.getBattle().step();

    // The Skeleton is of side 1, whose direction along the length is +1; the Knight's is -1.
    assertThat(log).containsExactly("Test_at at " + skeleton.x() + " " + (skeleton.y() + AHEAD));
    assertThat(knight.x() != skeleton.x() || knight.y() - AHEAD != skeleton.y() + AHEAD)
        .as("the Knight's own point would place it elsewhere")
        .isTrue();
  }
}
