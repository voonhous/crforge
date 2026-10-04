package org.crforge.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.io.PrintStream;
import java.util.Optional;
import org.crforge.core.arena.Arena;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.render.RenderConstants;

/**
 * Desktop launcher for CRForge. Starts the LibGDX application with debug visualization.
 *
 * <p>The debug visualizer runs the battle core, so it first loads the configured game tables (see
 * {@link GameTablesSetting}), prints where they came from, their data version and their content
 * hash, and stops with a message naming the settings when none are configured or they cannot be
 * read. The AI visualizer ({@code --ai-port}) still runs the original engine and reads no tables.
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

    GameTables tables = null;
    if (aiPort <= 0) {
      tables = loadTables(GameTablesSetting.resolve(), System.out, System.err);
      if (tables == null) {
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

    CRForgeGame game = aiPort > 0 ? new CRForgeGame(aiPort) : new CRForgeGame(tables);
    new Lwjgl3Application(game, config);
  }

  /**
   * Loads the configured game tables and prints what was loaded, or prints why nothing was.
   *
   * @param configured the configured folder, or empty when none is
   * @param out where the startup lines go
   * @param err where the failure goes
   * @return the tables, or null when none are configured or they cannot be read
   */
  static GameTables loadTables(
      Optional<GameTablesSetting.Configured> configured, PrintStream out, PrintStream err) {
    if (configured.isEmpty()) {
      err.println(GameTablesSetting.missingMessage());
      return null;
    }
    GameTables tables;
    try {
      tables = GameTables.load(configured.get().folder());
    } catch (RuntimeException e) {
      err.println(
          "Cannot read the game tables at "
              + configured.get().folder().toAbsolutePath()
              + " (from "
              + configured.get().source()
              + "): "
              + e.getMessage());
      return null;
    }
    for (String line : GameTablesSetting.describe(configured.get(), tables)) {
      out.println(line);
    }
    return tables;
  }
}
