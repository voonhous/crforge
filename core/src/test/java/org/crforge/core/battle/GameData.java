package org.crforge.core.battle;

import org.crforge.core.battle.data.ActionRows;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.unit.UnitData;

/**
 * The battle's records, built from the configured game tables, for the tests that play units and
 * cards. The tables are required: without them these tests fail, naming the setting.
 */
public final class GameData {

  private static GameTables tables;
  private static BattleRecords records;
  private static ActionRows actions;

  private GameData() {
    // Utility class
  }

  /** The configured tables, loaded once. */
  public static synchronized GameTables tables() {
    if (tables == null) {
      tables = GameTables.loadConfigured();
    }
    return tables;
  }

  /** The records of the configured tables. */
  public static synchronized BattleRecords records() {
    if (records == null) {
      records = new BattleRecords(tables());
    }
    return records;
  }

  /** The action rows of the configured tables. */
  public static synchronized ActionRows actions() {
    if (actions == null) {
      actions = new ActionRows(tables(), records());
    }
    return actions;
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
