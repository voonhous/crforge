package org.crforge.desktop;

import com.badlogic.gdx.Game;
import org.crforge.desktop.screen.AIGameScreen;
import org.crforge.desktop.screen.DebugGameScreen;

/**
 * Main LibGDX application for CRForge. Launches into debug visualization mode by default, or AI
 * visualizer mode when an AI port is specified.
 */
public class CRForgeGame extends Game {

  private final int aiPort;

  /** Default constructor for manual debug mode. */
  public CRForgeGame() {
    this.aiPort = -1;
  }

  /** Constructor for AI visualizer mode. */
  public CRForgeGame(int aiPort) {
    this.aiPort = aiPort;
  }

  @Override
  public void create() {
    if (aiPort > 0) {
      setScreen(new AIGameScreen(aiPort));
    } else {
      setScreen(new DebugGameScreen());
    }
  }
}
