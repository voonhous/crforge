package org.crforge.desktop.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.TableCopies;

/**
 * The replay viewer tests' inputs: the configured game tables, a copy of them the battle core
 * refuses, and the synthetic replay fixture.
 */
final class Replays {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The fixture: side 0 plays its Archer Queen on tick 220 and taps her ability on tick 350. */
  static final String ARCHER_QUEEN = "/replays/archer_queen_ability.json";

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

  /** The fixture as a document, to change in a test. */
  static ObjectNode archerQueen() {
    try (InputStream in = Replays.class.getResourceAsStream(ARCHER_QUEEN)) {
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
