/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version gives the units that jump the river a check of their own jump: an interval
 * of one step runs a check of the instigator, the unit itself, through a game object filter that
 * passes only the unit that asks (MatchSelf) and leaves out a jumping one (a Filters list written
 * as the single text Jumping). While the unit jumps the check misses, and its miss branch gives the
 * unit a buff that ignores pushback for one step; on the ground it matches and does nothing.
 */
class BattleJumpCheckTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;
  private static final String BUFF = "JumpHack_Ignore_Pushback_Buff";
  private static final String FILTER = "JumpHack_Self_Jump_Filter";

  /** The configured tables with the newer version's jump check rows, and the Hog Rider's start. */
  private static GameTables withJumpCheck(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          rows.set(
              "JumpHack_Check_Jump_Interval",
              action(
                  "LogicActionIntervalData",
                  "ActionInterval",
                  f -> {
                    f.putObject("ActionToExecute").put("action", "JumpHack_Check_Self_Jump");
                    f.put("Interval", 50);
                  }));
          rows.set(
              "JumpHack_Check_Self_Jump",
              action(
                  "LogicActionRunIfInstigatorMatchesData",
                  "ActionRunIfInstigatorMatches",
                  f -> {
                    f.putObject("ActionToRunIfNoMatch").put("action", "JumpHack_Spawn_Buff");
                    f.put("GameObjectFilter", FILTER);
                  }));
          rows.set(
              "JumpHack_Spawn_Buff",
              action(
                  "LogicActionSpawnData",
                  "ActionSpawn",
                  f -> {
                    f.put("SpawnData", BUFF);
                    f.put("SpawnTime", 50);
                    f.put("SpawnType", "BuffType");
                  }));
        });
    edit(
        folder,
        "game_object_filters",
        rows -> {
          ObjectNode row = rows.putObject(FILTER);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicGameObjectFilterData");
          ObjectNode columns = row.putObject("columns");
          columns.put("Filters", "Jumping");
          columns.put("MatchSelf", true);
          columns.put("MatchTeamOwn", true);
          columns.put("MatchTypeCharacters", true);
        });
    edit(
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
    edit(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "HogRider")
                .put("OnStartingAction", "JumpHack_Check_Jump_Interval"));
    return GameTables.load(folder);
  }

  /** An action row of a class with its fields. */
  private static ObjectNode action(String className, String classType, Consumer<ObjectNode> edit) {
    ObjectNode row = new ObjectMapper().createObjectNode();
    row.put("class", className);
    row.put("ClassType", classType);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", classType);
    edit.accept(fields);
    return row;
  }

  /** Alters the rows of one more table in a folder {@link GameData#altered} filled. */
  private static void edit(Path folder, String table, Consumer<ObjectNode> edit)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve(table + ".json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    edit.accept((ObjectNode) document.get("rows"));
    mapper.writeValue(file.toFile(), document);
  }

  @Test
  @DisplayName(
      "a filter whose Filters list is the single text Jumping leaves out jumping objects, and one"
          + " that matches only its asker is refused when asked without it")
  void theFilterReadsItsTextAndItsAsker(@TempDir Path folder) throws IOException {
    GameObjectFilter filter = new BattleRecords(withJumpCheck(folder)).filter(FILTER);

    assertThat(filter.isFilterJumping()).isTrue();
    assertThat(filter.isMatchSelf()).isTrue();
    assertThatThrownBy(
            () ->
                filter.matches(
                    new Standard1v1Battle(GameData.tables(), LEVEL, false)
                        .deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000)
                        .filterSubject(),
                    0,
                    "Knight"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("MatchSelf");
  }

  @Test
  @DisplayName(
      "a Hog Rider carries the buff that ignores pushback in every step of its jump over the river"
          + " and in none before it")
  void theHogRiderIgnoresPushbackWhileItJumps(@TempDir Path folder) throws IOException {
    GameTables tables = withJumpCheck(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity hog =
        match.deploy(0, new BattleRecords(tables).unit("HogRider"), LEVEL, 0, 9000, 13000);
    List<String> before = new ArrayList<>();
    List<String> jumping = new ArrayList<>();
    for (int step = 0; step < 400; step++) {
      match.getBattle().step();
      boolean jumps = hog.getView().getState() == GridEntityState.JUMPING;
      if (jumps) {
        jumping.add(hog.getBuffs().carries(BUFF) ? "buffed" : "bare");
      } else if (jumping.isEmpty()) {
        before.add(hog.getBuffs().carries(BUFF) ? "buffed" : "bare");
      }
    }

    assertThat(jumping).as("the steps of the jump").isNotEmpty().containsOnly("buffed");
    assertThat(before).as("the steps before the jump").isNotEmpty().containsOnly("bare");
  }
}
