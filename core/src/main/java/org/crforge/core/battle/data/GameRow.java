package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row of a game table: its name, its creation order in the table, its class, and its columns
 * under the game's own names, with their values as the game reads them.
 *
 * <p>A column the row does not set, or sets to an empty cell, reads as the game reads it: 0, false
 * or the empty string.
 *
 * <p>A tracking view of a row records every column read through it, so a loader can tell which of
 * the columns a row sets it never read.
 */
public final class GameRow {

  private final String name;
  private final int index;
  private final String className;
  private final Map<String, JsonNode> columns;

  /** The id the game gives the row, or null for a row of a table that carries none. */
  private final Integer globalId;

  /** The columns read through this view, or null for a view that does not track them. */
  private final Set<String> read;

  GameRow(String name, int index, String className, Map<String, JsonNode> columns) {
    this(name, index, className, columns, null);
  }

  GameRow(
      String name, int index, String className, Map<String, JsonNode> columns, Integer globalId) {
    this(name, index, className, Collections.unmodifiableMap(columns), globalId, null);
  }

  private GameRow(
      String name,
      int index,
      String className,
      Map<String, JsonNode> columns,
      Integer globalId,
      Set<String> read) {
    this.name = name;
    this.index = index;
    this.className = className;
    this.columns = columns;
    this.globalId = globalId;
    this.read = read;
  }

  /** A view of the row that records every column read through it, starting from none. */
  public GameRow tracking() {
    return new GameRow(name, index, className, columns, globalId, new HashSet<>());
  }

  /**
   * The columns read through this view so far.
   *
   * @throws IllegalStateException for a view that does not track them
   */
  public Set<String> read() {
    if (read == null) {
      throw new IllegalStateException("the row " + name + " does not track its reads");
    }
    return Collections.unmodifiableSet(read);
  }

  /** The row's name. */
  public String name() {
    return name;
  }

  /** The row's creation order in its table; for a game tag, its bit in an object's tag word. */
  public int index() {
    return index;
  }

  /**
   * The id the game gives the row: its fixed id, or a hash of its type and name, which may be
   * negative. Only the rows of the characters and buildings tables carry one.
   *
   * @throws IllegalStateException for a row whose table carries no global id
   */
  public int globalId() {
    if (globalId == null) {
      throw new IllegalStateException("the row " + name + " carries no global id");
    }
    return globalId;
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
    if (read != null) {
      read.add(column);
    }
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
