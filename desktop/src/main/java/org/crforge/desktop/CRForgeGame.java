package org.crforge.desktop;

import com.badlogic.gdx.Game;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.screen.AIGameScreen;
import org.crforge.desktop.screen.DebugGameScreen;

/**
 * Main LibGDX application for CRForge. Launches into debug visualization mode by default, which
 * runs the battle core on the given game tables, or AI visualizer mode when an AI port is
 * specified, which runs the original engine.
 */
public class CRForgeGame extends Game {

  private final int aiPort;

  /** The game tables the debug visualizer's battles read, or null in AI visualizer mode. */
  private final GameTables tables;

  /** Debug mode: battle core battles on the given tables. */
  public CRForgeGame(GameTables tables) {
    this.aiPort = -1;
    this.tables = tables;
  }

  /** Constructor for AI visualizer mode. */
  public CRForgeGame(int aiPort) {
    this.aiPort = aiPort;
    this.tables = null;
  }

  @Override
  public void create() {
    if (aiPort > 0) {
      setScreen(new AIGameScreen(aiPort));
    } else {
      setScreen(new DebugGameScreen(tables));
    }
  }
}
