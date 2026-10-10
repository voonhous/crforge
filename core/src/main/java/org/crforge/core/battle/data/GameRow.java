/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One row of a game table: its name, its creation order in the table, its class, and its columns
 * under the game's own names, with their values as the game reads them.
 *
 * <p>A column the row does not set, or sets to an empty cell, reads as the game reads it: 0, false
 * or the empty string. A column read by type must hold a value of that type: one of another shape,
 * as a table where a number is read or a name where a boolean is, is refused with the row, the
 * column and the shape, never read as 0, false or empty. The raw value stays readable.
 *
 * <p>A tracking view of a row records every column read through it, so a loader can tell which of
 * the columns a row sets it never read.
 */
public final class GameRow {

  /** The table the row is of, for the messages that name it. */
  private final String table;

  private final String name;
  private final int index;
  private final String className;
  private final Map<String, JsonNode> columns;

  /** The id the game gives the row, or null for a row of a table that carries none. */
  private final Integer globalId;

  /** The columns read through this view, or null for a view that does not track them. */
  private final Set<String> read;

  GameRow(String table, String name, int index, String className, Map<String, JsonNode> columns) {
    this(table, name, index, className, columns, null);
  }

  GameRow(
      String table,
      String name,
      int index,
      String className,
      Map<String, JsonNode> columns,
      Integer globalId) {
    this(table, name, index, className, Collections.unmodifiableMap(columns), globalId, null);
  }

  private GameRow(
      String table,
      String name,
      int index,
      String className,
      Map<String, JsonNode> columns,
      Integer globalId,
      Set<String> read) {
    this.table = table;
    this.name = name;
    this.index = index;
    this.className = className;
    this.columns = columns;
    this.globalId = globalId;
    this.read = read;
  }

  /** A view of the row that records every column read through it, starting from none. */
  public GameRow tracking() {
    return new GameRow(table, name, index, className, columns, globalId, new HashSet<>());
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

  /**
   * The columns the row sets to a value other than an empty cell, false, 0, an empty list or an
   * empty table, in the row's order. Nothing is recorded as read.
   */
  public List<String> setColumns() {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, JsonNode> column : columns.entrySet()) {
      JsonNode value = column.getValue();
      boolean set =
          value != null
              && !value.isNull()
              && !(value.isTextual() && value.asText().isEmpty())
              && !(value.isBoolean() && !value.asBoolean())
              && !(value.isNumber() && value.asDouble() == 0)
              && !(value.isContainerNode() && value.isEmpty());
      if (set) {
        out.add(column.getKey());
      }
    }
    return out;
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

  /**
   * The column as an integer; 0 when the row does not set it or sets an empty cell.
   *
   * @throws UnsupportedOperationException when it holds anything but a whole number
   */
  public int intValue(String column) {
    JsonNode value = set(column);
    if (value == null) {
      return 0;
    }
    if (!isWhole(value)) {
      throw mistyped(column, value, "a number");
    }
    return value.asInt();
  }

  /**
   * The column as a boolean; false when the row does not set it or sets an empty cell.
   *
   * @throws UnsupportedOperationException when it holds anything but a boolean
   */
  public boolean bool(String column) {
    JsonNode value = set(column);
    if (value == null) {
      return false;
    }
    if (!value.isBoolean()) {
      throw mistyped(column, value, "a boolean");
    }
    return value.asBoolean();
  }

  /**
   * The column as a string; empty when the row does not set it.
   *
   * @throws UnsupportedOperationException when it holds anything but a text
   */
  public String string(String column) {
    JsonNode value = set(column);
    if (value == null) {
      return "";
    }
    if (!value.isTextual()) {
      throw mistyped(column, value, "a text");
    }
    return value.asText();
  }

  /**
   * The column as a list of strings; empty when the row does not set it or sets an empty cell.
   *
   * @throws UnsupportedOperationException when it holds anything but a list of texts
   */
  public List<String> strings(String column) {
    List<String> out = new ArrayList<>();
    JsonNode value = set(column);
    if (value != null) {
      if (!value.isArray()) {
        throw mistyped(column, value, "a list of texts");
      }
      for (JsonNode element : value) {
        if (!element.isTextual()) {
          throw mistypedElement(column, element, "a list of texts");
        }
        out.add(element.asText());
      }
    }
    return List.copyOf(out);
  }

  /**
   * The column as a list of integers; empty when the row does not set it or sets an empty cell.
   *
   * @throws UnsupportedOperationException when it holds anything but a list of whole numbers
   */
  public List<Integer> ints(String column) {
    List<Integer> out = new ArrayList<>();
    JsonNode value = set(column);
    if (value != null) {
      if (!value.isArray()) {
        throw mistyped(column, value, "a list of numbers");
      }
      for (JsonNode element : value) {
        if (!isWhole(element)) {
          throw mistypedElement(column, element, "a list of numbers");
        }
        out.add(element.asInt());
      }
    }
    return List.copyOf(out);
  }

  /**
   * An element of a list the column holds, as an integer.
   *
   * @throws UnsupportedOperationException when it is anything but a whole number
   */
  public int intElement(String column, JsonNode element) {
    if (!isWhole(element)) {
      throw mistypedElement(column, element, "a number");
    }
    return element.asInt();
  }

  /**
   * An element of a list the column holds, as a boolean.
   *
   * @throws UnsupportedOperationException when it is anything but a boolean
   */
  public boolean boolElement(String column, JsonNode element) {
    if (!element.isBoolean()) {
      throw mistypedElement(column, element, "a boolean");
    }
    return element.asBoolean();
  }

  /**
   * An element of a list the column holds, as a string.
   *
   * @throws UnsupportedOperationException when it is anything but a text
   */
  public String textElement(String column, JsonNode element) {
    if (!element.isTextual()) {
      throw mistypedElement(column, element, "a text");
    }
    return element.asText();
  }

  /**
   * An element of a list the column holds, as a table.
   *
   * @throws UnsupportedOperationException when it is anything but a table
   */
  public JsonNode tableElement(String column, JsonNode element) {
    if (!element.isObject()) {
      throw mistypedElement(column, element, "a table");
    }
    return element;
  }

  /**
   * A field of a table the column holds, or of a table in its list, as an integer; the fallback
   * when the table leaves it out.
   *
   * @throws UnsupportedOperationException when it is anything but a whole number
   */
  public int intField(String column, JsonNode table, String field, int fallback) {
    JsonNode value = table.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!isWhole(value)) {
      throw mistypedField(column, field, value, "a number");
    }
    return value.asInt();
  }

  /**
   * A field of a table the column holds, or of a table in its list, as a boolean; the fallback when
   * the table leaves it out.
   *
   * @throws UnsupportedOperationException when it is anything but a boolean
   */
  public boolean boolField(String column, JsonNode table, String field, boolean fallback) {
    JsonNode value = table.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isBoolean()) {
      throw mistypedField(column, field, value, "a boolean");
    }
    return value.asBoolean();
  }

  /**
   * A field of a table the column holds, or of a table in its list, as a string; empty when the
   * table leaves it out.
   *
   * @throws UnsupportedOperationException when it is anything but a text
   */
  public String textField(String column, JsonNode table, String field) {
    JsonNode value = table.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw mistypedField(column, field, value, "a text");
    }
    return value.asText();
  }

  /**
   * The column's value when the row sets it to anything but an empty cell, recording the read; null
   * otherwise.
   */
  private JsonNode set(String column) {
    JsonNode value = value(column);
    return value == null || value.isTextual() && value.asText().isEmpty() ? null : value;
  }

  /** The refusal of a column whose value is of another shape than the reader reads. */
  private UnsupportedOperationException mistyped(String column, JsonNode value, String expected) {
    return new UnsupportedOperationException(
        "the "
            + table
            + " row "
            + name
            + " sets "
            + column
            + " to "
            + shape(value)
            + " where "
            + expected
            + " is read, which is not modelled");
  }

  /** The refusal of a list column holding an element of another shape than the reader reads. */
  private UnsupportedOperationException mistypedElement(
      String column, JsonNode element, String expected) {
    return new UnsupportedOperationException(
        "the "
            + table
            + " row "
            + name
            + " sets "
            + column
            + " to a list holding "
            + shape(element)
            + " where "
            + expected
            + " is read, which is not modelled");
  }

  /** The refusal of a field of a table the column holds whose value is of another shape. */
  private UnsupportedOperationException mistypedField(
      String column, String field, JsonNode value, String expected) {
    return new UnsupportedOperationException(
        "the "
            + table
            + " row "
            + name
            + " sets "
            + column
            + " to a table whose "
            + field
            + " is "
            + shape(value)
            + " where "
            + expected
            + " is read, which is not modelled");
  }

  /**
   * Whether a value is a whole number an integer holds: an integer, or a fraction with nothing
   * after its point, as the data writes some.
   */
  static boolean isWhole(JsonNode value) {
    return value.isNumber()
        && value.canConvertToInt()
        && (value.isIntegralNumber() || value.asDouble() == Math.rint(value.asDouble()));
  }

  /** A value's shape as a refusal names it: its kind, with its keys or its value. */
  static String shape(JsonNode value) {
    if (value.isObject()) {
      List<String> keys = new ArrayList<>();
      value.fieldNames().forEachRemaining(keys::add);
      return keys.isEmpty() ? "an empty table" : "a table of " + String.join(", ", keys);
    }
    if (value.isArray()) {
      return "a list of " + value.size();
    }
    if (value.isTextual()) {
      return "the text \"" + value.asText() + "\"";
    }
    if (value.isBoolean()) {
      return "the boolean " + value.asBoolean();
    }
    if (value.isIntegralNumber()) {
      return "the number " + value.asText();
    }
    if (value.isNumber()) {
      return "the fraction " + value.asText();
    }
    return "a " + value.getNodeType().toString().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return name;
  }
}
