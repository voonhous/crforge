package org.crforge.core.battle;

import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.data.game.BattleRecords;
import org.crforge.data.game.GameTables;

/**
 * The battle's records, built from the configured game tables, for the tests that play units and
 * cards. The tables are required: without them these tests fail, naming the setting.
 */
public final class GameData {

  private static BattleRecords records;

  private GameData() {
    // Utility class
  }

  /** The records of the configured tables, loaded once. */
  public static synchronized BattleRecords records() {
    if (records == null) {
      records = new BattleRecords(GameTables.loadConfigured());
    }
    return records;
  }

  /** A unit or building by its row's name. */
  public static UnitData unit(String name) {
    return records().unit(name);
  }

  /** A troop card by its row's name. */
  public static DeployCard card(String name) {
    return records().card(name);
  }
}
