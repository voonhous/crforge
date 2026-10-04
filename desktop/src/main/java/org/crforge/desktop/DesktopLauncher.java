package org.crforge.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.io.PrintStream;
import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.render.RenderConstants;

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
 */
public class DesktopLauncher {

  /** The exit code when the game tables are missing or unreadable. */
  static final int NO_TABLES = 1;

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
    if (aiPort <= 0) {
      DataSelection.Choice choice = DataSelection.choose(DataSelection.Settings.ofProcess(args));
      GameTables tables = loadTables(choice, System.out, System.err);
      if (tables == null) {
        System.exit(NO_TABLES);
        return;
      }
      versions = dataVersions(choice, tables);
      first = firstSession(versions, System.err);
      if (first == null) {
        System.exit(NO_TABLES);
        return;
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

    String title = aiPort > 0 ? "CRForge - AI Visualizer" : "CRForge - Debug Visualizer";
    config.setTitle(title);
    config.setWindowedMode(width, height);
    config.setResizable(false);
    config.useVsync(true);
    config.setForegroundFPS(60);

    CRForgeGame game = aiPort > 0 ? new CRForgeGame(aiPort) : new CRForgeGame(versions, first);
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
    if (choice.root() == null) {
      return new DataVersions(null, List.of(), choice.tables().folder(), tables);
    }
    return new DataVersions(
        choice.root().folder(),
        DataSelection.versions(choice.root().folder()),
        choice.tables().folder(),
        tables);
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
}
