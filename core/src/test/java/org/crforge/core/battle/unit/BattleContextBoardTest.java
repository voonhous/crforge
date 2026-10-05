package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version's two board actions, built from their rows: a group that creates a context
 * writes a unit's hit points under a key of the scratch board (ActionBlackboardSetInt, as the
 * Tombstone hero writes its own) and copies that key into one of the unit's variables
 * (ActionContextToVariable).
 */
class BattleContextBoardTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String VARIABLE = "TestVariable";

  /** The configured tables with the Knight starting the two board actions in a new context. */
  private static GameTables withBoard(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode group = row(rows, "Board_group", "LogicActionGroupData", "ActionGroup");
          group.put("ContextMode", "Create");
          ArrayNode parts = group.putArray("SubActions");
          parts.addObject().put("action", "Board_write");
          parts.addObject().put("action", "Board_copy");
          // The copy one step after the write: two parts due together start last first.
          group.putArray("SubActionsDelay").add(0).add(50);
          ObjectNode write =
              row(rows, "Board_write", "LogicActionBlackboardSetIntData", "ActionBlackboardSetInt");
          write.put("Key", "hp_copy");
          write.put("Value", "hp * 2");
          write.put("UseScratch", true);
          ObjectNode copy =
              row(
                  rows,
                  "Board_copy",
                  "LogicActionContextToVariableData",
                  "ActionContextToVariable");
          copy.put("BlackboardKey", "hp_copy");
          copy.put("OutputVariable", VARIABLE);
          copy.put("UseScratch", true);
        });
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve("characters.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    GameData.columns((ObjectNode) document.get("rows"), "Knight")
        .put("OnStartingAction", "Board_group");
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }

  /** A new action row of a class, answering its fields. */
  private static ObjectNode row(ObjectNode rows, String name, String className, String classType) {
    ObjectNode row = rows.putObject(name);
    row.put("class", className);
    row.put("ClassType", classType);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", classType);
    return fields;
  }

  @Test
  @DisplayName(
      "the Knight's starting group writes twice its hit points into the scratch board and, a step"
          + " later, copies them into its variable")
  void theBoardIsWrittenAndCopied(@TempDir Path folder) throws IOException {
    GameTables tables = withBoard(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity knight =
        match.deploy(0, new BattleRecords(tables).unit("Knight"), LEVEL, 0, 9000, 10000);
    int key = match.getWorld().variableKey(VARIABLE);
    for (int i = 0; i < 4; i++) {
      match.getBattle().step();
    }

    assertThat(knight.variable(key)).isEqualTo(knight.getHitPoints().getHitPoints() * 2);
  }
}
