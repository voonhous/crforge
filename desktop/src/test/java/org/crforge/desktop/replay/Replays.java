package org.crforge.desktop.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.desktop.battle.TableCopies;

/**
 * The replay viewer tests' inputs: the configured game tables, a copy of them the battle core
 * refuses, and the synthetic replay fixture.
 */
final class Replays {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The fixture: side 0 plays its Archer Queen on tick 220 and taps her ability on tick 350. */
  static final String ARCHER_QUEEN = "/replays/archer_queen_ability.json";

  /**
   * The fixture as a replay of the game client whose data version is 16.402.18 writes it, with
   * made-up players: its command types 153 and 189, and the fields that version's replays write
   * beyond 14.593.1's.
   */
  static final String ARCHER_QUEEN_VERSION_16 = "/replays/archer_queen_version16.json";

  /** The data version of {@link #ARCHER_QUEEN_VERSION_16}. */
  static final String VERSION_16 = GameVersions.DATA_16_402_18;

  private static GameTables tables;

  private Replays() {
    // Utility class
  }

  /** The configured game tables, loaded once. */
  static synchronized GameTables tables() {
    if (tables == null) {
      tables = GameTables.loadConfigured();
    }
    return tables;
  }

  /**
   * The tables of {@link #VERSION_16}: the configured tables when they are of that version, else a
   * folder of them beside the configured ones, as in a checkout of the game data repository.
   *
   * @return the tables, or empty when there are none
   */
  static Optional<GameTables> version16Tables() {
    Optional<Path> configured = GameTables.configuredDirectory();
    if (configured.isEmpty()) {
      return Optional.empty();
    }
    Path folder = configured.get().toAbsolutePath();
    Path version16 =
        folder.getFileName().toString().equals(VERSION_16)
            ? folder
            : folder.resolveSibling(VERSION_16);
    return Files.isDirectory(version16)
        ? Optional.of(GameTables.load(version16))
        : Optional.empty();
  }

  /** The fixture as a document, to change in a test. */
  static ObjectNode archerQueen() {
    return read(ARCHER_QUEEN);
  }

  /** The fixture of version 16.402.18 as a document. */
  static ObjectNode archerQueenOfVersion16() {
    return read(ARCHER_QUEEN_VERSION_16);
  }

  private static ObjectNode read(String resource) {
    try (InputStream in = Replays.class.getResourceAsStream(resource)) {
      return (ObjectNode) MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes a document as a replay file in a folder. */
  static Path write(Path folder, String name, ObjectNode document) throws IOException {
    Path file = folder.resolve(name);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
    return file;
  }

  /**
   * A copy of the configured tables the battle core refuses ({@link TableCopies#refused}): a
   * variable row's Tid, a column the battle does not model.
   */
  static GameTables refusedTables(Path folder) throws IOException {
    return GameTables.load(TableCopies.refused(folder, "refused"));
  }
}
