package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.battle.TableCopies;
import org.crforge.desktop.replay.ReplayFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher's game tables: what it prints about the tables it loaded and the versions built
 * beside them, and the message it stops with when there are none or the battle core refuses a
 * battle on them; and the replay it is given to open.
 */
class DesktopLauncherTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  @Test
  @DisplayName("with no tables given the launcher loads nothing and says how to run it")
  void missingTablesFailWithAMessage() {
    GameTables tables =
        DesktopLauncher.loadTables(DataSelection.choose(null, null, null), out, err);

    assertThat(tables).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("No game tables")
        .contains(DataSelection.TABLES_ROOT_PROPERTY)
        .contains(GameTables.ASSET_SOURCE_PROPERTY);
    assertThat(outBytes.size()).isZero();
  }

  @Test
  @DisplayName("a data version that was not built is refused, listing the versions that were")
  void aMissingVersionListsTheBuiltOnes(@TempDir Path root) throws IOException {
    TableCopies.copy(root, "1.0.0");

    GameTables tables =
        DesktopLauncher.loadTables(DataSelection.choose(root.toString(), "9.9.9", null), out, err);

    assertThat(tables).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("Cannot read the game tables of data version 9.9.9 at")
        .contains(root.resolve("9.9.9").toAbsolutePath().normalize().toString())
        .contains("data versions built: 1.0.0");
  }

  @Test
  @DisplayName("the tables are loaded and their folder, version, content sha and siblings printed")
  void tablesArePrinted(@TempDir Path root) throws IOException {
    Path folder = TableCopies.copy(root, "1.0.0");

    GameTables tables =
        DesktopLauncher.loadTables(DataSelection.choose(root.toString(), "1.0.0", null), out, err);

    assertThat(tables).isNotNull();
    assertThat(outBytes.toString(StandardCharsets.UTF_8).lines().toList())
        .containsExactly(
            "game tables: " + folder.toAbsolutePath().normalize(),
            "data version: " + tables.version(),
            "content sha: " + tables.contentSha(),
            "data versions: 1.0.0 (V switches)");
    assertThat(errBytes.size()).isZero();
  }

  @Test
  @DisplayName("tables whose battle the battle core refuses stop the launcher with the reason")
  void aRefusedFirstBattleStops(@TempDir Path root) throws IOException {
    Path folder = TableCopies.refused(root, "2.0.0");

    BattleSession session = DesktopLauncher.firstSession(versions(root, "2.0.0", folder), err);

    assertThat(session).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("The battle core refuses a battle on data version")
        .contains("sets Tid, which is not modelled")
        .contains("-P" + DataSelection.DATA_VERSION_PROPERTY);
  }

  @Test
  @DisplayName(
      "tables of a data version the battle core does not model stop the launcher, naming the"
          + " version")
  void anUnmodelledDataVersionStops(@TempDir Path root) throws IOException {
    Path folder = TableCopies.labelled(root, "1.0.0", "14.593.1");

    BattleSession session = DesktopLauncher.firstSession(versions(root, "1.0.0", folder), err);

    assertThat(session).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("The battle core refuses a battle on data version 14.593.1")
        .contains("does not play tables of data version 14.593.1")
        .contains("-P" + DataSelection.DATA_VERSION_PROPERTY);
  }

  @Test
  @DisplayName("tables the battle core starts a battle on give the screen its first session")
  void aFirstBattle() {
    Path folder = GameTables.configuredDirectory().orElseThrow();
    DataVersions versions = versions(folder.getParent(), folder.getFileName().toString(), folder);

    BattleSession session = DesktopLauncher.firstSession(versions, err);

    assertThat(session).isNotNull();
    assertThat(errBytes.size()).isZero();
  }

  /** The versions of a root holding one version, on that version's tables. */
  private static DataVersions versions(Path root, String version, Path folder) {
    return new DataVersions(
        root, List.of(version), folder, GameTables.load(folder), "test", "unknown");
  }

  @Test
  @DisplayName("--replay names the replay file, and without a file it is refused")
  void theReplayArgument() {
    assertThat(DesktopLauncher.replayArgument(new String[] {})).isEmpty();
    assertThat(DesktopLauncher.replayArgument(new String[] {"--replay", "/r/replay.json"}))
        .contains(Path.of("/r/replay.json"));
    assertThatThrownBy(() -> DesktopLauncher.replayArgument(new String[] {"--replay"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("--replay names no replay file");
  }

  @Test
  @DisplayName("a replay is printed with its header and what its mapping refused")
  void aReplayIsPrinted(@TempDir Path folder) throws IOException {
    Path file = folder.resolve("replay.json");
    try (InputStream in = getClass().getResourceAsStream("/replays/archer_queen_ability.json")) {
      Files.copy(in, file);
    }
    GameTables tables = GameTables.loadConfigured();

    ReplayFile replay = DesktopLauncher.loadReplay(file, tables, out, err);

    assertThat(replay).isNotNull();
    assertThat(outBytes.toString(StandardCharsets.UTF_8).lines().toList())
        .isEqualTo(replay.describe());
    assertThat(errBytes.size()).isZero();
  }

  @Test
  @DisplayName("a replay file that cannot be read is refused with a message naming it")
  void anUnreadableReplay(@TempDir Path folder) {
    Path missing = folder.resolve("nowhere.json");

    ReplayFile replay = DesktopLauncher.loadReplay(missing, GameTables.loadConfigured(), out, err);

    assertThat(replay).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .startsWith("Cannot read the replay at " + missing.toAbsolutePath().normalize());
    assertThat(outBytes.size()).isZero();
  }
}
