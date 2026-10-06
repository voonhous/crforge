package org.crforge.desktop;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Screen;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.replay.ReplayFile;
import org.crforge.desktop.screen.AIGameScreen;
import org.crforge.desktop.screen.DebugGameScreen;
import org.crforge.desktop.screen.ReplayGameScreen;

/**
 * Main LibGDX application for CRForge. Launches into debug visualization mode by default, which
 * runs the battle core on the given game tables (switching between the data versions of a data
 * root), or AI visualizer mode when an AI port is specified, which runs the original engine. Given
 * a replay, it opens the replay viewer instead of the debug screen; a replay file dropped on the
 * window opens there too, read against the tables of the data it names, else the current ones.
 */
@Slf4j
public class CRForgeGame extends Game {

  private final int aiPort;

  /** The data versions the debug visualizer's battles read, or null in AI visualizer mode. */
  private final DataVersions versions;

  /** The debug visualizer's first battle, or null in AI visualizer mode and in a replay. */
  private final BattleSession first;

  /** The replay the viewer opens on, or null for the debug screen. */
  private final ReplayFile replay;

  /**
   * Debug mode: battle core battles on the current tables of the given versions.
   *
   * @param versions the data versions, on the tables loaded at startup
   * @param first the first battle, on those tables
   */
  public CRForgeGame(DataVersions versions, BattleSession first) {
    this(versions, first, null);
  }

  /**
   * Debug mode, opening on a replay when one is given.
   *
   * @param versions the data versions, on the tables loaded at startup
   * @param first the first battle on those tables, or null when a replay is given
   * @param replay the replay to open, read against those tables, or null for the debug screen
   */
  public CRForgeGame(DataVersions versions, BattleSession first, ReplayFile replay) {
    this.aiPort = -1;
    this.versions = versions;
    this.first = first;
    this.replay = replay;
  }

  /** Constructor for AI visualizer mode. */
  public CRForgeGame(int aiPort) {
    this.aiPort = aiPort;
    this.versions = null;
    this.first = null;
    this.replay = null;
  }

  @Override
  public void create() {
    if (aiPort > 0) {
      setScreen(new AIGameScreen(aiPort));
    } else if (replay != null) {
      setScreen(new ReplayGameScreen(replay, versions));
    } else {
      setScreen(new DebugGameScreen(versions, first));
    }
  }

  @Override
  public void dispose() {
    super.dispose();
    if (getScreen() != null) getScreen().dispose();
  }

  /**
   * Opens the first JSON file dropped on the window as a replay, read against the tables of the
   * data its capture block names (the root's version with its content sha, which becomes current)
   * or else the current tables, and described as {@code --replay} reads one; a file that cannot be
   * read is logged and the screen kept.
   *
   * @param files the dropped files' paths
   */
  public void filesDropped(String[] files) {
    if (versions == null) {
      return;
    }
    for (String file : files) {
      if (!file.toLowerCase().endsWith(".json")) {
        continue;
      }
      Path path = Paths.get(file);
      ReplayFile dropped = DesktopLauncher.openReplay(path, versions, null, System.out, System.err);
      if (dropped == null) {
        log.info("Dropped file {} is not a replay that can be read", path);
        return;
      }
      Screen previous = getScreen();
      setScreen(new ReplayGameScreen(dropped, versions));
      if (previous != null) {
        previous.dispose();
      }
      return;
    }
    log.info("No .json file among the dropped files");
  }
}
