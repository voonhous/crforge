package org.crforge.desktop;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.crforge.core.battle.data.GameTables;

/**
 * Which game tables the debug visualizer opens on. There is one way: {@code ./gradlew :desktop:run}
 * builds the tables (see {@code docs/game-tables.md}, Building the game tables) and passes the
 * program the system properties {@value #TABLES_ROOT_PROPERTY}, the folder they are built in with
 * one folder per data version, {@value #DATA_VERSION_PROPERTY}, the version to open, and {@value
 * #LOCK_VERSION_PROPERTY}, the lock's version (the development target). The tables are {@code
 * <root>/<data version>}; the root's versions are the ones the screen's {@code V} key cycles
 * through.
 */
public final class DataSelection {

  /** The system property naming the folder the tables are built in, one folder per version. */
  public static final String TABLES_ROOT_PROPERTY = "crforge.tablesRoot";

  /** The system property naming the data version to open. */
  public static final String DATA_VERSION_PROPERTY = "crforge.dataVersion";

  /** The system property naming the lock's data version, the development target. */
  public static final String LOCK_VERSION_PROPERTY = "crforge.lockVersion";

  private static final JsonFactory JSON = new JsonFactory();

  private DataSelection() {
    // Utility class
  }

  /**
   * The outcome of the choice.
   *
   * @param root the folder of built tables, or null when nothing was chosen
   * @param version the data version opened, or null when nothing was chosen
   * @param lockVersion the lock's data version, or null when it was not given
   * @param problem why nothing was chosen and how to choose, or null when something was
   */
  public record Choice(Path root, String version, String lockVersion, String problem) {

    /** The folder of the chosen version's tables. */
    public Path folder() {
      return root.resolve(version);
    }
  }

  /** Chooses the tables of this process's system properties. */
  public static Choice ofProcess() {
    return choose(
        System.getProperty(TABLES_ROOT_PROPERTY),
        System.getProperty(DATA_VERSION_PROPERTY),
        System.getProperty(LOCK_VERSION_PROPERTY));
  }

  /**
   * Chooses the tables: the version's folder in the root.
   *
   * @param root the {@value #TABLES_ROOT_PROPERTY} property's value, or null
   * @param version the {@value #DATA_VERSION_PROPERTY} property's value, or null
   * @param lockVersion the {@value #LOCK_VERSION_PROPERTY} property's value, or null
   * @return the choice, or why there is none
   */
  public static Choice choose(String root, String version, String lockVersion) {
    if (blankToNull(root) == null || blankToNull(version) == null) {
      return new Choice(
          null,
          null,
          null,
          "No game tables: the debug visualizer reads the tables the build makes, given as the"
              + " system properties "
              + TABLES_ROOT_PROPERTY
              + " and "
              + DATA_VERSION_PROPERTY
              + ". Run it with ./gradlew :desktop:run and the Gradle property "
              + GameTables.ASSET_SOURCE_PROPERTY
              + " set, to a file: URI of a copy of the game's files, or to cdn for the game's"
              + " asset CDN; -P"
              + DATA_VERSION_PROPERTY
              + "=<v> opens another data version.");
    }
    return new Choice(Paths.get(root), version, blankToNull(lockVersion), null);
  }

  /** The startup lines about the tables loaded and the versions beside them. */
  public static List<String> describe(Choice choice, GameTables tables) {
    List<String> versions = versions(choice.root());
    return List.of(
        "game tables: " + choice.folder().toAbsolutePath().normalize(),
        "data version: " + tables.version(),
        "content sha: " + tables.contentSha(),
        "data versions: "
            + (versions.isEmpty() ? "none" : String.join(", ", versions))
            + " (V switches)");
  }

  /**
   * The data versions of a root: its folders that hold at least one table file, leaving out hidden
   * folders, in version order (numerically, part by part).
   *
   * @param root the folder of built tables
   * @return the folder names, or an empty list when the root is not a folder
   */
  public static List<String> versions(Path root) {
    if (root == null || !Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> listing = Files.list(root)) {
      return listing
          .filter(Files::isDirectory)
          .map(folder -> folder.getFileName().toString())
          .filter(name -> !name.startsWith("."))
          .filter(name -> holdsTables(root.resolve(name)))
          .sorted(VERSION_ORDER)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list " + root, e);
    }
  }

  /**
   * The content sha of a version folder's tables, read from the header of one table file (the first
   * by name) without loading the tables: every table file of a data version names the same sha.
   *
   * @param folder the version folder
   * @return the sha, or empty when the folder holds no table file whose header names one
   */
  public static Optional<String> contentSha(Path folder) {
    if (folder == null || !Files.isDirectory(folder)) {
      return Optional.empty();
    }
    Path first;
    try (Stream<Path> listing = Files.list(folder)) {
      first =
          listing
              .filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".json"))
              .min(Comparator.comparing(f -> f.getFileName().toString()))
              .orElse(null);
    } catch (IOException e) {
      return Optional.empty();
    }
    if (first == null) {
      return Optional.empty();
    }
    // The header fields come first: read top-level fields until the sha, skipping any value.
    try (JsonParser parser = JSON.createParser(first.toFile())) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        return Optional.empty();
      }
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        String name = parser.currentName();
        JsonToken value = parser.nextToken();
        if (name.equals(GameTables.CONTENT_SHA_FIELD) && value == JsonToken.VALUE_STRING) {
          return Optional.of(parser.getText());
        }
        parser.skipChildren();
      }
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Orders version names numerically part by part; a part that is not a number sorts as text. */
  static final Comparator<String> VERSION_ORDER =
      (a, b) -> {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
          int compared = comparePart(left[i], right[i]);
          if (compared != 0) {
            return compared;
          }
        }
        return left.length != right.length
            ? Integer.compare(left.length, right.length)
            : a.compareTo(b);
      };

  private static int comparePart(String a, String b) {
    if (a.matches("\\d+") && b.matches("\\d+")) {
      // Lengths first, so a part of any size compares without overflow.
      String left = a.replaceFirst("^0+(?=.)", "");
      String right = b.replaceFirst("^0+(?=.)", "");
      return left.length() != right.length()
          ? Integer.compare(left.length(), right.length())
          : left.compareTo(right);
    }
    return a.compareTo(b);
  }

  private static boolean holdsTables(Path folder) {
    try (Stream<Path> listing = Files.list(folder)) {
      return listing.anyMatch(
          file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".json"));
    } catch (IOException e) {
      return false;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
