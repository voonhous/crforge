package org.crforge.data.game;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One row of a game table: its name, its creation order in the table, its class, and its columns
 * under the game's own names, with their values as the game reads them.
 *
 * <p>A column the row does not set, or sets to an empty cell, reads as the game reads it: 0, false
 * or the empty string.
 */
public final class GameRow {

  private final String name;
  private final int index;
  private final String className;
  private final Map<String, JsonNode> columns;

  GameRow(String name, int index, String className, Map<String, JsonNode> columns) {
    this.name = name;
    this.index = index;
    this.className = className;
    this.columns = Collections.unmodifiableMap(columns);
  }

  /** The row's name. */
  public String name() {
    return name;
  }

  /** The row's creation order in its table; for a game tag, its bit in an object's tag word. */
  public int index() {
    return index;
  }

  /** The row's class, which matters in the tables that hold several; null where none is given. */
  public String className() {
    return className;
  }

  /** Every column the row sets, by the game's name. */
  public Map<String, JsonNode> columns() {
    return columns;
  }

  /** True when the row sets the column to a value, an empty cell not counting. */
  public boolean has(String column) {
    JsonNode value = columns.get(column);
    return value != null && !value.isNull();
  }

  /** The column's raw value, or null when the row does not set it. */
  public JsonNode value(String column) {
    return has(column) ? columns.get(column) : null;
  }

  /** The column as an integer; 0 when the row does not set it. */
  public int intValue(String column) {
    return has(column) ? columns.get(column).asInt() : 0;
  }

  /** The column as a boolean; false when the row does not set it. */
  public boolean bool(String column) {
    return has(column) && columns.get(column).asBoolean();
  }

  /** The column as a string; empty when the row does not set it. */
  public String string(String column) {
    return has(column) ? columns.get(column).asText() : "";
  }

  /** The column as a list of strings; empty when the row does not set it. */
  public List<String> strings(String column) {
    List<String> out = new ArrayList<>();
    if (has(column)) {
      for (JsonNode element : columns.get(column)) {
        out.add(element.asText());
      }
    }
    return List.copyOf(out);
  }

  @Override
  public String toString() {
    return name;
  }
}
