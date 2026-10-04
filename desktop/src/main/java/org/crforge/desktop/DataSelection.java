package org.crforge.desktop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.crforge.core.battle.data.GameTables;

/**
 * Which game tables the debug visualizer opens on: a data version of a data root, or a tables
 * folder named outright.
 *
 * <p>A <b>data root</b> is a checkout of the game data repository: one folder per data version,
 * each holding that version's tables, beside a {@code references} folder. The root is named by the
 * system property {@value #DATA_ROOT_PROPERTY}, else the environment variable {@value
 * #DATA_ROOT_ENVIRONMENT}; with neither, it is the {@value #SIBLING_FOLDER} folder beside the
 * project folder, when there is one. The project folder is named by {@value #PROJECT_DIR_PROPERTY}
 * ({@code ./gradlew :desktop:run} passes it), else it is the nearest folder up from the working
 * folder that holds {@value #LOCK_FILE}.
 *
 * <p>The tables are chosen by the first of these rules that applies:
 *
 * <ol>
 *   <li>an explicit data version, the argument {@value #DATA_VERSION_ARGUMENT} {@code <v>} or else
 *       the system property {@value #DATA_VERSION_PROPERTY}: the folder {@code <root>/<v>};
 *   <li>a tables folder named by {@value GameTables#PROPERTY} or {@value GameTables#ENVIRONMENT},
 *       as before data roots (see {@link GameTablesSetting});
 *   <li>the {@code version=} of the project's {@value #LOCK_FILE}: the folder {@code
 *       <root>/<version>}.
 * </ol>
 *
 * <p>Whichever rule applies, the data root, when there is one, gives the screen the versions its
 * {@code V} key cycles through.
 */
public final class DataSelection {

  /** The system property naming the data root. */
  public static final String DATA_ROOT_PROPERTY = "crforge.dataRoot";

  /** The environment variable naming the data root. */
  public static final String DATA_ROOT_ENVIRONMENT = "CRFORGE_DATA_ROOT";

  /** The system property naming the data version. */
  public static final String DATA_VERSION_PROPERTY = "crforge.dataVersion";

  /** The program argument naming the data version, ahead of the property. */
  public static final String DATA_VERSION_ARGUMENT = "--data-version";

  /** The system property naming the project folder, which holds the lock. */
  public static final String PROJECT_DIR_PROPERTY = "crforge.projectDir";

  /** The project's file naming the data repository's commit and data version it is built on. */
  public static final String LOCK_FILE = "crforge-data.lock";

  /** The folder beside the project folder that is the data root when no setting names one. */
  public static final String SIBLING_FOLDER = "crforge-data";

  /** Where a data root found beside the project folder is said to come from. */
  public static final String SIBLING_SOURCE = "the crforge-data folder beside the project";

  /** The data root's folder of reference battles, which is not a data version. */
  static final String REFERENCES_FOLDER = "references";

  private DataSelection() {
    // Utility class
  }

  /**
   * The settings the choice is made from, each null when not given.
   *
   * @param versionArgument the {@value #DATA_VERSION_ARGUMENT} argument's value
   * @param versionProperty the {@value #DATA_VERSION_PROPERTY} property's value
   * @param rootProperty the {@value #DATA_ROOT_PROPERTY} property's value
   * @param rootEnvironment the {@value #DATA_ROOT_ENVIRONMENT} variable's value
   * @param tablesProperty the {@value GameTables#PROPERTY} property's value
   * @param tablesEnvironment the {@value GameTables#ENVIRONMENT} variable's value
   * @param projectDir the project folder, which holds the lock
   */
  public record Settings(
      String versionArgument,
      String versionProperty,
      String rootProperty,
      String rootEnvironment,
      String tablesProperty,
      String tablesEnvironment,
      Path projectDir) {

    /** The settings of this process and its arguments. */
    public static Settings ofProcess(String[] args) {
      return new Settings(
          DataSelection.versionArgument(args),
          System.getProperty(DATA_VERSION_PROPERTY),
          System.getProperty(DATA_ROOT_PROPERTY),
          System.getenv(DATA_ROOT_ENVIRONMENT),
          System.getProperty(GameTables.PROPERTY),
          System.getenv(GameTables.ENVIRONMENT),
          findProjectDir(
              System.getProperty(PROJECT_DIR_PROPERTY), Paths.get(System.getProperty("user.dir"))));
    }
  }

  /**
   * A data root and the setting that named it.
   *
   * @param folder the root folder
   * @param source the property's name, the variable's, or {@link #SIBLING_SOURCE}
   */
  public record DataRoot(Path folder, String source) {}

  /**
   * The project's lock.
   *
   * @param commit the data repository's commit, or null when the lock names none
   * @param version the data version, or null when the lock names none
   */
  public record Lock(String commit, String version) {}

  /**
   * The outcome of the choice.
   *
   * @param root the data root, or null when there is none
   * @param lock the project's lock, or null when there is none
   * @param tables the tables folder and the rule that chose it, or null when nothing was chosen
   * @param problem why nothing was chosen and how to choose, or null when something was
   */
  public record Choice(
      DataRoot root, Lock lock, GameTablesSetting.Configured tables, String problem) {}

  /**
   * The {@value #DATA_VERSION_ARGUMENT} argument's value, given as {@code --data-version <v>} or
   * {@code --data-version=<v>}.
   *
   * @param args the program's arguments
   * @return the value, or null when the argument is missing or has none
   */
  public static String versionArgument(String[] args) {
    String prefix = DATA_VERSION_ARGUMENT + "=";
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith(prefix)) {
        return blankToNull(args[i].substring(prefix.length()));
      }
      if (args[i].equals(DATA_VERSION_ARGUMENT)) {
        return i + 1 < args.length ? blankToNull(args[i + 1]) : null;
      }
    }
    return null;
  }

  /**
   * The project folder: the property's value, else the nearest folder from the working folder up
   * that holds {@value #LOCK_FILE}.
   *
   * @param property the {@value #PROJECT_DIR_PROPERTY} property's value, or null
   * @param workingDir the working folder
   * @return the folder, or null when there is none
   */
  public static Path findProjectDir(String property, Path workingDir) {
    if (blankToNull(property) != null) {
      return Paths.get(property);
    }
    for (Path dir = workingDir.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve(LOCK_FILE))) {
        return dir;
      }
    }
    return null;
  }

  /**
   * The data root: the property's, else the variable's, else the {@value #SIBLING_FOLDER} folder
   * beside the project folder when it exists. A blank value counts as none.
   *
   * @param property the {@value #DATA_ROOT_PROPERTY} property's value, or null
   * @param environment the {@value #DATA_ROOT_ENVIRONMENT} variable's value, or null
   * @param projectDir the project folder, or null
   * @return the root and where it came from, or empty when there is none
   */
  public static Optional<DataRoot> resolveRoot(
      String property, String environment, Path projectDir) {
    if (blankToNull(property) != null) {
      return Optional.of(new DataRoot(Paths.get(property), DATA_ROOT_PROPERTY));
    }
    if (blankToNull(environment) != null) {
      return Optional.of(new DataRoot(Paths.get(environment), DATA_ROOT_ENVIRONMENT));
    }
    if (projectDir != null) {
      Path parent = projectDir.toAbsolutePath().normalize().getParent();
      Path sibling = parent == null ? null : parent.resolve(SIBLING_FOLDER);
      if (sibling != null && Files.isDirectory(sibling)) {
        return Optional.of(new DataRoot(sibling, SIBLING_SOURCE));
      }
    }
    return Optional.empty();
  }

  /**
   * Reads the project's {@value #LOCK_FILE}: its {@code commit=} and {@code version=} lines, past
   * its comment lines.
   *
   * @param projectDir the project folder, or null
   * @return the lock, or empty when there is no lock file
   */
  public static Optional<Lock> readLock(Path projectDir) {
    if (projectDir == null || !Files.isRegularFile(projectDir.resolve(LOCK_FILE))) {
      return Optional.empty();
    }
    String commit = null;
    String version = null;
    for (String line : readLines(projectDir.resolve(LOCK_FILE))) {
      String trimmed = line.trim();
      if (trimmed.startsWith("commit=")) {
        commit = blankToNull(trimmed.substring("commit=".length()).trim());
      } else if (trimmed.startsWith("version=")) {
        version = blankToNull(trimmed.substring("version=".length()).trim());
      }
    }
    return Optional.of(new Lock(commit, version));
  }

  /**
   * The data versions of a root: its folders that hold at least one table file, leaving out the
   * references folder and hidden folders, in version order (numerically, part by part).
   *
   * @param root the data root
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
          .filter(name -> !name.startsWith(".") && !name.equals(REFERENCES_FOLDER))
          .filter(name -> holdsTables(root.resolve(name)))
          .sorted(VERSION_ORDER)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list " + root, e);
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

  /**
   * The commit a root has checked out, read from its git folder without running git: a detached
   * head, or the branch head names, read from its loose ref or the packed refs. A {@code .git} file
   * (a linked worktree) is followed to its git folder and that folder's common folder.
   *
   * @param root the data root
   * @return the commit, or empty when the root is no git checkout this can read
   */
  public static Optional<String> headCommit(Path root) {
    try {
      Path git = root.resolve(".git");
      Path gitDir = git;
      if (Files.isRegularFile(git)) {
        String link = Files.readString(git, StandardCharsets.UTF_8).trim();
        if (!link.startsWith("gitdir:")) {
          return Optional.empty();
        }
        gitDir = root.resolve(link.substring("gitdir:".length()).trim()).normalize();
      }
      Path head = gitDir.resolve("HEAD");
      if (!Files.isRegularFile(head)) {
        return Optional.empty();
      }
      String content = Files.readString(head, StandardCharsets.UTF_8).trim();
      if (!content.startsWith("ref:")) {
        return isCommit(content) ? Optional.of(content) : Optional.empty();
      }
      String ref = content.substring("ref:".length()).trim();
      Path commonDir = gitDir;
      Path commonFile = gitDir.resolve("commondir");
      if (Files.isRegularFile(commonFile)) {
        String common = Files.readString(commonFile, StandardCharsets.UTF_8).trim();
        commonDir = gitDir.resolve(common).normalize();
      }
      for (Path dir : List.of(gitDir, commonDir)) {
        Path loose = dir.resolve(ref);
        if (Files.isRegularFile(loose)) {
          String commit = Files.readString(loose, StandardCharsets.UTF_8).trim();
          return isCommit(commit) ? Optional.of(commit) : Optional.empty();
        }
      }
      Path packed = commonDir.resolve("packed-refs");
      if (Files.isRegularFile(packed)) {
        for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
          String[] parts = line.trim().split(" ");
          if (parts.length == 2 && parts[1].equals(ref) && isCommit(parts[0])) {
            return Optional.of(parts[0]);
          }
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static boolean isCommit(String text) {
    return text.matches("[0-9a-f]{40}([0-9a-f]{24})?");
  }

  /**
   * Chooses the tables by the rules of this class.
   *
   * @param settings the settings to choose from
   * @return the choice, or why there is none
   */
  public static Choice choose(Settings settings) {
    DataRoot root =
        resolveRoot(settings.rootProperty(), settings.rootEnvironment(), settings.projectDir())
            .orElse(null);
    Lock lock = readLock(settings.projectDir()).orElse(null);

    // 1. An explicit data version, in the data root.
    String argument = blankToNull(settings.versionArgument());
    String property = blankToNull(settings.versionProperty());
    if (argument != null || property != null) {
      String version = argument != null ? argument : property;
      String rule =
          argument != null
              ? DATA_VERSION_ARGUMENT + " " + version
              : DATA_VERSION_PROPERTY + "=" + version;
      if (root == null) {
        return new Choice(
            null,
            lock,
            null,
            "Data version "
                + version
                + " asked for by "
                + rule
                + ", but there is no data root to find it in: set "
                + DATA_ROOT_PROPERTY
                + "=<folder> (a Gradle or system property) or the environment variable "
                + DATA_ROOT_ENVIRONMENT
                + "=<folder>, or check out the game data repository as "
                + SIBLING_FOLDER
                + " beside the project folder.");
      }
      return new Choice(
          root,
          lock,
          new GameTablesSetting.Configured(
              root.folder().resolve(version), rule + " in the data root"),
          null);
    }

    // 2. A tables folder named outright.
    Optional<GameTablesSetting.Configured> tables =
        GameTablesSetting.resolve(settings.tablesProperty(), settings.tablesEnvironment());
    if (tables.isPresent()) {
      return new Choice(root, lock, tables.get(), null);
    }

    // 3. The lock's version, in the data root.
    if (root != null && lock != null && lock.version() != null) {
      return new Choice(
          root,
          lock,
          new GameTablesSetting.Configured(
              root.folder().resolve(lock.version()),
              "version=" + lock.version() + " of " + LOCK_FILE + " in the data root"),
          null);
    }
    return new Choice(root, lock, null, missingMessage(root, lock));
  }

  /** What the visualizer says when no rule chooses any tables, and how to configure them. */
  public static String missingMessage() {
    return missingMessage(null, null);
  }

  private static String missingMessage(DataRoot root, Lock lock) {
    String why;
    if (root == null) {
      why = "there is no data root";
    } else if (lock == null) {
      why =
          "the data root "
              + root.folder().toAbsolutePath().normalize()
              + " was found, but no "
              + LOCK_FILE
              + " to name a data version in it";
    } else {
      why =
          "the data root "
              + root.folder().toAbsolutePath().normalize()
              + " was found, but "
              + LOCK_FILE
              + " names no version=";
    }
    return "No game tables configured: the debug visualizer runs the battle core, which reads the"
        + " game's tables, and "
        + why
        + ". Name a data root (a checkout of the game data repository, one folder per data"
        + " version) with the Gradle or system property "
        + DATA_ROOT_PROPERTY
        + "=<folder> or the environment variable "
        + DATA_ROOT_ENVIRONMENT
        + "=<folder>, or check it out as "
        + SIBLING_FOLDER
        + " beside the project folder; the version is then "
        + DATA_VERSION_ARGUMENT
        + " <v>, "
        + DATA_VERSION_PROPERTY
        + "=<v> or the lock's. Or name a tables folder with the Gradle or system property "
        + GameTables.PROPERTY
        + "=<folder> or the environment variable "
        + GameTables.ENVIRONMENT
        + "=<folder>.";
  }

  /**
   * The startup lines about the data root: the folder and the setting that named it, its
   * checked-out commit against the lock's, and its data versions. Empty when there is no root.
   */
  public static List<String> describeRoot(Choice choice) {
    if (choice.root() == null) {
      return List.of();
    }
    Path folder = choice.root().folder();
    List<String> lines = new ArrayList<>();
    lines.add(
        "data root: "
            + folder.toAbsolutePath().normalize()
            + " (from "
            + choice.root().source()
            + ")");
    Optional<String> head = headCommit(folder);
    String lockCommit = choice.lock() == null ? null : choice.lock().commit();
    String commitLine;
    if (head.isEmpty()) {
      commitLine = "unknown (no git checkout read)";
    } else if (lockCommit == null) {
      commitLine = head.get() + " (no lock commit to compare)";
    } else if (head.get().equals(lockCommit)) {
      commitLine = head.get() + " (the lock's commit)";
    } else {
      commitLine = head.get() + " (differs from the lock's " + lockCommit + "; informational only)";
    }
    lines.add("data root commit: " + commitLine);
    List<String> versions = versions(folder);
    lines.add(
        "data versions: "
            + (versions.isEmpty() ? "none" : String.join(", ", versions))
            + " (V switches)");
    return lines;
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
