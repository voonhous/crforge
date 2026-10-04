package org.crforge.parity;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;

/**
 * The game tables of data version 16.402.18 for the tests: the configured tables when they are of
 * that version, else a folder of them beside the configured ones, as in a checkout of the game data
 * repository. A test that needs them is skipped without them.
 */
final class Version16Tables {

  /** The data version. */
  static final String VERSION = "16.402.18";

  /** The tables, loaded once. */
  private static GameTables tables;

  private Version16Tables() {
    // Utility class
  }

  /**
   * The tables, or the calling test skipped when there are none.
   *
   * @return the tables of 16.402.18
   */
  static synchronized GameTables load() {
    if (tables == null) {
      Optional<Path> folder = folder();
      assumeTrue(folder.isPresent(), "no game tables of " + VERSION + " configured");
      tables = GameTables.load(folder.get());
    }
    return tables;
  }

  /** The configured folder when it is of the version, else a folder of the version beside it. */
  private static Optional<Path> folder() {
    Optional<Path> configured = GameTables.configuredDirectory();
    if (configured.isEmpty()) {
      return Optional.empty();
    }
    Path folder = configured.get().toAbsolutePath();
    if (folder.getFileName().toString().equals(VERSION)) {
      return Optional.of(folder);
    }
    Path beside = folder.resolveSibling(VERSION);
    return Files.isDirectory(beside) ? Optional.of(beside) : Optional.empty();
  }
}
