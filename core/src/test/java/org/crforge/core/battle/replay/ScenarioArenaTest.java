/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each player's arena in a replay: the arenas row its avatar names by data id. A fresh battle's
 * caller names each side's arena by row name and the writer writes that row's id; a replay's arenas
 * are read as rows of the arenas table and written back as read. The arena the tests name is a
 * hand-written row added after every row of the tables, so its id is read from the tables, never
 * typed here.
 */
class ScenarioArenaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The hand-written arena row, added after every arena the tables hold. */
  private static final String TEST_ARENA = "TestArena";

  @TempDir static Path folder;

  private static GameTables tables;

  @BeforeAll
  static void addTestArena() throws Exception {
    tables =
        GameData.altered(
            folder,
            ScenarioWriter.ARENAS,
            rows -> {
              int index = rows.size();
              ObjectNode row = rows.putObject(TEST_ARENA);
              row.put("index", index);
              row.put("class", "LogicArenaData");
              row.putObject("columns");
            });
  }

  /** Lets the copied tables go once the class is done, as the test run keeps its classes. */
  @AfterAll
  static void dropTables() {
    tables = null;
  }

  /** An arena row's data id, from the tables: its table's id times a million plus its index. */
  private static int id(String arena) {
    GameTable arenas = tables.table(ScenarioWriter.ARENAS);
    return Integer.parseInt(arenas.id()) * ReplayScenario.IDS_PER_TABLE + arenas.row(arena).index();
  }

  /** A fresh Ladder battle on the tables with the arena row, both players' data handed over. */
  private static Standard1v1Battle ladder() {
    Standard1v1Battle battle = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL);
    battle.getWorld().seed(11);
    battle.addPlayerData(1);
    battle.addPlayerData(1);
    List<String> deck =
        Arrays.stream(Scenarios.DECK).mapToObj(id -> ScenarioItems.row(tables, id).name()).toList();
    battle.startLadderMatch(deck, deck, 1, 2, new int[8], new int[8]);
    return battle;
  }

  /** The written document, as a file holds it: written out and parsed back. */
  private static JsonNode throughText(ObjectNode document) throws Exception {
    return MAPPER.readTree(MAPPER.writeValueAsString(document));
  }

  @Test
  void aFreshBattleIsWrittenWithTheArenaRowEachSidesCallerNames() throws Exception {
    String other = Scenarios.arena(tables);
    ScenarioWriter writer = new ScenarioWriter(tables);

    ScenarioPlan plan =
        writer.plan(ladder(), Standard1v1Battle.DEFAULT_LEVEL, List.of(TEST_ARENA, other));
    JsonNode document = throughText(writer.write(plan));

    assertThat(plan.arenas()).containsExactly(TEST_ARENA, other);
    assertThat(document.path("battle").path("avatar0").path("arena").asInt())
        .isEqualTo(id(TEST_ARENA));
    assertThat(document.path("battle").path("avatar1").path("arena").asInt()).isEqualTo(id(other));
    assertThat(new ReplayScenario(tables).translate(document).arenas())
        .containsExactly(TEST_ARENA, other);
  }

  @Test
  void aFreshBattleWithNoArenaNamedIsRefusedNamingWhatIsMissing() {
    ScenarioWriter writer = new ScenarioWriter(tables);
    Standard1v1Battle battle = ladder();

    assertThatThrownBy(() -> writer.plan(battle, Standard1v1Battle.DEFAULT_LEVEL, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no arena named");
    assertThatThrownBy(
            () ->
                writer.plan(
                    battle, Standard1v1Battle.DEFAULT_LEVEL, Arrays.asList(TEST_ARENA, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no arena named for side 1");
  }

  @Test
  void aFreshBattleNamingAnArenaTheTablesDoNotHoldIsRefusedNamingIt() {
    ScenarioWriter writer = new ScenarioWriter(tables);
    Standard1v1Battle battle = ladder();

    assertThatThrownBy(
            () ->
                writer.plan(
                    battle, Standard1v1Battle.DEFAULT_LEVEL, List.of(TEST_ARENA, "NoSuchArena")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchArena")
        .hasMessageContaining("no row of the arenas table");
  }

  @Test
  void aReplaysArenasAreReadAsArenaRowsAndWrittenBackAsRead() throws Exception {
    String other = Scenarios.arena(tables);
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knight(), tables);
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    ((ObjectNode) battle.path("avatar0")).put("arena", id(other));
    ((ObjectNode) battle.path("avatar1")).put("arena", id(TEST_ARENA));

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);
    JsonNode written = throughText(new ScenarioWriter(tables).write(plan));

    assertThat(plan.arenas()).containsExactly(other, TEST_ARENA);
    assertThat(written.path("battle").path("avatar0").path("arena").asInt()).isEqualTo(id(other));
    assertThat(written.path("battle").path("avatar1").path("arena").asInt())
        .isEqualTo(id(TEST_ARENA));
  }

  @Test
  void aReplayWhoseAvatarNamesNoArenaRowIsRefusedNamingTheField() {
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knight(), tables);
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    ((ObjectNode) battle.path("avatar1")).put("arena", 0);

    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("battle.avatar1.arena=0");
    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("no row of the arenas table");
  }
}
