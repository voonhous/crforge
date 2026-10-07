package org.crforge.desktop.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The screen's switch between the data versions of a data root: the next version's battle, a
 * version the battle core refuses at the start of a battle, and a version whose tables cannot be
 * read. Each version folder of the test's root is a copy of the configured tables; the refused one
 * has a variables row that sets a start value, which the battle core does not model.
 */
class DataVersionsTest {

  @TempDir Path root;

  private Path good;
  private Path other;

  @BeforeEach
  void copyTables() throws IOException {
    good = copy("1.0.0");
    other = copy("3.0.0");
  }

  private Path copy(String version) throws IOException {
    return TableCopies.copy(root, version);
  }

  private Path refused(String version) throws IOException {
    return TableCopies.refused(root, version);
  }

  private DataVersions versions(Path current, List<String> names) {
    return new DataVersions(root, names, current, GameTables.load(current));
  }

  @Test
  void explicitSelectionRetainsLoadedProvenanceUntilItSucceeds() throws IOException {
    refused("2.0.0");
    GameTables loaded = GameTables.load(good);
    DataVersions versions =
        new DataVersions(
            root,
            List.of("1.0.0", "2.0.0", "3.0.0"),
            good,
            loaded,
            "crforge.gameTables",
            GameVersions.DATA_16_402_18);
    assertThat(versions.select("missing").session()).isNull();
    assertThat(versions.select("2.0.0").session()).isNull();
    assertThat(versions.current()).isSameAs(loaded);
    assertThat(versions.source()).isEqualTo("crforge.gameTables");
    assertThat(versions.developmentVersion()).isEqualTo(GameVersions.DATA_16_402_18);
    assertThat(versions.select("3.0.0").session()).isNotNull();
    assertThat(versions.currentFolder()).isEqualTo(other);
    assertThat(versions.source()).isEqualTo("selected from the data root");
    assertThat(versions.developmentVersion()).isEqualTo(GameVersions.DATA_16_402_18);
    assertThat(versions.next().version()).isEqualTo("1.0.0");
  }

  @Test
  @DisplayName("V starts a new battle on the next version's tables, and wraps to the first")
  void switchesToTheNextVersion() {
    DataVersions versions = versions(good, List.of("1.0.0", "3.0.0"));
    GameTables first = versions.current();

    DataVersions.Switched switched = versions.next();

    assertThat(switched.refusal()).isNull();
    assertThat(switched.version()).isEqualTo("3.0.0");
    assertThat(switched.session()).isNotNull();
    assertThat(switched.session().step()).isTrue();
    assertThat(versions.currentFolder()).isEqualTo(other);
    assertThat(versions.current()).isNotSameAs(first);

    DataVersions.Switched back = versions.next();
    assertThat(back.version()).isEqualTo("1.0.0");
    assertThat(versions.currentFolder()).isEqualTo(good);
    // Loaded once and kept: the first version's tables come back as the same object.
    assertThat(versions.current()).isSameAs(first);
  }

  @Test
  @DisplayName("a version the battle core refuses at a battle's start is reported, not switched to")
  void aRefusedVersionStaysOff() throws IOException {
    refused("2.0.0");
    DataVersions versions = versions(good, List.of("1.0.0", "2.0.0", "3.0.0"));
    GameTables first = versions.current();
    String source = versions.source();
    String hash = versions.current().contentSha();

    DataVersions.Switched switched = versions.next();

    assertThat(switched.session()).isNull();
    assertThat(switched.version()).isEqualTo("2.0.0");
    assertThat(switched.refusal())
        .contains("data version 2.0.0")
        .contains("refuses a battle")
        .contains("sets Tid, which is not modelled")
        .contains("V tries the next")
        .contains("R resets on " + first.version());
    assertThat(versions.current()).isSameAs(first);
    assertThat(versions.currentFolder()).isEqualTo(good);
    assertThat(versions.source()).isEqualTo(source);
    assertThat(versions.current().contentSha()).isEqualTo(hash);
    // The ladder of the version still on works, which is what R starts.
    assertThat(versions.ladder().step()).isTrue();

    // V again moves past the refused version.
    DataVersions.Switched next = versions.next();
    assertThat(next.refusal()).isNull();
    assertThat(next.version()).isEqualTo("3.0.0");
    assertThat(versions.currentFolder()).isEqualTo(other);
    assertThat(versions.source()).isEqualTo("selected from the data root");
  }

  @Test
  @DisplayName("a version whose tables cannot be read is reported, not switched to")
  void anUnreadableVersionStaysOff() throws IOException {
    Path broken = Files.createDirectories(root.resolve("2.0.0"));
    Files.writeString(broken.resolve("globals.json"), "{ not json");
    DataVersions versions = versions(good, List.of("1.0.0", "2.0.0", "3.0.0"));

    DataVersions.Switched switched = versions.next();

    assertThat(switched.session()).isNull();
    assertThat(switched.refusal()).contains("cannot read the tables of data version 2.0.0");
    assertThat(versions.currentFolder()).isEqualTo(good);
    assertThat(versions.next().version()).isEqualTo("3.0.0");
  }

  @Test
  @DisplayName("tables from outside the root start the cycle at the root folder of their version")
  void outsideTheRootByVersionName() throws IOException {
    GameTables tables = GameTables.load(good);
    Path named = copy(tables.version());
    DataVersions versions =
        new DataVersions(root, List.of("1.0.0", tables.version(), "3.0.0"), good, tables);

    // The cursor is on the folder of the same path first, so the next one is the version folder.
    assertThat(versions.next().version()).isEqualTo(tables.version());
    assertThat(versions.currentFolder()).isEqualTo(named);

    Path outside = root.resolveSibling(root.getFileName() + "-outside");
    DataVersions byName =
        new DataVersions(root, List.of("1.0.0", tables.version(), "3.0.0"), outside, tables);
    assertThat(byName.next().version()).isEqualTo("3.0.0");
  }

  @Test
  @DisplayName("with no data root V says so and changes nothing")
  void noRoot() {
    GameTables tables = GameTables.load(good);
    DataVersions versions = new DataVersions(null, List.of(), good, tables);

    DataVersions.Switched switched = versions.next();

    assertThat(switched.session()).isNull();
    assertThat(switched.refusal()).contains("crforge.dataRoot").contains("CRFORGE_DATA_ROOT");
    assertThat(versions.current()).isSameAs(tables);
  }

  @Test
  @DisplayName("the status line names the current data version and how many V cycles through")
  void statusLine() {
    DataVersions versions = versions(good, List.of("1.0.0", "3.0.0"));

    assertThat(versions.statusLine())
        .isEqualTo("data: " + versions.current().version() + " (V: 2 versions)");
    assertThat(new DataVersions(null, List.of(), good, versions.current()).statusLine())
        .isEqualTo("data: " + versions.current().version() + " (V: no data root)");
  }
}
