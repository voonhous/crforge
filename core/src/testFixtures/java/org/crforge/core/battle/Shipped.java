package org.crforge.core.battle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.replay.ScenarioItems;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * The configured tables' columns as the tables write them, for a test whose expected value is a
 * shipped row's. Each value is read from the row's own JSON, never through the battle's readers, so
 * the expected value follows the data from one version of the tables to the next while the test
 * still checks what the battle makes of it.
 */
public final class Shipped {

  private Shipped() {
    // Utility class
  }

  /** The configured tables. */
  private static GameTables tables() {
    return GameData.tables();
  }

  /** A row of a configured table; fails when the table has none of that name. */
  public static GameRow row(String table, String name) {
    return tables().table(table).row(name);
  }

  /** A unit's row: a character's, else a building's. */
  public static GameRow unitRow(String name) {
    return unitRow(tables(), name);
  }

  /** A unit's row in a set of tables: a character's, else a building's. */
  public static GameRow unitRow(GameTables tables, String name) {
    return tables.table("characters").has(name)
        ? tables.table("characters").row(name)
        : tables.table("buildings").row(name);
  }

  /** A card's elixir cost: its row's ManaCost, from whichever card table holds it. */
  public static int cost(String card) {
    return number(ScenarioItems.card(tables(), card), "ManaCost");
  }

  /** The published rarity a row's Rarity column names; Common when the row names none. */
  public static RarityTable rarity(GameRow row) {
    String name = text(row, "Rarity");
    String rarity = name == null ? "Common" : name;
    return RarityTable.PUBLISHED.stream()
        .filter(table -> table.name().equals(rarity))
        .findFirst()
        .orElseThrow();
  }

  /**
   * A card stat at a level counted from 1, worked out in the test: the base times the multiplier of
   * its row's rarity, in hundredths, for the steps the level stands above the rarity's first,
   * truncated; the base itself on the first level or below it.
   */
  public static int scaled(int base, GameRow row, int level) {
    return scaledBy(base, rarity(row), Math.max(level - rarity(row).firstLevel(), 0));
  }

  /**
   * A stat at a packed level: the base times the multiplier of its row's rarity, in hundredths, at
   * the packed level's step, truncated; the base itself with no step.
   */
  public static int scaledAtPackedLevel(int base, GameRow row, int packedLevel) {
    return scaledBy(base, rarity(row), PackedLevel.steps(packedLevel));
  }

  /**
   * A stat of a unit at its level: the base times the multiplier of its row's rarity, in
   * hundredths, at the step of the unit's packed level, truncated.
   */
  public static int scaled(int base, CharacterEntity unit) {
    return scaledAtPackedLevel(base, unitRow(unit.getData().name()), unit.getPackedLevel());
  }

  private static int scaledBy(int base, RarityTable rarity, int steps) {
    return steps == 0 ? base : base * rarity.multiplier(steps - 1) / 100;
  }

  /**
   * A battle's whole length in ticks: the sections of the Ladder mode's battle timeline, in seconds
   * of 20 ticks, summed.
   */
  public static int battleTicks() {
    return battleTicks(tables());
  }

  /**
   * A battle's whole length in ticks on a set of tables: the sections of the Ladder mode's battle
   * timeline, in seconds of 20 ticks, summed.
   */
  public static int battleTicks(GameTables tables) {
    String timeline = text(tables.table("game_modes").row(LadderMatch.GAME_MODE), "BattleTimeline");
    int seconds = 0;
    for (int length : numbers(tables.table("battle_timelines").row(timeline), "SectionLength")) {
      seconds += length;
    }
    return seconds * 1000 / 50;
  }

  /** A column as the row writes it, or null when the row leaves it out or leaves it empty. */
  public static JsonNode column(GameRow row, String column) {
    JsonNode value = row.columns().get(column);
    return value == null || value.isNull() ? null : value;
  }

  /** A number column; 0 when the row leaves it out. */
  public static int number(GameRow row, String column) {
    return number(row, column, 0);
  }

  /** A number column; the given value when the row leaves it out. */
  public static int number(GameRow row, String column, int whenLeftOut) {
    JsonNode value = column(row, column);
    return value == null ? whenLeftOut : value.asInt();
  }

  /** A boolean column; false when the row leaves it out. */
  public static boolean flag(GameRow row, String column) {
    JsonNode value = column(row, column);
    return value != null && value.asBoolean();
  }

  /** A text column; null when the row leaves it out or leaves it empty. */
  public static String text(GameRow row, String column) {
    JsonNode value = column(row, column);
    return value == null || value.asText().isEmpty() ? null : value.asText();
  }

  /** A list of numbers; empty when the row leaves it out. */
  public static List<Integer> numbers(GameRow row, String column) {
    return numbers(column(row, column));
  }

  /** A list of texts; empty when the row leaves it out. */
  public static List<String> texts(GameRow row, String column) {
    List<String> out = new ArrayList<>();
    JsonNode value = column(row, column);
    if (value != null) {
      value.forEach(element -> out.add(element.asText()));
    }
    return out;
  }

  /** The fields of an action row as the actions table writes them. */
  public static JsonNode fields(String action) {
    return tables().action(action).fields();
  }

  /** A number field of an action row; 0 when the row leaves it out. */
  public static int number(String action, String field) {
    return number(action, field, 0);
  }

  /** A number field of an action row; the given value when the row leaves it out. */
  public static int number(String action, String field, int whenLeftOut) {
    JsonNode value = fields(action).get(field);
    return value == null || value.isNull() ? whenLeftOut : value.asInt();
  }

  /** A text field of an action row; null when the row leaves it out. */
  public static String text(String action, String field) {
    JsonNode value = fields(action).get(field);
    return value == null || value.isNull() || value.asText().isEmpty() ? null : value.asText();
  }

  /** A list of numbers an action row's field holds; empty when the row leaves it out. */
  public static List<Integer> numbers(String action, String field) {
    return numbers(fields(action).get(field));
  }

  /** A list of texts an action row's field holds; empty when the row leaves it out. */
  public static List<String> texts(String action, String field) {
    List<String> out = new ArrayList<>();
    JsonNode value = fields(action).get(field);
    if (value != null && !value.isNull()) {
      value.forEach(element -> out.add(element.asText()));
    }
    return out;
  }

  /**
   * The name of the action an action row's field names, as {@code {"action": name}}; null when the
   * row leaves the field out.
   */
  public static String actionName(String action, String field) {
    JsonNode value = fields(action).get(field);
    return value == null || value.isNull() ? null : value.path("action").asText();
  }

  /** The names of the actions a list field of an action row names, in its order. */
  public static List<String> actionNames(String action, String field) {
    List<String> out = new ArrayList<>();
    JsonNode value = fields(action).get(field);
    if (value != null) {
      value.forEach(element -> out.add(element.path("action").asText()));
    }
    return out;
  }

  /** The tick count of a delay in milliseconds, whole 50 ms steps only. */
  public static int ticks(int milliseconds) {
    return milliseconds / 50;
  }

  private static List<Integer> numbers(JsonNode value) {
    List<Integer> out = new ArrayList<>();
    if (value != null && !value.isNull()) {
      value.forEach(element -> out.add(element.asInt()));
    }
    return out;
  }
}
