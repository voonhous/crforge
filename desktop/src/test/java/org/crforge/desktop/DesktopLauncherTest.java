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
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.battle.TableCopies;
import org.crforge.desktop.replay.ReplayFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher's game tables: which setting names the folder, what it prints about the tables it
 * loaded and the data root they came from, and the message it stops with when there are none or the
 * battle core refuses a battle on them; and the replay it is given to open.
 */
class DesktopLauncherTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  @Test
  @DisplayName("the system property names the folder ahead of the environment variable")
  void thePropertyWins() {
    Optional<GameTablesSetting.Configured> configured =
        GameTablesSetting.resolve("/tables/a", "/tables/b");

    assertThat(configured).isPresent();
    assertThat(configured.get().folder()).isEqualTo(Path.of("/tables/a"));
    assertThat(configured.get().source()).isEqualTo(GameTables.PROPERTY);
  }

  @Test
  @DisplayName("the environment variable names the folder when the property is missing or blank")
  void theVariableWhenNoProperty() {
    for (String property : new String[] {null, "", "  "}) {
      Optional<GameTablesSetting.Configured> configured =
          GameTablesSetting.resolve(property, "/tables/b");

      assertThat(configured).isPresent();
      assertThat(configured.get().folder()).isEqualTo(Path.of("/tables/b"));
      assertThat(configured.get().source()).isEqualTo(GameTables.ENVIRONMENT);
    }
  }

  @Test
  @DisplayName("neither setting, or both blank, configures nothing")
  void nothingConfigured() {
    assertThat(GameTablesSetting.resolve(null, null)).isEmpty();
    assertThat(GameTablesSetting.resolve("", " ")).isEmpty();
  }

  @Test
  @DisplayName("with nothing configured the launcher loads nothing and names all four settings")
  void missingTablesFailWithAMessage() {
    DataSelection.Choice nothing =
        DataSelection.choose(new DataSelection.Settings(null, null, null, null, null, null, null));

    GameTables tables = DesktopLauncher.loadTables(nothing, out, err);

    assertThat(tables).isNull();
    String message = errBytes.toString(StandardCharsets.UTF_8);
    assertThat(message)
        .contains("No game tables configured")
        .contains(DataSelection.DATA_ROOT_PROPERTY)
        .contains(DataSelection.DATA_ROOT_ENVIRONMENT)
        .contains(GameTables.PROPERTY)
        .contains(GameTables.ENVIRONMENT);
    assertThat(outBytes.size()).isZero();
  }

  @Test
  @DisplayName("a configured folder that holds no tables is refused with a message naming it")
  void unreadableTablesFailWithAMessage(@TempDir Path empty) {
    Path missing = empty.resolve("nowhere");
    GameTables tables =
        DesktopLauncher.loadTables(
            choice(null, new GameTablesSetting.Configured(missing, GameTables.PROPERTY)), out, err);

    assertThat(tables).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("Cannot read the game tables at")
        .contains(missing.toString())
        .contains(GameTables.PROPERTY);
  }

  @Test
  @DisplayName("a version missing from the data root is refused, listing the root's versions")
  void aMissingVersionListsTheRoot(@TempDir Path root) throws IOException {
    TableCopies.copy(root, "1.0.0");
    DataSelection.Choice choice =
        DataSelection.choose(
            new DataSelection.Settings("9.9.9", null, root.toString(), null, null, null, null));

    GameTables tables = DesktopLauncher.loadTables(choice, out, err);

    assertThat(tables).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("Cannot read the game tables at")
        .contains(root.resolve("9.9.9").toString())
        .contains("--data-version 9.9.9 in the data root")
        .contains("data versions in " + root.toAbsolutePath().normalize() + ": 1.0.0");
  }

  @Test
  @DisplayName("the configured tables are loaded and their folder, version and content sha printed")
  void configuredTablesArePrinted() {
    GameTablesSetting.Configured configured = GameTablesSetting.resolve().orElseThrow();

    GameTables tables = DesktopLauncher.loadTables(choice(null, configured), out, err);

    assertThat(tables).isNotNull();
    String printed = outBytes.toString(StandardCharsets.UTF_8);
    assertThat(printed.lines().toList())
        .containsExactly(
            "game tables: "
                + configured.folder().toAbsolutePath().normalize()
                + " (from "
                + configured.source()
                + ")",
            "data version: " + tables.version(),
            "content sha: " + tables.contentSha());
    assertThat(tables.version()).isNotBlank();
    assertThat(tables.contentSha()).isNotBlank();
    assertThat(errBytes.size()).isZero();
  }

  @Test
  @DisplayName("tables picked from a data root are printed after the root's lines")
  void rootLinesFirst(@TempDir Path root) throws IOException {
    Path folder = TableCopies.copy(root, "1.0.0");
    DataSelection.Choice choice =
        DataSelection.choose(
            new DataSelection.Settings("1.0.0", null, root.toString(), null, null, null, null));

    GameTables tables = DesktopLauncher.loadTables(choice, out, err);

    assertThat(tables).isNotNull();
    List<String> printed = outBytes.toString(StandardCharsets.UTF_8).lines().toList();
    assertThat(printed)
        .containsExactly(
            "data root: "
                + root.toAbsolutePath().normalize()
                + " (from "
                + DataSelection.DATA_ROOT_PROPERTY
                + ")",
            "data root commit: unknown (no git checkout read)",
            "data versions: 1.0.0 (V switches)",
            "game tables: "
                + folder.toAbsolutePath().normalize()
                + " (from --data-version 1.0.0 in the data root)",
            "data version: " + tables.version(),
            "content sha: " + tables.contentSha());
  }

  @Test
  @DisplayName("tables whose battle the battle core refuses stop the launcher with the reason")
  void aRefusedFirstBattleStops(@TempDir Path root) throws IOException {
    Path folder = TableCopies.refused(root, "2.0.0");
    DataVersions versions =
        new DataVersions(root, List.of("2.0.0"), folder, GameTables.load(folder));

    BattleSession session = DesktopLauncher.firstSession(versions, err);

    assertThat(session).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("The battle core refuses a battle on data version")
        .contains("sets Tid, which is not modelled")
        .contains("--data-version");
  }

  @Test
  @DisplayName(
      "tables of a data version the battle core does not model stop the launcher, naming the"
          + " version")
  void anUnmodelledDataVersionStops(@TempDir Path root) throws IOException {
    Path folder = TableCopies.labelled(root, "1.0.0", "14.593.1");
    DataVersions versions =
        new DataVersions(root, List.of("1.0.0"), folder, GameTables.load(folder));

    BattleSession session = DesktopLauncher.firstSession(versions, err);

    assertThat(session).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("The battle core refuses a battle on data version 14.593.1")
        .contains("does not play tables of data version 14.593.1")
        .contains("--data-version");
  }

  @Test
  @DisplayName("tables the battle core starts a battle on give the screen its first session")
  void aFirstBattle() {
    Path folder = GameTables.configuredDirectory().orElseThrow();
    DataVersions versions = new DataVersions(null, List.of(), folder, GameTables.load(folder));

    BattleSession session = DesktopLauncher.firstSession(versions, err);

    assertThat(session).isNotNull();
    assertThat(errBytes.size()).isZero();
  }

  private static DataSelection.Choice choice(
      DataSelection.DataRoot root, GameTablesSetting.Configured tables) {
    return new DataSelection.Choice(root, null, tables, null);
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
