package org.crforge.core.battle;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;

/**
 * The game tables of a data version of game client 16.402.17 for the tests, 16.402.18 unless named:
 * the configured tables when they are of that version, else a folder of them beside the configured
 * ones, as in a checkout of the game data repository. A test that needs them is skipped without
 * them.
 */
public final class Version16Tables {

  /** The data version the tests read unless they name another. */
  public static final String VERSION = GameVersions.DATA_16_402_18;

  /** The data version client 16.402.17 runs since 2026-10-06. */
  public static final String VERSION_16_402_19 = GameVersions.DATA_16_402_19;

  /** The tables of each version, each loaded once. */
  private static final Map<String, GameTables> TABLES = new HashMap<>();

  private Version16Tables() {
    // Utility class
  }

  /**
   * The tables, or the calling test skipped when there are none.
   *
   * @return the tables of 16.402.18
   */
  public static GameTables load() {
    return load(VERSION);
  }

  /**
   * The tables of a data version, or the calling test skipped when there are none.
   *
   * @param version the data version
   * @return its tables
   */
  public static synchronized GameTables load(String version) {
    GameTables tables = TABLES.get(version);
    if (tables == null) {
      Optional<Path> folder = folder(version);
      assumeTrue(folder.isPresent(), "no game tables of " + version + " configured");
      tables = GameTables.load(folder.get());
      TABLES.put(version, tables);
    }
    return tables;
  }

  /** The configured folder when it is of the version, else a folder of the version beside it. */
  private static Optional<Path> folder(String version) {
    Optional<Path> configured = GameTables.configuredDirectory();
    if (configured.isEmpty()) {
      return Optional.empty();
    }
    Path folder = configured.get().toAbsolutePath();
    if (folder.getFileName().toString().equals(version)) {
      return Optional.of(folder);
    }
    Path beside = folder.resolveSibling(version);
    return Files.isDirectory(beside) ? Optional.of(beside) : Optional.empty();
  }
}
