package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher's game tables: which setting names the folder, what it prints about the tables it
 * loaded, and the message it stops with when there are none.
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
  @DisplayName("with nothing configured the launcher loads nothing and names both settings")
  void missingTablesFailWithAMessage() {
    GameTables tables = DesktopLauncher.loadTables(Optional.empty(), out, err);

    assertThat(tables).isNull();
    String message = errBytes.toString(StandardCharsets.UTF_8);
    assertThat(message)
        .contains("No game tables configured")
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
            Optional.of(new GameTablesSetting.Configured(missing, GameTables.PROPERTY)), out, err);

    assertThat(tables).isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("Cannot read the game tables at")
        .contains(missing.toString())
        .contains(GameTables.PROPERTY);
  }

  @Test
  @DisplayName("the configured tables are loaded and their folder, version and content sha printed")
  void configuredTablesArePrinted() {
    GameTablesSetting.Configured configured = GameTablesSetting.resolve().orElseThrow();

    GameTables tables = DesktopLauncher.loadTables(Optional.of(configured), out, err);

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
}
