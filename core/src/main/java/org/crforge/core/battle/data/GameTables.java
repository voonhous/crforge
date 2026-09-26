package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A folder of game tables: one file per table of the game's data, in the game's own names and
 * units, and the action graph, all of one data version. The repository ships no game data; the
 * folder is configured, by the system property {@value #PROPERTY} or the environment variable
 * {@value #ENVIRONMENT}.
 *
 * <p>A table file holds a header - the table's name, id, data version and content hash - and its
 * rows by name in creation order, each with its index, its class and its columns. The action graph
 * holds every action row by name with its class, its ClassType and its fields.
 */
public final class GameTables {

  /** The system property naming the folder. */
  public static final String PROPERTY = "crforge.gameTables";

  /** The environment variable naming the folder, when the property is not set. */
  public static final String ENVIRONMENT = "CRFORGE_GAME_TABLES";

  /** The file the action graph is in. */
  private static final String ACTIONS = "actions.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String version;
  private final String contentSha;
  private final Map<String, GameTable> tables;
  private final Map<String, GameAction> actions;

  private GameTables(
      String version,
      String contentSha,
      Map<String, GameTable> tables,
      Map<String, GameAction> actions) {
    this.version = version;
    this.contentSha = contentSha;
    this.tables = tables;
    this.actions = actions;
  }

  /** The configured folder, or empty when neither the property nor the variable names one. */
  public static Optional<Path> configuredDirectory() {
    String configured = System.getProperty(PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(ENVIRONMENT);
    }
    return configured == null || configured.isBlank()
        ? Optional.empty()
        : Optional.of(Paths.get(configured));
  }

  /** Loads the configured folder; fails, naming the settings, when none is configured. */
  public static GameTables loadConfigured() {
    Optional<Path> folder = configuredDirectory();
    checkState(
        folder.isPresent(),
        () -> "no game tables configured: set " + PROPERTY + " or " + ENVIRONMENT);
    return load(folder.get());
  }

  /**
   * Loads a folder of game tables.
   *
   * @param folder the folder of one data version
   * @return its tables and its action graph
   */
  public static GameTables load(Path folder) {
    checkState(
        Files.isDirectory(folder),
        () ->
            "no game tables at "
                + folder
                + "; set "
                + PROPERTY
                + " or "
                + ENVIRONMENT
                + " to a folder of them");
    Map<String, GameTable> tables = new TreeMap<>();
    Map<String, GameAction> actions = new LinkedHashMap<>();
    String version = null;
    String contentSha = null;
    for (Path file : files(folder)) {
      JsonNode document = read(file);
      String fileVersion = document.path("version").asText();
      if (version == null) {
        version = fileVersion;
        contentSha = document.path("content_sha").asText();
      }
      String expected = version;
      checkState(
          expected.equals(fileVersion),
          () -> file.getFileName() + " is of version " + fileVersion + ", not " + expected);
      if (file.getFileName().toString().equals(ACTIONS)) {
        readActions(document, actions);
      } else {
        GameTable table = readTable(document);
        tables.put(table.name(), table);
      }
    }
    checkState(version != null, () -> "no game tables in " + folder);
    return new GameTables(version, contentSha, tables, actions);
  }

  private static List<Path> files(Path folder) {
    try (Stream<Path> listing = Files.list(folder)) {
      return listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list " + folder, e);
    }
  }

  private static JsonNode read(Path file) {
    try {
      return MAPPER.readTree(file.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  private static GameTable readTable(JsonNode document) {
    Map<String, GameRow> rows = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = document.path("rows").fields();
        it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      JsonNode row = entry.getValue();
      Map<String, JsonNode> columns = new LinkedHashMap<>();
      row.path("columns").fields().forEachRemaining(c -> columns.put(c.getKey(), c.getValue()));
      JsonNode className = row.path("class");
      rows.put(
          entry.getKey(),
          new GameRow(
              entry.getKey(),
              row.path("index").asInt(),
              className.isNull() || className.isMissingNode() ? null : className.asText(),
              columns));
    }
    return new GameTable(
        document.path("table").asText(),
        document.path("id").asText(),
        document.path("version").asText(),
        document.path("content_sha").asText(),
        rows);
  }

  private static void readActions(JsonNode document, Map<String, GameAction> actions) {
    document
        .path("actions")
        .fields()
        .forEachRemaining(
            entry ->
                actions.put(
                    entry.getKey(),
                    new GameAction(
                        entry.getKey(),
                        entry.getValue().path("class").asText(),
                        entry.getValue().path("ClassType").asText(),
                        entry.getValue().path("fields"))));
  }

  /** The data version every table belongs to. */
  public String version() {
    return version;
  }

  /** The content hash of that data version. */
  public String contentSha() {
    return contentSha;
  }

  /** The names of the tables in the folder. */
  public List<String> tableNames() {
    return List.copyOf(tables.keySet());
  }

  /** The table of the given name; fails when the folder has none. */
  public GameTable table(String name) {
    GameTable table = tables.get(name);
    checkState(table != null, () -> "the game tables of " + version + " have no " + name);
    return table;
  }

  /** The names of the action rows, in the order the data holds them. */
  public List<String> actionNames() {
    return new ArrayList<>(actions.keySet());
  }

  /** The action row of the given name; fails when the data has none. */
  public GameAction action(String name) {
    GameAction action = actions.get(name);
    checkState(action != null, () -> "the game tables of " + version + " have no action " + name);
    return action;
  }
}
