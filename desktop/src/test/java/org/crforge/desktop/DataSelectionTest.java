/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the visualizer picks its game tables: the one way, the data version's folder in the folder
 * the build makes the tables in, and the versions beside it.
 */
class DataSelectionTest {

  @TempDir Path workspace;

  private static void tables(Path root, String version) throws IOException {
    Path folder = Files.createDirectories(root.resolve(version));
    Files.writeString(folder.resolve("globals.json"), "{\"version\": \"" + version + "\"}\n");
  }

  @Test
  @DisplayName("the versions are the root's folders that hold tables, in version order")
  void listsTheVersions() throws IOException {
    Path root = Files.createDirectories(workspace.resolve("root"));
    tables(root, GameVersions.DATA_16_402_18);
    tables(root, "9.1.0");
    tables(root, GameVersions.DATA_14_593_1);
    Files.createDirectories(root.resolve("empty"));
    Files.createDirectories(root.resolve(".hidden"));
    Files.writeString(root.resolve(".hidden").resolve("x.json"), "{}");
    Files.writeString(root.resolve("README.md"), "readme");

    assertThat(DataSelection.versions(root))
        .containsExactly("9.1.0", GameVersions.DATA_14_593_1, GameVersions.DATA_16_402_18);
    assertThat(DataSelection.versions(workspace.resolve("missing"))).isEmpty();
  }

  @Test
  @DisplayName("the tables are the data version's folder in the root, with the lock's version kept")
  void choosesTheVersionFolder() {
    DataSelection.Choice choice =
        DataSelection.choose(
            workspace.toString(), GameVersions.DATA_16_402_18, GameVersions.DATA_16_402_19);

    assertThat(choice.problem()).isNull();
    assertThat(choice.folder()).isEqualTo(workspace.resolve(GameVersions.DATA_16_402_18));
    assertThat(choice.version()).isEqualTo(GameVersions.DATA_16_402_18);
    assertThat(choice.lockVersion()).isEqualTo(GameVersions.DATA_16_402_19);
  }

  @Test
  @DisplayName("without the root or the version nothing is chosen, and the message says how to run")
  void nothingToChoose() {
    for (DataSelection.Choice choice :
        new DataSelection.Choice[] {
          DataSelection.choose(null, GameVersions.DATA_16_402_18, null),
          DataSelection.choose(workspace.toString(), " ", null)
        }) {
      assertThat(choice.root()).isNull();
      assertThat(choice.problem())
          .contains(DataSelection.TABLES_ROOT_PROPERTY)
          .contains(DataSelection.DATA_VERSION_PROPERTY)
          .contains(GameTables.ASSET_SOURCE_PROPERTY)
          .contains("./gradlew :desktop:run");
    }
  }
}
