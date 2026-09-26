package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/** One game table: its name and id, the data version it belongs to, and its rows by name. */
public final class GameTable {

  private final String name;
  private final String id;
  private final String version;
  private final String contentSha;
  private final Map<String, GameRow> rows;

  GameTable(String name, String id, String version, String contentSha, Map<String, GameRow> rows) {
    this.name = name;
    this.id = id;
    this.version = version;
    this.contentSha = contentSha;
    this.rows = Collections.unmodifiableMap(rows);
  }

  /** The table's name, which its file is named after. */
  public String name() {
    return name;
  }

  /** The table's id, as the data numbers its tables. */
  public String id() {
    return id;
  }

  /** The data version the table was exported from. */
  public String version() {
    return version;
  }

  /** The content hash of that data version. */
  public String contentSha() {
    return contentSha;
  }

  /** Every row, in creation order. */
  public Collection<GameRow> rows() {
    return rows.values();
  }

  /** True when the table has a row of the given name. */
  public boolean has(String rowName) {
    return rows.containsKey(rowName);
  }

  /** The row of the given name; fails when the table has none. */
  public GameRow row(String rowName) {
    GameRow row = rows.get(rowName);
    checkState(row != null, () -> "the " + name + " table has no row " + rowName);
    return row;
  }
}
