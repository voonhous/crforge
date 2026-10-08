package org.crforge.core.battle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;
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

  /**
   * The configured tables copied into a folder with one table's rows altered, for a test that needs
   * a row no table ships.
   *
   * @param folder the folder to copy them into
   * @param table the table's file name, without its extension
   * @param edit what is done to its rows, by name; the actions table keeps them under its own name
   * @return the altered tables
   */
  public static GameTables altered(Path folder, String table, Consumer<ObjectNode> edit)
      throws IOException {
    copyConfigured(folder);
    Path file = folder.resolve(table + ".json");
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    edit.accept((ObjectNode) document.get(document.has("rows") ? "rows" : table));
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }

  /**
   * Alters one more table of a folder the configured tables were already copied into by {@link
   * #altered}; load the folder again to read the change.
   *
   * @param folder the folder
   * @param table the table's file name, without its extension
   * @param edit what is done to its rows, by name; the actions table keeps them under its own name
   */
  public static void alterLoaded(Path folder, String table, Consumer<ObjectNode> edit)
      throws IOException {
    Path file = folder.resolve(table + ".json");
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    edit.accept((ObjectNode) document.get(document.has("rows") ? "rows" : table));
    mapper.writeValue(file.toFile(), document);
  }

  /** The variable the tests' hand-written action rows write; no configured table declares it. */
  public static final String TEST_VARIABLE = "TestVariable";

  /**
   * Declares {@link #TEST_VARIABLE} in the variables table of a folder the configured tables were
   * already copied into, after every configured variable; load the folder again to read it.
   *
   * @param folder the folder
   */
  public static void addTestVariable(Path folder) throws IOException {
    alterLoaded(
        folder,
        "variables",
        rows -> {
          int index = rows.size();
          ObjectNode row = rows.putObject(TEST_VARIABLE);
          row.put("index", index);
          row.put("class", "LogicVariableData");
          row.putObject("columns");
        });
  }

  /** The columns of a row in the rows of a table, to alter. */
  public static ObjectNode columns(ObjectNode rows, String row) {
    return (ObjectNode) rows.get(row).get("columns");
  }

  /** Copies every file of the configured tables into a folder. */
  private static void copyConfigured(Path folder) throws IOException {
    Path source = GameTables.configuredDirectory().orElseThrow();
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.toList()) {
        Files.copy(file, folder.resolve(file.getFileName()));
      }
    }
  }
}
