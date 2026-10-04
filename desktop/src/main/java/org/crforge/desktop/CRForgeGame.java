package org.crforge.desktop;

import com.badlogic.gdx.Game;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.screen.AIGameScreen;
import org.crforge.desktop.screen.DebugGameScreen;

/**
 * Main LibGDX application for CRForge. Launches into debug visualization mode by default, which
 * runs the battle core on the given game tables (switching between the data versions of a data
 * root), or AI visualizer mode when an AI port is specified, which runs the original engine.
 */
public class CRForgeGame extends Game {

  private final int aiPort;

  /** The data versions the debug visualizer's battles read, or null in AI visualizer mode. */
  private final DataVersions versions;

  /** The debug visualizer's first battle, or null in AI visualizer mode. */
  private final BattleSession first;

  /**
   * Debug mode: battle core battles on the current tables of the given versions.
   *
   * @param versions the data versions, on the tables loaded at startup
   * @param first the first battle, on those tables
   */
  public CRForgeGame(DataVersions versions, BattleSession first) {
    this.aiPort = -1;
    this.versions = versions;
    this.first = first;
  }

  /** Constructor for AI visualizer mode. */
  public CRForgeGame(int aiPort) {
    this.aiPort = aiPort;
    this.versions = null;
    this.first = null;
  }

  @Override
  public void create() {
    if (aiPort > 0) {
      setScreen(new AIGameScreen(aiPort));
    } else {
      setScreen(new DebugGameScreen(versions, first));
    }
  }
}
