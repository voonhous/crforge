package org.crforge.desktop;

import com.badlogic.gdx.Game;
import org.crforge.desktop.screen.DebugGameScreen;

/**
 * Main LibGDX application for CRForge. Currently launches directly into debug visualization mode.
 */
public class CRForgeGame extends Game {

  @Override
  public void create() {
    setScreen(new DebugGameScreen());
  }
}