package org.crforge.desktop.battle;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameTables;

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
   * The versions of a root, starting on the tables already loaded.
   *
   * @param root the data root, or null when there is none
   * @param versions the root's version folder names, in order
   * @param currentFolder the folder the current tables came from
   * @param current the current tables
   */
  public DataVersions(Path root, List<String> versions, Path currentFolder, GameTables current) {
    this.root = root;
    this.versions = root == null ? List.of() : List.copyOf(versions);
    this.current = current;
    this.currentFolder = currentFolder;
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
    cursor = (cursor + 1) % versions.size();
    String version = versions.get(cursor);
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
    return new Switched(version, session, null);
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
