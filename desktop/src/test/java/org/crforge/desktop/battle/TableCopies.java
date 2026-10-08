package org.crforge.desktop.battle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;

/**
 * Version folders for the tests of a data root: copies of the configured tables, and a copy the
 * battle core refuses to start a battle on.
 */
public final class TableCopies {

  private TableCopies() {
    // Utility class
  }

  /**
   * Copies the configured tables into a version folder of a root.
   *
   * @param root the data root
   * @param version the version folder's name
   * @return the folder
   */
  public static Path copy(Path root, String version) throws IOException {
    Path folder = Files.createDirectories(root.resolve(version));
    GameData.copyConfigured(folder);
    return folder;
  }

  /**
   * A copy of the configured tables whose every file's header names another content sha: the same
   * rows, as another set of game data would be named.
   *
   * @param root the data root
   * @param version the version folder's name
   * @param contentSha the content sha the copy's headers name
   * @return the folder
   */
  public static Path withContentSha(Path root, String version, String contentSha)
      throws IOException {
    Path folder = copy(root, version);
    String original = GameTables.load(folder).contentSha();
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String header = "\"content_sha\": \"" + original + "\"";
        int at = text.indexOf(header);
        if (at < 0) {
          throw new IOException(file + " names no content sha " + original);
        }
        Files.writeString(
            file,
            text.substring(0, at)
                + "\"content_sha\": \""
                + contentSha
                + "\""
                + text.substring(at + header.length()),
            StandardCharsets.UTF_8);
      }
    }
    return folder;
  }

  /**
   * A copy whose first variables row sets Tid, a column the battle core does not model: its tables
   * load, and a battle on them is refused as it is built.
   *
   * @param root the data root
   * @param version the version folder's name
   * @return the folder
   */
  public static Path refused(Path root, String version) throws IOException {
    Path folder = copy(root, version);
    ObjectMapper mapper = new ObjectMapper();
    Path variables = folder.resolve("variables.json");
    ObjectNode document = (ObjectNode) mapper.readTree(variables.toFile());
    ObjectNode firstRow = (ObjectNode) document.path("rows").elements().next();
    ((ObjectNode) firstRow.path("columns")).put("Tid", "TID_REFUSED");
    mapper.writeValue(variables.toFile(), document);
    return folder;
  }

  /**
   * A copy whose every file's header names another data version: the same rows under a version the
   * battle core does not model, so a battle on them is refused as it is built.
   *
   * @param root the data root
   * @param version the version folder's name
   * @param dataVersion the data version the copy's headers name
   * @return the folder
   */
  public static Path labelled(Path root, String version, String dataVersion) throws IOException {
    Path folder = copy(root, version);
    ObjectMapper mapper = new ObjectMapper();
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
        document.put(GameTables.VERSION_FIELD, dataVersion);
        mapper.writeValue(file.toFile(), document);
      }
    }
    return folder;
  }
}
