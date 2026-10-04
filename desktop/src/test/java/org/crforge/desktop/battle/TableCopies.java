package org.crforge.desktop.battle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
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
    Path source = GameTables.configuredDirectory().orElseThrow();
    Path folder = Files.createDirectories(root.resolve(version));
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        Files.copy(file, folder.resolve(file.getFileName()));
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
}
