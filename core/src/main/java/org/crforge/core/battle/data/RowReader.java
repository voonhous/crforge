/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * What every reader of the battle's records shares: the game tables, the facade for a record
 * another reader builds, and the row-reading helpers - a set column, a named action, an inline row,
 * a rarity, a tag mask, an integer list.
 */
abstract class RowReader {

  static final String PROJECTILES = "projectiles";

  static final String GAME_TAGS = "game_tags";

  /** The game tables of one data version. */
  final GameTables tables;

  /** The battle's records, for a record another reader builds. */
  final BattleRecords records;

  RowReader(GameTables tables, BattleRecords records) {
    this.tables = tables;
    this.records = records;
  }

  /** True when a row sets a column: a value that is not empty, false, 0 or an empty list. */
  static boolean set(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asDouble() != 0;
    }
    return !value.isEmpty();
  }

  /**
   * True when a row sets a column: a value other than empty, 0 or false. A table counts as set
   * unless it is empty, and a fraction unless it is 0, so a column of either form is never taken
   * for one the row leaves out.
   */
  static boolean sets(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asDouble() != 0;
    }
    return !value.isEmpty();
  }

  /**
   * The action row a hook column names, as a reference or by its name; null for none. A hook
   * written as an inline row of its own, with no name to build it by, is refused, so it is never
   * read as no hook at all.
   */
  static String actionName(GameRow row, String column) {
    return actionName(row.name(), column, row.value(column));
  }

  /**
   * The action row a column's value names, as a reference or by its name; null for none. A value
   * written as an inline row of its own is refused.
   *
   * @param owner the row the value belongs to, for the refusal
   * @param column the column, for the refusal
   * @param value the column's value, or null
   */
  static String actionName(String owner, String column, JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      throw new UnsupportedOperationException(
          owner
              + " writes its "
              + column
              + " inline, as "
              + value.path("ClassType").asText("an unnamed row")
              + ", which is not modelled");
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /**
   * The action row a column names, or, for a row written inline, the actions table's row named
   * after the row and the column; null for none.
   */
  String inlineActionName(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      String inline = row.name() + "_" + column;
      checkArgument(
          tables.actionNames().contains(inline),
          () -> row.name() + " writes its " + column + " inline, with no row " + inline);
      return inline;
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /**
   * Whether a column's value names an action row, by reference or by its name, rather than writing
   * one inline.
   */
  static boolean namedAction(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return false;
    }
    return value.isTextual() || value.isObject() && value.has("action");
  }

  /**
   * Whether every element of a group's sub-actions names an action row, none written inline: such a
   * group is the actions table's row named after its unit and column, sub-actions and all.
   */
  static boolean namesOnly(JsonNode subActions) {
    if (!subActions.isArray() || subActions.isEmpty()) {
      return false;
    }
    for (JsonNode sub : subActions) {
      if (!sub.isTextual()
          && !(sub.isObject() && sub.size() == 1 && sub.path("action").isTextual())) {
        return false;
      }
    }
    return true;
  }

  /** Whether a column holds an inline group of named rows that the actions table has a row for. */
  boolean inlineNamedGroup(GameRow row, String column) {
    JsonNode value = row.value(column);
    return value != null
        && value.isObject()
        && !value.has("action")
        && value.path("ClassType").asText().equals("ActionGroup")
        && namesOnly(value.path("SubActions"))
        && tables.actionNames().contains(row.name() + "_" + column);
  }

  long tagBits(String names) {
    long bits = 0;
    for (String name : names.split(",")) {
      String tag = name.trim();
      if (tag.isEmpty()) {
        continue;
      }
      GameTable tags = tables.table(GAME_TAGS);
      checkArgument(tags.has(tag), () -> "the game tables have no game tag " + tag);
      bits |= 1L << tags.row(tag).index();
    }
    return bits;
  }

  /** The published rarity row of that name; a row without a rarity is scaled as Common. */
  static RarityTable rarity(String name) {
    if (name.isEmpty()) {
      return RarityTable.COMMON;
    }
    for (RarityTable rarity : RarityTable.PUBLISHED) {
      if (rarity.name().equals(name)) {
        return rarity;
      }
    }
    throw new IllegalArgumentException("no published rarity " + name);
  }

  /** An array column of whole numbers; an empty list for a column left out. */
  static List<Integer> ints(GameRow row, String column) {
    List<Integer> out = new ArrayList<>();
    for (JsonNode element : arrayOf(row, column)) {
      out.add(row.intElement(column, element));
    }
    return out;
  }

  /**
   * An array column's elements; none for a column left out or an empty cell. A column of one value
   * of another shape is refused.
   */
  static List<JsonNode> arrayOf(GameRow row, String column) {
    List<JsonNode> out = new ArrayList<>();
    JsonNode value = row.value(column);
    if (value != null && value.isArray()) {
      value.forEach(out::add);
    } else if (value != null && !(value.isTextual() && value.asText().isEmpty())) {
      // A column of one value where a list is read: refused, naming its shape.
      row.strings(column);
    }
    return out;
  }

  /**
   * The unmodelled columns a row already lists, followed by every other column it sets that its
   * loader never read and that is neither inert nor pending a trace, in name order.
   *
   * @param listed the columns already listed as not modelled
   * @param row the tracking view the row was loaded through
   * @param presentation the columns that only show something
   * @param inert the columns the record shows no battle logic reads
   * @param pending the columns whose role is not yet established, carried unread until it is
   */
  static List<String> withUnread(
      List<String> listed,
      GameRow row,
      Set<String> presentation,
      Set<String> inert,
      Set<String> pending) {
    List<String> unread = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!row.read().contains(column)
          && !listed.contains(column)
          && !presentation.contains(column)
          && !inert.contains(column)
          && !pending.contains(column)
          && sets(row, column)) {
        unread.add(column);
      }
    }
    if (unread.isEmpty()) {
      return listed;
    }
    Collections.sort(unread);
    List<String> all = new ArrayList<>(listed);
    all.addAll(unread);
    return all;
  }

  /**
   * The columns a unit's row sets that the battle does not model: those of any unit, those of a
   * spawner for a row that spawns characters, and a spawner with neither a count nor an interval,
   * which spawns once as its deploy ends.
   */
  /** A column of row names, written as one name or a list of them. */
  static List<String> namesOf(GameRow row, String column) {
    if (row.has(column) && row.value(column).isTextual()) {
      String name = row.string(column);
      return name.isEmpty() ? List.of() : List.of(name);
    }
    return row.strings(column);
  }

  /** A column of numbers, written as one number or a list of them; empty when the row sets none. */
  static List<Integer> intsOf(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null) {
      return List.of();
    }
    if (!value.isArray()) {
      return List.of(row.intValue(column));
    }
    return row.ints(column);
  }
}
