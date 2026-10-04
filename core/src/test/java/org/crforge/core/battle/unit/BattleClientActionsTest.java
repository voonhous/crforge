package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A unit's OnStartingClientActions names rows of the client's own action table, its health bar
 * parts and visual layers, which only the unit's view runs: the battle builds and plays the unit as
 * it does without the column.
 */
class BattleClientActionsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The tables with the row of a characters or buildings table naming client actions. */
  private static GameTables withClientActions(Path folder, String table, String row)
      throws IOException {
    ArrayNode actions = JsonNodeFactory.instance.arrayNode().add("Healthbar_Medium");
    return GameData.altered(
        folder, table, rows -> GameData.columns(rows, row).set("OnStartingClientActions", actions));
  }

  @Test
  @DisplayName("a character naming client actions is built and played as one without them")
  void aCharacterNamingClientActionsPlays(@TempDir Path folder) throws IOException {
    GameTables tables = withClientActions(folder, "characters", "DarkPrince");
    BattleRecords records = new BattleRecords(tables);
    assertThat(records.unit("DarkPrince")).isEqualTo(GameData.unit("DarkPrince"));

    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(25, records.unit("DarkPrince"), LEVEL, 0, 3500, 11000, "DarkPrince");
    Standard1v1Battle plain = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    plain.deploy(25, GameData.unit("DarkPrince"), LEVEL, 0, 3500, 11000, "DarkPrince");
    for (int tick = 0; tick < 300; tick++) {
      match.getBattle().step();
      plain.getBattle().step();
    }
    assertThat(view(match)).isEqualTo(view(plain));
  }

  @Test
  @DisplayName("a building naming client actions is built as one without them")
  void aBuildingNamingClientActionsBuilds(@TempDir Path folder) throws IOException {
    BattleRecords records = new BattleRecords(withClientActions(folder, "buildings", "Tombstone"));
    assertThat(records.unit("Tombstone")).isEqualTo(GameData.unit("Tombstone"));
  }

  /** Every entity's name, position and hit points, in the holder's order. */
  private static String view(Standard1v1Battle match) {
    StringBuilder out = new StringBuilder();
    match
        .getBattle()
        .getHolder()
        .entities()
        .forEach(
            e ->
                out.append(e.getClass().getSimpleName())
                    .append(' ')
                    .append(e.getId())
                    .append(' ')
                    .append(
                        e instanceof WorldEntity w
                            ? w.name()
                                + " "
                                + w.getView().getX()
                                + ","
                                + w.getView().getY()
                                + " hp "
                                + (w.getHitPoints() == null ? "-" : w.getHitPoints().getHitPoints())
                            : "")
                    .append('\n'));
    return out.toString();
  }
}
