package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A game object filter may name the kinds of object it leaves out as a list, Filters, in place of
 * one switch per kind: Hidden for FilterHidden, NoHitpointComponent for
 * FilterIfNoHitpointComponent, and so on. Each name is the switch of the same test, so a row
 * written either way is the same filter. The kinds the switches have no test for (Self, Kamikaze,
 * IgnoreResurrect) are refused, as is a row that writes both forms.
 */
class GameObjectFilterListTest {

  /** The switches a list names, by the name the list gives each, in the list's kind order. */
  private static final Map<String, String> SWITCHES = new LinkedHashMap<>();

  static {
    SWITCHES.put("FilterHidden", "Hidden");
    SWITCHES.put("FilterInvisible", "Invisible");
    SWITCHES.put("FilterUnderground", "Underground");
    SWITCHES.put("FilterBuildings", "Buildings");
    SWITCHES.put("FilterTowers", "Towers");
    SWITCHES.put("FilterSummoner", "Summoner");
    SWITCHES.put("FilterFlying", "Flying");
    SWITCHES.put("FilterJumping", "Jumping");
    SWITCHES.put("FilterDashImmune", "DashImmune");
    SWITCHES.put("FilterDragging", "Dragging");
    SWITCHES.put("FilterCloning", "Cloning");
    SWITCHES.put("FilterIfNoHitpointComponent", "NoHitpointComponent");
    SWITCHES.put("FilterPushbackIgnore", "PushbackIgnore");
    SWITCHES.put("FilterSameObjects", "SameObjects");
    SWITCHES.put("FilterPrincessTowers", "PrincessTowers");
    SWITCHES.put("FilterClones", "Clones");
  }

  /** Rewrites every row's switches as a Filters list, in the order the row writes them. */
  private static void asLists(ObjectNode rows) {
    for (Iterator<JsonNode> it = rows.elements(); it.hasNext(); ) {
      ObjectNode columns = (ObjectNode) it.next().path("columns");
      ArrayNode list = columns.arrayNode();
      List<String> fields = new ArrayList<>();
      columns.fieldNames().forEachRemaining(fields::add);
      for (String field : fields) {
        if (SWITCHES.containsKey(field)) {
          if (columns.path(field).asBoolean(false)) {
            list.add(SWITCHES.get(field));
          }
          columns.remove(field);
        }
      }
      if (!list.isEmpty()) {
        columns.set("Filters", list);
      }
    }
  }

  @Test
  @DisplayName("every filter written as a Filters list is the filter its switches make")
  void aListIsItsSwitches(@TempDir Path folder) throws IOException {
    GameTables lists =
        GameData.altered(folder, "game_object_filters", GameObjectFilterListTest::asLists);
    BattleRecords records = new BattleRecords(lists);
    int listed = 0;
    for (GameRow row : GameData.tables().table("game_object_filters").rows()) {
      assertThat(records.filter(row.name()))
          .as(row.name())
          .usingRecursiveComparison()
          .isEqualTo(GameData.records().filter(row.name()));
      if (lists.table("game_object_filters").row(row.name()).has("Filters")) {
        listed++;
      }
    }
    assertThat(listed).as("rows written as a list").isGreaterThan(20);
  }

  @Test
  @DisplayName("a list naming a kind the switches have no test for is refused")
  void aKindWithoutASwitchIsRefused(@TempDir Path folder) throws IOException {
    for (String kind : List.of("Self", "Kamikaze", "IgnoreResurrect", "Teleporting")) {
      GameTables tables =
          GameData.altered(
              Files.createDirectories(folder.resolve(kind)),
              "game_object_filters",
              rows -> {
                ObjectNode columns = GameData.columns(rows, "EnemyTowersOnly");
                columns.set("Filters", columns.arrayNode().add("Hidden").add(kind));
              });
      BattleRecords records = new BattleRecords(tables);
      assertThatThrownBy(() -> records.filter("EnemyTowersOnly"))
          .as(kind)
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessage(
              "the game object filter EnemyTowersOnly lists the kind "
                  + kind
                  + " in Filters, which is not modelled");
    }
  }

  @Test
  @DisplayName("a row writing both a Filters list and a switch is refused")
  void bothFormsAreRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "game_object_filters",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "EnemyTowersOnly");
              columns.set("Filters", columns.arrayNode().add("Hidden"));
              columns.put("FilterFlying", true);
            });
    BattleRecords records = new BattleRecords(tables);
    assertThatThrownBy(() -> records.filter("EnemyTowersOnly"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the game object filter EnemyTowersOnly writes both a Filters list and the switch"
                + " FilterFlying, which no data version writes together");
  }
}
