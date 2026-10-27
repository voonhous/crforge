/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayCapture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped example replay ({@code examples/replays/ladder.json}) is the battle {@link
 * ExampleReplay} plays on the configured tables, and the viewer plays it in full. The file is a
 * replay of one data version; on the tables of another version the checks are skipped.
 */
class ExampleReplayTest {

  private static Path file;

  private static GameTables tables;

  @BeforeAll
  static void read() {
    String configured = System.getProperty("crforge.exampleReplay");
    assertThat(configured).as("the build names the example replay's file").isNotBlank();
    file = Paths.get(configured);
    tables = Replays.tables();
  }

  /** Skips a check on tables of a data version other than the one the file names. */
  private static void assumeFileVersion() throws IOException {
    JsonNode document = new ObjectMapper().readTree(file.toFile());
    String version =
        document.path(ReplayCapture.FIELD).path(ReplayCapture.CONTENT_VERSION).asText();
    assumeTrue(
        version.equals(tables.version()),
        "the example replay is of data version " + version + ", the tables of " + tables.version());
  }

  @Test
  @DisplayName("the shipped example is the battle the script plays, as ExampleReplay writes it")
  void theShippedFileIsTheScriptedBattle() throws IOException {
    assumeFileVersion();

    String played = ExampleReplay.text(ExampleReplay.play(tables));

    assertThat(Files.readString(file))
        .as("examples/replays/ladder.json; rewrite it with ./gradlew :desktop:exampleReplay")
        .isEqualTo(played);
  }

  @Test
  @DisplayName("the viewer reads the shipped example as a playable replay of its data version")
  void theViewerPlaysTheShippedFile() throws IOException {
    assumeFileVersion();

    ReplayFile replay = ReplayFile.read(file, tables);

    assertThat(replay.refusals()).isEmpty();
    assertThat(replay.playable()).isTrue();
    assertThat(replay.dataNamed()).isTrue();
    assertThat(replay.header().gameMode()).isEqualTo("Ladder");
  }
}
