/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The battle models the rules of one game client: it plays tables of the data versions that client
 * runs and refuses any other, and its rules hold before any tables are loaded.
 */
class BattleDataVersionTest {

  /** The configured tables copied into a folder with every file's header naming a data version. */
  private static GameTables labelled(Path folder, String version) throws IOException {
    Path source = GameTables.configuredDirectory().orElseThrow();
    ObjectMapper mapper = new ObjectMapper();
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
        document.put(GameTables.VERSION_FIELD, version);
        mapper.writeValue(folder.resolve(file.getFileName()).toFile(), document);
      }
    }
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "tables of a data version the modelled client has not run are refused as the battle loads"
          + " them, naming the version")
  void anotherDataVersionIsRefused(@TempDir Path folder) throws IOException {
    GameTables older = labelled(folder, "14.593.1");

    assertThatThrownBy(() -> new Standard1v1Battle(older))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("game client " + GameVersions.CLIENT_16_402_17)
        .hasMessageContaining("does not play tables of data version 14.593.1");
  }

  @Test
  @DisplayName("tables of each data version the modelled client runs are played")
  void theClientsDataVersionsArePlayed(@TempDir Path folder) throws IOException {
    for (String version : GameVersions.CLIENT_16_402_17_DATA) {
      Path dir = Files.createDirectories(folder.resolve(version));
      Standard1v1Battle battle = new Standard1v1Battle(labelled(dir, version));
      battle.getBattle().step();
      assertThat(battle.getWorld().getRecords()).isNotNull();
    }
    assertThat(GameData.tables().version()).isIn(GameVersions.CLIENT_16_402_17_DATA);
  }

  @Test
  @DisplayName(
      "a battle world drops a unit's route at a pushback's end before any tables are loaded")
  void thePushbackRuleNeedsNoTables() {
    BattleWorld world = new BattleWorld(TileMap.standard1v1());
    assertThat(world.getMovementGlobals().pushbackEndDropsRoute()).isTrue();
  }
}
