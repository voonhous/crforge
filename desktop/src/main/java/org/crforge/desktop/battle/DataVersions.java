package org.crforge.desktop.battle;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.DataSelection;

/**
 * The data versions the debug screen can switch between: the version folders of a data root, the
 * tables the screen's battles read now, and the switch to the next version that the {@code V} key
 * makes.
 *
 * <p>A switch loads the next version's tables (once; they are kept for the next time round) and
 * starts a Ladder battle on them. The battle core refuses tables it does not model as a battle on
 * them is built, and a version may not be readable at all; either way the switch is reported and
 * the screen stays on the tables it had, so {@code R} still starts a battle and {@code V} moves on
 * to the version after the refused one.
 */
public final class DataVersions {

  /** The data root, or null when the tables came from outside any root. */
  private final Path root;

  /** The root's version folder names, in the order {@code V} visits them. */
  private final List<String> versions;

  /** The tables of each version loaded so far, by folder name. */
  private final Map<String, GameTables> loaded = new HashMap<>();

  /** The tables the screen's battles read now, and the folder they came from. */
  private GameTables current;

  private Path currentFolder;
  private String source;
  private final String developmentVersion;

  /** The index into {@link #versions} of the version {@code V} last tried, or -1. */
  private int cursor;

  /**
   * The result of a switch.
   *
   * @param version the version folder tried, or null when there was none to try
   * @param session a new Ladder battle on that version's tables, or null when it was refused
   * @param refusal why the switch was refused, or null when it was made
   */
  public record Switched(String version, BattleSession session, String refusal) {}

  /**
   * The result of looking up a content sha in the root.
   *
   * @param version the version folder whose tables have the sha, or null when none has it
   * @param from the version on screen before, when the lookup switched to another; else null
   * @param refusal why no version was switched to, or null when the current tables have the sha or
   *     a switch was made
   */
  public record ContentMatch(String version, String from, String refusal) {}

  /** The source of tables a replay's capture block named ({@link #selectContent}). */
  public static final String NAMED_BY_REPLAY = "named by the replay's capture block";

  /**
   * The versions of a root, starting on the tables already loaded.
   *
   * @param root the data root, or null when there is none
   * @param versions the root's version folder names, in order
   * @param currentFolder the folder the current tables came from
   * @param current the current tables
   */
  public DataVersions(Path root, List<String> versions, Path currentFolder, GameTables current) {
    this(root, versions, currentFolder, current, "configured tables folder", "unknown");
  }

  public DataVersions(
      Path root,
      List<String> versions,
      Path currentFolder,
      GameTables current,
      String source,
      String developmentVersion) {
    this.root = root;
    this.versions = root == null ? List.of() : List.copyOf(versions);
    this.current = current;
    this.currentFolder = currentFolder;
    this.source = source;
    this.developmentVersion = developmentVersion;
    this.cursor = startCursor();
    if (cursor >= 0 && sameFolder(this.versions.get(cursor))) {
      loaded.put(this.versions.get(cursor), current);
    }
  }

  /**
   * Where the cycle starts: on the root folder the current tables came from, else on the root
   * folder named as their data version, else before the first.
   */
  private int startCursor() {
    for (int i = 0; i < versions.size(); i++) {
      if (sameFolder(versions.get(i))) {
        return i;
      }
    }
    return versions.indexOf(current.version());
  }

  private boolean sameFolder(String version) {
    return root.resolve(version)
        .toAbsolutePath()
        .normalize()
        .equals(currentFolder.toAbsolutePath().normalize());
  }

  /** The tables the screen's battles read now. */
  public GameTables current() {
    return current;
  }

  /** The folder the current tables came from. */
  public Path currentFolder() {
    return currentFolder;
  }

  public String source() {
    return source;
  }

  public String developmentVersion() {
    return developmentVersion;
  }

  /** The root's version folder names. */
  public List<String> versions() {
    return versions;
  }

  /** A new Ladder battle on the current tables, which is what {@code R} starts. */
  public BattleSession ladder() {
    return BattleSession.ladder(current);
  }

  /**
   * Switches to the root's next version: loads its tables and starts a Ladder battle on them. When
   * the tables cannot be read, or the battle core refuses a battle on them, the current tables
   * stay, and the next switch tries the version after this one.
   *
   * @return the new battle, or why there is none
   */
  public Switched next() {
    if (versions.isEmpty()) {
      return new Switched(
          null,
          null,
          root == null
              ? "no data root to switch versions in: set crforge.dataRoot or CRFORGE_DATA_ROOT,"
                  + " or check out crforge-data beside the project"
              : "no data versions in " + root.toAbsolutePath().normalize());
    }
    return select(versions.get((cursor + 1) % versions.size()));
  }

  /** Loads an explicitly chosen version, retaining the current tables and source on failure. */
  public Switched select(String version) {
    int index = versions.indexOf(version);
    if (index < 0) {
      return new Switched(version, null, "unknown data version " + version + stillOn());
    }
    cursor = index;
    Path folder = root.resolve(version);
    GameTables tables = loaded.get(version);
    if (tables == null) {
      try {
        tables = GameTables.load(folder);
      } catch (RuntimeException e) {
        return new Switched(
            version,
            null,
            "cannot read the tables of data version "
                + version
                + " at "
                + folder.toAbsolutePath().normalize()
                + ": "
                + e.getMessage()
                + stillOn());
      }
      loaded.put(version, tables);
    }
    BattleSession session;
    try {
      session = BattleSession.ladder(tables);
    } catch (RuntimeException e) {
      return new Switched(
          version,
          null,
          "the battle core refuses a battle on data version "
              + version
              + ", "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage()
              + stillOn());
    }
    current = tables;
    currentFolder = folder;
    source = "selected from the data root";
    return new Switched(version, session, null);
  }

  /**
   * Makes the tables of a content sha current, for a replay whose capture block names it: the
   * current tables when they have it, else the root's first version whose tables have it, loaded
   * once and kept, without starting a battle (the replay's own reading lists any refusal of the
   * battle core). Each version's sha is read from the header of one of its table files ({@link
   * DataSelection#contentSha}) unless its tables are loaded already. When no version has the sha,
   * or its tables cannot be read, the current tables stay.
   *
   * @param contentSha the content sha the replay names
   * @return the version switched to, or why none was
   */
  public ContentMatch selectContent(String contentSha) {
    if (contentSha.equals(current.contentSha())) {
      return new ContentMatch(cursor >= 0 ? versions.get(cursor) : current.version(), null, null);
    }
    String from = cursor >= 0 ? versions.get(cursor) : current.version();
    for (int i = 0; i < versions.size(); i++) {
      String version = versions.get(i);
      GameTables tables = loaded.get(version);
      String sha =
          tables != null
              ? tables.contentSha()
              : DataSelection.contentSha(root.resolve(version)).orElse(null);
      if (!contentSha.equals(sha)) {
        continue;
      }
      Path folder = root.resolve(version);
      if (tables == null) {
        try {
          tables = GameTables.load(folder);
        } catch (RuntimeException e) {
          return new ContentMatch(
              null,
              null,
              "cannot read the tables of data version "
                  + version
                  + " at "
                  + folder.toAbsolutePath().normalize()
                  + ", whose content sha the replay names: "
                  + e.getMessage());
        }
        loaded.put(version, tables);
      }
      cursor = i;
      current = tables;
      currentFolder = folder;
      source = NAMED_BY_REPLAY;
      return new ContentMatch(version, from, null);
    }
    return new ContentMatch(
        null,
        null,
        root == null
            ? "there is no data root to look for the content sha "
                + contentSha
                + " the replay was recorded on in; it is not played on other data"
            : "no data version of the data root "
                + root.toAbsolutePath().normalize()
                + " has the content sha "
                + contentSha
                + " the replay was recorded on; it is not played on other data");
  }

  private String stillOn() {
    return "; still on "
        + current.version()
        + " (V tries the next, R resets on "
        + current.version()
        + ")";
  }

  /** The status column's line: the current data version and what {@code V} cycles through. */
  public String statusLine() {
    String cycle =
        root == null
            ? "no data root"
            : versions.size() + (versions.size() == 1 ? " version" : " versions");
    return "data: " + current.version() + " (V: " + cycle + ")";
  }
}
