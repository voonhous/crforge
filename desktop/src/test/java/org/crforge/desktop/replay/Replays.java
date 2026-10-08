package org.crforge.desktop.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.TableCopies;

/**
 * The replay viewer tests' inputs: the configured game tables, a copy of them the battle core
 * refuses, and the synthetic replay fixture.
 */
final class Replays {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The fixture: side 0 plays its Archer Queen on tick 220 and taps her ability on tick 350. It is
   * written in the shape every version's replays share, which {@link #archerQueen} completes into
   * one of the configured version.
   */
  static final String ARCHER_QUEEN = "/replays/archer_queen_ability.json";

  /** The command type of a card play in the configured version's replays. */
  static final int PLAY = 153;

  /** The command type of an ability command in the configured version's replays. */
  static final int ABILITY = 189;

  /**
   * The fixture as a replay of the configured version's game client writes it, with made-up
   * players: its command types 153 and 189, and every field its replays write beyond the shape all
   * versions share.
   */
  static final String ARCHER_QUEEN_EVERY_FIELD = "/replays/archer_queen_every_field.json";

  private Replays() {
    // Utility class
  }

  /** The configured game tables, loaded once. */
  static GameTables tables() {
    return GameData.tables();
  }

  /**
   * The fixture as a replay of the configured version writes it, with the least it must hold beyond
   * the shared shape: the request lists, the header's switches, each side's king level ({@code kt})
   * 1 in its player data, and the version's command types. A document to change in a test.
   */
  static ObjectNode archerQueen() {
    ObjectNode document = read(ARCHER_QUEEN);
    document.putArray("srq");
    document.putArray("srs");
    ObjectNode battle = (ObjectNode) document.path("battle");
    battle.put("cardlvlmin", 0);
    battle.put("rrb", false);
    battle.put("seb", false);
    for (JsonNode data : battle.path("hbd")) {
      ((ObjectNode) data).put("kt", 1);
    }
    for (JsonNode command : document.path("cmd")) {
      ((ObjectNode) command)
          .put("ct", command.has("c") && command.path("c").has("cgid") ? ABILITY : PLAY);
    }
    return document;
  }

  /** The fixture with every field the configured version's replays write, as a document. */
  static ObjectNode archerQueenWithEveryField() {
    return read(ARCHER_QUEEN_EVERY_FIELD);
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
