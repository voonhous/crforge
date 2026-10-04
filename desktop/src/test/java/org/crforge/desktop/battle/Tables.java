package org.crforge.desktop.battle;

import org.crforge.core.battle.data.GameTables;

/**
 * The configured game tables, loaded once for the visualizer's battle tests. The tables are
 * required: without them these tests fail, naming the setting.
 */
final class Tables {

  private static GameTables tables;

  private Tables() {
    // Utility class
  }

  static synchronized GameTables get() {
    if (tables == null) {
      tables = GameTables.loadConfigured();
    }
    return tables;
  }
}
