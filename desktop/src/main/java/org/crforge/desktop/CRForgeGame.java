package org.crforge.desktop;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Screen;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.replay.ReplayArchive;
import org.crforge.desktop.replay.ReplayFile;
import org.crforge.desktop.screen.DebugGameScreen;
import org.crforge.desktop.screen.ReplayGameScreen;

/**
 * Main LibGDX application for CRForge. Launches into debug visualization mode, which runs the
 * battle core on the given game tables (switching between the data versions of a data root). Given
 * a replay, it opens the replay viewer instead of the debug screen; a replay file dropped on the
 * window opens there too, read against the tables of the data it names, else the current ones. A
 * crawl's output ({@link ReplayArchive}) opens the viewer on its first readable replay, with a list
 * of all of them to pick from.
 */
@Slf4j
public class CRForgeGame extends Game {

  /** The data versions the debug visualizer's battles read. */
  private final DataVersions versions;

  /** The debug visualizer's first battle, or null in a replay. */
  private final BattleSession first;

  /** The replay the viewer opens on, or null for the debug screen. */
  private final ReplayFile replay;

  /** The crawl's output the replay is from, listed by the viewer, or null for a replay file. */
  private final ReplayArchive archive;

  /** The rule that fixed the data version at launch, or null when it is not fixed. */
  private final String fixedBy;

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
    this(versions, first, replay, null, null);
  }

  /**
   * Debug mode, opening on a replay of a crawl's output when one is given.
   *
   * @param versions the data versions, on the tables loaded at startup
   * @param first the first battle on those tables, or null when a replay is given
   * @param replay the replay to open, or null for the debug screen
   * @param archive the crawl's output the replay is from, or null for a replay file
   * @param fixedBy the rule that fixed the data version at launch, or null when it is not fixed
   */
  public CRForgeGame(
      DataVersions versions,
      BattleSession first,
      ReplayFile replay,
      ReplayArchive archive,
      String fixedBy) {
    this.versions = versions;
    this.first = first;
    this.replay = replay;
    this.archive = archive;
    this.fixedBy = fixedBy;
  }

  @Override
  public void create() {
    if (replay != null) {
      setScreen(new ReplayGameScreen(replay, versions, browser(archive, fixedBy)));
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
   * Opens the first JSON file or crawl's output dropped on the window. A JSON file is read as a
   * replay, against the tables of the data its capture block names (the root's version with its
   * content sha, which becomes current) or else the current tables, and described as {@code
   * --replay} reads one. A crawl's output ({@code .jsonl} or {@code .jsonl.gz}) opens on its first
   * readable replay, with the list of all of them. A file that cannot be read is logged and the
   * screen kept.
   *
   * @param files the dropped files' paths
   */
  public void filesDropped(String[] files) {
    if (versions == null) {
      return;
    }
    for (String file : files) {
      Path path = Paths.get(file);
      ReplayGameScreen.Browser browser = null;
      ReplayFile dropped;
      if (ReplayArchive.isArchive(path)) {
        ReplayArchive archive = DesktopLauncher.openArchive(path, System.out, System.err);
        dropped =
            archive == null
                ? null
                : DesktopLauncher.openFirst(archive, versions, null, System.out, System.err);
        browser = browser(archive, null);
      } else if (file.toLowerCase().endsWith(".json")) {
        dropped = DesktopLauncher.openReplay(path, versions, null, System.out, System.err);
      } else {
        continue;
      }
      if (dropped == null) {
        log.info("Dropped file {} holds no replay that can be read", path);
        return;
      }
      Screen previous = getScreen();
      setScreen(new ReplayGameScreen(dropped, versions, browser));
      if (previous != null) {
        previous.dispose();
      }
      return;
    }
    log.info("No .json, .jsonl or .jsonl.gz file among the dropped files");
  }

  /**
   * The viewer's list of a crawl's replays, opening each on the data its record names.
   *
   * @return the list, or null for no crawl's output
   */
  private ReplayGameScreen.Browser browser(ReplayArchive archive, String fixedBy) {
    if (archive == null) {
      return null;
    }
    return new ReplayGameScreen.Browser(
        archive,
        entry ->
            DesktopLauncher.openEntry(archive, entry, versions, fixedBy, System.out, System.err));
  }
}
