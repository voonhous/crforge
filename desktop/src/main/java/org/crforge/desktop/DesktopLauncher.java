package org.crforge.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.crforge.core.arena.Arena;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayCapture;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.render.RenderConstants;
import org.crforge.desktop.replay.ReplayArchive;
import org.crforge.desktop.replay.ReplayFile;

/**
 * Desktop launcher for CRForge. Starts the LibGDX application with debug visualization.
 *
 * <p>The debug visualizer runs the battle core, so it first chooses and loads its game tables (see
 * {@link DataSelection}: an explicit {@code --data-version} or {@code crforge.dataVersion} in the
 * data root, else a {@code crforge.gameTables} folder, else the lock's version in the data root),
 * prints the data root, its commit and its versions, where the tables came from and by which rule,
 * their data version and their content hash, and stops with a message naming the settings when none
 * are configured, they cannot be read, or the battle core refuses a battle on them. The AI
 * visualizer ({@code --ai-port}) still runs the original engine and reads no tables.
 *
 * <p>{@code --replay <file>} opens the replay viewer on a replay file instead of a Ladder battle:
 * the launcher reads the replay against the tables of the data it was recorded on ({@link
 * #openReplay}: the data root's version whose content sha the replay's capture block names, else
 * the tables chosen, marked assumed for a replay without a block) and prints its battle header and
 * every reason the replay is refused, if it is (see {@link ReplayFile}), the battle core's refusal
 * of the tables among them, and opens the window on that list rather than stopping. A replay file
 * dropped on the debug visualizer's window opens the same way, except that the version is never
 * fixed for it.
 *
 * <p>A crawl's output ({@code .jsonl} or {@code .jsonl.gz}, see {@link ReplayArchive}) opens the
 * same way, given to {@code --replay} or dropped: the viewer lists its replays and opens the first
 * one that can be read, each on the data its record names ({@link #openEntry}).
 */
public class DesktopLauncher {

  /** The exit code when the game tables are missing or unreadable. */
  static final int NO_TABLES = 1;

  /** The exit code when the replay given cannot be read. */
  static final int NO_REPLAY = 2;

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The argument that names a replay file to open. */
  static final String REPLAY_ARGUMENT = "--replay";

  public static void main(String[] args) {
    // Parse --ai-port argument
    int aiPort = -1;
    for (int i = 0; i < args.length - 1; i++) {
      if ("--ai-port".equals(args[i])) {
        aiPort = Integer.parseInt(args[i + 1]);
        break;
      }
    }

    DataVersions versions = null;
    BattleSession first = null;
    ReplayFile replay = null;
    ReplayArchive archive = null;
    String fixedBy = null;
    if (aiPort <= 0) {
      DataSelection.Choice choice = DataSelection.choose(DataSelection.Settings.ofProcess(args));
      GameTables tables = loadTables(choice, System.out, System.err);
      if (tables == null) {
        System.exit(NO_TABLES);
        return;
      }
      versions = dataVersions(choice, tables);
      Optional<Path> replayFile;
      try {
        replayFile = replayArgument(args);
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        System.exit(NO_REPLAY);
        return;
      }
      if (replayFile.isPresent()) {
        // A replay lists the battle core's refusal of the tables among its reasons, so no first
        // Ladder battle is built for it.
        fixedBy = choice.explicitVersion();
        if (ReplayArchive.isArchive(replayFile.get())) {
          archive = openArchive(replayFile.get(), System.out, System.err);
          replay =
              archive == null
                  ? null
                  : openFirst(archive, versions, fixedBy, System.out, System.err);
        } else {
          replay = openReplay(replayFile.get(), versions, fixedBy, System.out, System.err);
        }
        if (replay == null) {
          System.exit(NO_REPLAY);
          return;
        }
      } else {
        first = firstSession(versions, System.err);
        if (first == null) {
          System.exit(NO_TABLES);
          return;
        }
      }
    }

    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();

    // Window size based on arena dimensions + UI Margins
    int width = (int) (Arena.WIDTH * RenderConstants.TILE_PIXELS);
    int height =
        (int)
            (Arena.HEIGHT * RenderConstants.TILE_PIXELS
                + RenderConstants.TOP_UI_HEIGHT
                + RenderConstants.BOTTOM_UI_HEIGHT);

    String title =
        aiPort > 0
            ? "CRForge - AI Visualizer"
            : replay != null ? "CRForge - Replay Viewer" : "CRForge - Debug Visualizer";
    config.setTitle(title);
    config.setWindowedMode(aiPort > 0 ? width : 1120, aiPort > 0 ? height : 1040);
    config.setResizable(aiPort <= 0);
    if (aiPort <= 0) config.setWindowSizeLimits(1000, 760, -1, -1);
    config.useVsync(true);
    config.setForegroundFPS(60);

    CRForgeGame game =
        aiPort > 0
            ? new CRForgeGame(aiPort)
            : new CRForgeGame(versions, first, replay, archive, fixedBy);
    if (aiPort <= 0) {
      // A replay file or a crawl's output dropped on the window opens in the replay viewer.
      config.setWindowListener(
          new Lwjgl3WindowAdapter() {
            @Override
            public void filesDropped(String[] files) {
              game.filesDropped(files);
            }
          });
    }
    new Lwjgl3Application(game, config);
  }

  /**
   * Loads the chosen game tables and prints what was loaded, or prints why nothing was.
   *
   * @param choice the tables chosen, or why there are none
   * @param out where the startup lines go
   * @param err where the failure goes
   * @return the tables, or null when none are chosen or they cannot be read
   */
  static GameTables loadTables(DataSelection.Choice choice, PrintStream out, PrintStream err) {
    if (choice.tables() == null) {
      err.println(choice.problem());
      return null;
    }
    GameTablesSetting.Configured configured = choice.tables();
    GameTables tables;
    try {
      tables = GameTables.load(configured.folder());
    } catch (RuntimeException e) {
      err.println(
          "Cannot read the game tables at "
              + configured.folder().toAbsolutePath()
              + " (from "
              + configured.source()
              + "): "
              + e.getMessage());
      if (choice.root() != null) {
        List<String> versions = DataSelection.versions(choice.root().folder());
        err.println(
            "data versions in "
                + choice.root().folder().toAbsolutePath().normalize()
                + ": "
                + (versions.isEmpty() ? "none" : String.join(", ", versions)));
      }
      return null;
    }
    for (String line : DataSelection.describeRoot(choice)) {
      out.println(line);
    }
    for (String line : GameTablesSetting.describe(configured, tables)) {
      out.println(line);
    }
    return tables;
  }

  /** The versions the screen's {@code V} key cycles through, starting on the tables loaded. */
  static DataVersions dataVersions(DataSelection.Choice choice, GameTables tables) {
    return new DataVersions(
        choice.root() == null ? null : choice.root().folder(),
        choice.root() == null ? List.of() : DataSelection.versions(choice.root().folder()),
        choice.tables().folder(),
        tables,
        choice.tables().source(),
        choice.lock() == null || choice.lock().version() == null
            ? "unknown"
            : choice.lock().version());
  }

  /**
   * The screen's first battle, on the tables loaded. The battle core refuses tables it does not
   * model as a battle on them is built; the launcher then stops with the reason, since there is no
   * battle to show yet. (On the screen, a switch to such a version is refused and the screen stays
   * on its battle.)
   *
   * @param versions the versions, on the tables loaded
   * @param err where the refusal goes
   * @return the battle, or null when it was refused
   */
  static BattleSession firstSession(DataVersions versions, PrintStream err) {
    try {
      return versions.ladder();
    } catch (RuntimeException e) {
      err.println(
          "The battle core refuses a battle on data version "
              + versions.current().version()
              + " ("
              + versions.currentFolder().toAbsolutePath().normalize()
              + "): "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage()
              + ". Pick another with "
              + DataSelection.DATA_VERSION_ARGUMENT
              + " <v> or "
              + DataSelection.DATA_VERSION_PROPERTY
              + "=<v>.");
      return null;
    }
  }

  /**
   * The replay file the arguments name with {@value #REPLAY_ARGUMENT}.
   *
   * @return the file, or empty when none is named
   * @throws IllegalArgumentException when the argument is given without a file
   */
  static Optional<Path> replayArgument(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (REPLAY_ARGUMENT.equals(args[i])) {
        if (i + 1 >= args.length || args[i + 1].isBlank()) {
          throw new IllegalArgumentException(REPLAY_ARGUMENT + " names no replay file");
        }
        return Optional.of(Paths.get(args[i + 1]));
      }
    }
    return Optional.empty();
  }

  /**
   * Opens a replay file on the tables of the data it was recorded on, and prints its description.
   *
   * <p>A replay whose capture block names a content sha other than the current tables' is read on
   * the data root's version with that sha, which becomes the current version (said in the output
   * and the data details). When no version of the root has it, or the data version was fixed at
   * launch ({@code fixedBy}), the current tables stay and the replay is refused, never played on
   * other data: the first reason says why, and the mapping's names what the replay was recorded on.
   * A replay without a block is read on the current tables, its data version marked assumed.
   *
   * @param file the replay file
   * @param versions the data versions, whose current tables may change
   * @param fixedBy the rule that fixed the data version at launch, or null when it is not fixed
   * @param out where the description goes
   * @param err where the failure goes
   * @return the replay, refused or playable, or null when the file cannot be read as JSON
   */
  static ReplayFile openReplay(
      Path file, DataVersions versions, String fixedBy, PrintStream out, PrintStream err) {
    JsonNode document;
    try {
      document = JSON.readTree(Files.readAllBytes(file));
    } catch (IOException | RuntimeException e) {
      err.println(
          "Cannot read the replay at " + file.toAbsolutePath().normalize() + ": " + e.getMessage());
      return null;
    }
    return openDocument(file, 0, document, versions, fixedBy, out, err);
  }

  /**
   * Reads a crawl's output and prints how many of its records can be opened.
   *
   * @param file the file
   * @param out where the description goes
   * @param err where the failure goes
   * @return its records, or null when the file cannot be read
   */
  static ReplayArchive openArchive(Path file, PrintStream out, PrintStream err) {
    ReplayArchive archive;
    try {
      archive = ReplayArchive.read(file);
    } catch (IOException | RuntimeException e) {
      err.println(
          "Cannot read the replays at "
              + file.toAbsolutePath().normalize()
              + ": "
              + e.getMessage());
      return null;
    }
    out.println(
        "replays: "
            + file.toAbsolutePath().normalize()
            + ", "
            + archive.entries().size()
            + " records, "
            + archive.readable()
            + " readable");
    return archive;
  }

  /**
   * Opens the first record of a crawl's output that can be read, as {@link #openEntry} does.
   *
   * @return the replay, refused or playable, or null when no record can be read
   */
  static ReplayFile openFirst(
      ReplayArchive archive,
      DataVersions versions,
      String fixedBy,
      PrintStream out,
      PrintStream err) {
    for (ReplayArchive.Entry entry : archive.entries()) {
      if (entry.problem() == null) {
        return openEntry(archive, entry, versions, fixedBy, out, err);
      }
    }
    err.println("No record of " + archive.file().toAbsolutePath().normalize() + " can be read");
    return null;
  }

  /**
   * Opens one replay of a crawl's output, as {@link #openReplay} opens a replay file: on the data
   * its record names, with the capture block built from the record.
   *
   * @param archive the crawl's output
   * @param entry the record
   * @param versions the data versions, whose current tables may change
   * @param fixedBy the rule that fixed the data version at launch, or null when it is not fixed
   * @param out where the description goes
   * @param err where the failure goes
   * @return the replay, refused or playable, or null when the record cannot be read
   */
  static ReplayFile openEntry(
      ReplayArchive archive,
      ReplayArchive.Entry entry,
      DataVersions versions,
      String fixedBy,
      PrintStream out,
      PrintStream err) {
    Path file = archive.file();
    if (entry.problem() != null) {
      err.println(
          "Cannot read line "
              + entry.line()
              + " of "
              + file.toAbsolutePath().normalize()
              + ": "
              + entry.problem());
      return null;
    }
    JsonNode document;
    try {
      document = entry.document();
    } catch (IOException | RuntimeException e) {
      err.println(
          "Cannot read line "
              + entry.line()
              + " of "
              + file.toAbsolutePath().normalize()
              + ": "
              + e.getMessage());
      return null;
    }
    return openDocument(file, entry.line(), document, versions, fixedBy, out, err);
  }

  /**
   * Opens a replay document on the tables of the data it was recorded on, and prints its
   * description (see {@link #openReplay}).
   *
   * @param file the file it came from
   * @param line its line in a crawl's output, or 0 when the file is the replay
   * @return the replay, refused or playable, or null when the mapping cannot read it at all
   */
  private static ReplayFile openDocument(
      Path file,
      int line,
      JsonNode document,
      DataVersions versions,
      String fixedBy,
      PrintStream out,
      PrintStream err) {
    String tablesRefusal = null;
    Optional<ReplayCapture> capture = ReplayCapture.of(document);
    if (capture.isPresent()
        && !capture.get().contentSha().equals(versions.current().contentSha())) {
      if (fixedBy != null) {
        tablesRefusal =
            "the data version is fixed by "
                + fixedBy
                + ", and the replay was recorded on "
                + capture.get().recordedOn()
                + "; it is not played on other data";
      } else {
        DataVersions.ContentMatch match = versions.selectContent(capture.get().contentSha());
        if (match.refusal() != null) {
          tablesRefusal = match.refusal();
        } else if (match.from() != null) {
          out.println(
              "the replay names content sha "
                  + capture.get().contentSha()
                  + ": switched from data version "
                  + match.from()
                  + " to "
                  + match.version()
                  + " ("
                  + versions.currentFolder().toAbsolutePath().normalize()
                  + ")");
        }
      }
    }
    ReplayFile replay;
    try {
      replay = ReplayFile.parse(file, line, document, versions.current(), tablesRefusal);
    } catch (RuntimeException e) {
      err.println(
          "Cannot read the replay at "
              + file.toAbsolutePath().normalize()
              + (line > 0 ? ", line " + line : "")
              + ": "
              + e.getMessage());
      return null;
    }
    for (String described : replay.describe()) {
      out.println(described);
    }
    return replay;
  }

  /**
   * Reads a replay file against the tables and prints its description: the file, its battle header,
   * and every reason it is refused, if it is. The tables are not changed for the replay's capture
   * block, which the mapping checks against them ({@link #openReplay} picks the replay's own).
   *
   * @param file the replay file
   * @param tables the game tables
   * @param out where the description goes
   * @param err where the failure goes
   * @return the replay, refused or playable, or null when the file cannot be read as JSON
   */
  static ReplayFile loadReplay(Path file, GameTables tables, PrintStream out, PrintStream err) {
    ReplayFile replay;
    try {
      replay = ReplayFile.read(file, tables);
    } catch (IOException | RuntimeException e) {
      err.println(
          "Cannot read the replay at " + file.toAbsolutePath().normalize() + ": " + e.getMessage());
      return null;
    }
    for (String line : replay.describe()) {
      out.println(line);
    }
    return replay;
  }
}
