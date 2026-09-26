package org.crforge.core.battle.unit;

import org.crforge.data.game.BattleRecords;
import org.crforge.data.game.GameTables;

/**
 * The battle's records, built from the configured game tables, for the tests that play the
 * reference runs. The tables are required: without them these tests fail, naming the setting.
 */
final class GameData {

  private static BattleRecords records;

  private GameData() {
    // Utility class
  }

  /** The records of the configured tables, loaded once. */
  static synchronized BattleRecords records() {
    if (records == null) {
      records = new BattleRecords(GameTables.loadConfigured());
    }
    return records;
  }

  /** A unit or building by its row's name. */
  static UnitData unit(String name) {
    return records().unit(name);
  }
}
