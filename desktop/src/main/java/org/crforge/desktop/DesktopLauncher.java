package org.crforge.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import org.crforge.core.arena.Arena;
import org.crforge.desktop.render.RenderConstants;

/** Desktop launcher for CRForge. Starts the LibGDX application with debug visualization. */
public class DesktopLauncher {

  public static void main(String[] args) {
    // Parse --ai-port argument
    int aiPort = -1;
    for (int i = 0; i < args.length - 1; i++) {
      if ("--ai-port".equals(args[i])) {
        aiPort = Integer.parseInt(args[i + 1]);
        break;
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

    CRForgeGame game = aiPort > 0 ? new CRForgeGame(aiPort) : new CRForgeGame();
    new Lwjgl3Application(game, config);
  }
}
