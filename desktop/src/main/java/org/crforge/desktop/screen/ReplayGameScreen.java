package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.crforge.desktop.battle.AreaHitLog;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.render.BattleRenderer;
import org.crforge.desktop.render.BattleWorkspace;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.render.ViewState;
import org.crforge.desktop.render.WorkspaceAction;
import org.crforge.desktop.replay.ReplayArchive;
import org.crforge.desktop.replay.ReplayFile;
import org.crforge.desktop.replay.ReplayPlayer;

/**
 * Plays a replay on the battle core ({@link ReplayPlayer}): both sides' hands, elixir and clock are
 * the battle's, and the replay's own plays run at their ticks, each noted in the message column.
 * Cards are not selected or played from the screen. A replay the mapping or the battle core refuses
 * is not played: the screen lists every reason instead.
 *
 * <p>Controls:
 *
 * <ul>
 *   <li>SPACE: Pause/resume
 *   <li>R: Restart the replay from tick 0
 *   <li>+/-: Speed up/slow down
 *   <li>P, O, D, A, H, G, N: The overlays, as on the debug screen
 *   <li>F: Flip the view. A replay opens flipped, side 1 at the bottom in blue and side 0 at the
 *       top in red; F turns the arena, every overlay and the HUD by 180 degrees back and forth. The
 *       battle's sides are the replay's either way: only the drawing changes.
 *   <li>T: Hide or show the text annotations (status column, messages)
 *   <li>L: Show or hide the list of a crawl's replays, when the replay is one of them
 *   <li>[ and ]: Open the crawl's previous or next replay
 * </ul>
 *
 * <p>A replay from a crawl's output ({@link ReplayArchive}) comes with the list of all its replays
 * above the arena: a click on one opens it in place of the replay open, on the data its record
 * names, and a record that cannot be read says why under the list's title.
 */
@Slf4j
public class ReplayGameScreen implements Screen {

  /** The controls the status column lists, the replay screen's own. */
  private static final List<String> NOTES =
      List.of("SPACE pause, R restart, +/- speed", "clicks do not play in a replay");

  private ReplayPlayer player;
  private final DataVersions versions;
  private final Browser browser;

  /** The index of the open replay among the crawl's records, or -1 for a replay file. */
  private int selected = -1;

  private final BattleRenderer renderer;
  private final OrthographicCamera camera;
  private final BattleWorkspace workspace;
  private InputAdapter controls;
  private final Vector3 touchPos = new Vector3();

  /** The view settings: flipped at first, F flips them, T hides the annotations. */
  private final ViewState view = ViewState.replay();

  private int hoverTileX = -1;
  private int hoverTileY = -1;
  private int hoverCellX = -1;
  private int hoverCellY = -1;

  /** The mouse's last window position, to find its tile again after a flip; -1 before any move. */
  private int mouseX = -1;

  private int mouseY = -1;

  /** The area hits of the steps run since the last frame. */
  private final List<AreaHitLog.AreaHit> newAreaHits = new ArrayList<>();

  /** The stop reason last logged, so each stop is logged once. */
  private String loggedStop;

  /**
   * A crawl's output the screen lists, and what opens one of its replays: on the data its record
   * names, or null when the record cannot be read (the opener prints why).
   *
   * @param archive the crawl's output
   * @param open what opens a record's replay
   */
  public record Browser(ReplayArchive archive, Function<ReplayArchive.Entry, ReplayFile> open) {}

  /**
   * A screen playing a replay from tick 0.
   *
   * @param replay the replay, refused or playable
   * @param versions the loaded tables and their provenance
   */
  public ReplayGameScreen(ReplayFile replay, DataVersions versions) {
    this(replay, versions, null);
  }

  /**
   * A screen playing a replay from tick 0, listing the crawl's replays when it is one of them.
   *
   * @param replay the replay, refused or playable
   * @param versions the loaded tables and their provenance
   * @param browser the crawl's output the replay is from, or null for a replay file
   */
  public ReplayGameScreen(ReplayFile replay, DataVersions versions, Browser browser) {
    this.player = new ReplayPlayer(replay, versions.current());
    this.versions = versions;
    this.browser = browser;
    this.renderer = new BattleRenderer();

    this.camera = new OrthographicCamera();
    setupInput();
    workspace =
        new BattleWorkspace(camera, renderer, view, true, this::handleAction, (side, slot) -> {});
    showData();
    if (browser != null) {
      ReplayArchive archive = browser.archive();
      selected = indexOf(replay);
      workspace.showArchive(
          "REPLAYS  "
              + archive.file().getFileName()
              + "  ("
              + archive.entries().size()
              + " records, "
              + archive.readable()
              + " readable)",
          ReplayArchive.columns(),
          archive.rows(versions.current()),
          selected,
          this::open);
      workspace.archiveStatus(openLine());
    }
  }

  /** The data line of the tables loaded, with the open replay's. */
  private void showData() {
    workspace.setData(
        versions.current().version(),
        versions.source(),
        versions.currentFolder().toAbsolutePath().normalize().toString(),
        versions.current().contentSha(),
        versions.developmentVersion(),
        player.getReplay().dataLine());
  }

  /** The index of a replay's record among the crawl's, or -1. */
  private int indexOf(ReplayFile replay) {
    List<ReplayArchive.Entry> entries = browser.archive().entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).line() == replay.line()) return i;
    }
    return -1;
  }

  /** What the list's status line says of the replay open. */
  private String openLine() {
    ReplayFile replay = player.getReplay();
    return "Open: line "
        + replay.line()
        + (replay.playable() ? ", playing" : ", refused")
        + "    Click a row to open it, [ and ] for the previous and next, L hides the list";
  }

  /**
   * Opens one of the crawl's replays in place of the replay open, on the data its record names. A
   * record that cannot be read leaves the replay open and says why.
   *
   * @param index the record's index
   */
  private void open(int index) {
    List<ReplayArchive.Entry> entries = browser.archive().entries();
    if (index < 0 || index >= entries.size()) return;
    ReplayArchive.Entry entry = entries.get(index);
    ReplayFile replay = browser.open().apply(entry);
    if (replay == null) {
      workspace.selectArchiveRow(selected);
      workspace.archiveStatus(
          "Line "
              + entry.line()
              + " cannot be read: "
              + (entry.problem() == null ? "see the log" : entry.problem()));
      return;
    }
    player = new ReplayPlayer(replay, versions.current());
    selected = index;
    workspace.reset();
    newAreaHits.clear();
    loggedStop = null;
    showData();
    workspace.selectArchiveRow(index);
    workspace.archiveStatus(openLine());
    log.info("Opened {} on data version {}", replay.name(), versions.current().version());
  }

  private boolean handleAction(WorkspaceAction action) {
    switch (action) {
      case PAUSE -> {
        player.togglePause();
        log.info("Replay {}", player.isPaused() ? "paused" : "resumed");
      }
      case RESTART -> {
        player.restart();
        workspace.reset();
        newAreaHits.clear();
        loggedStop = null;
        log.info("Replay restarted from tick 0");
      }
      case STEP -> player.stepOnce();
      case FASTER -> {
        player.faster();
        log.info("Replay speed: {}x", player.getSpeed());
      }
      case SLOWER -> {
        player.slower();
        log.info("Replay speed: {}x", player.getSpeed());
      }
      case HEADINGS -> renderer.toggleDrawPaths();
      case RANGES -> renderer.toggleDrawRanges();
      case DAMAGE -> renderer.toggleDrawDamageNumbers();
      case AREA_HITS -> renderer.toggleDrawAoeDamage();
      case HP -> renderer.toggleDrawHpNumbers();
      case CELL_COSTS -> renderer.toggleDrawCellCosts();
      case ROUTES -> renderer.toggleDrawRoutes();
      case FLIP -> {
        view.flip();
        if (mouseX >= 0) {
          // The mouse has not moved, but the battle's tile under it has.
          updateHover(mouseX, mouseY);
        }
        log.info("View: side {} at the bottom", view.getOrientation().bottomSide());
      }
      case SIDEBAR -> {
        view.toggleAnnotations();
        log.info("Annotations: {}", view.isAnnotations() ? "ON" : "OFF");
      }
      case REPLAYS -> workspace.toggleArchive();
      case PREVIOUS_REPLAY -> {
        if (browser == null) return false;
        open(selected - 1);
      }
      case NEXT_REPLAY -> {
        if (browser == null) return false;
        open(selected + 1);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  private void setupInput() {
    controls =
        new InputAdapter() {
          @Override
          public boolean keyDown(int keycode) {
            WorkspaceAction action = WorkspaceAction.fromKey(keycode);
            return action != null && handleAction(action);
          }

          @Override
          public boolean mouseMoved(int screenX, int screenY) {
            updateHover(screenX, screenY);
            return false;
          }

          @Override
          public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button != Input.Buttons.LEFT || !workspace.onArena(screenX, screenY)) return false;
            touchPos.set(screenX, screenY, 0);
            workspace.unproject(touchPos);
            workspace.inspectAt(touchPos.x, touchPos.y);
            return true;
          }
        };
  }

  private void updateHover(int screenX, int screenY) {
    mouseX = screenX;
    mouseY = screenY;
    if (!workspace.onArena(screenX, screenY)) {
      hoverTileX = hoverTileY = hoverCellX = hoverCellY = -1;
      return;
    }
    touchPos.set(screenX, screenY, 0);
    workspace.unproject(touchPos);
    // The battle's tile and cell under the mouse, whichever way up the arena is drawn.
    ViewOrientation orientation = view.getOrientation();
    hoverTileX = orientation.tileColumnAt(touchPos.x);
    hoverTileY = orientation.tileRowAt(touchPos.y);
    hoverCellX = orientation.cellColumnAt(touchPos.x);
    hoverCellY = orientation.cellRowAt(touchPos.y);
  }

  @Override
  public void render(float delta) {
    var background = BattleWorkspace.background();
    Gdx.gl.glClearColor(background.r, background.g, background.b, 1);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    try {
      camera.update();
      BattleSession session = player.getSession();
      if (session == null) {
        workspace.showRefused(refusedLines());
        workspace.beginArena();
        workspace.draw(delta);
        return;
      }
      player.advance(delta);
      newAreaHits.addAll(player.drainAreaHits());
      logStop();
      ViewOrientation orientation = view.getOrientation();
      BattleFrame frame = BattleAdapter.frame(session, orientation::sideName);
      List<String> status = new ArrayList<>(player.statusLines(orientation));
      status.addAll(NOTES);
      if (renderer.isDrawCellCosts()) {
        status.add(
            renderer.hoveredCellStatus(session.getBattle().getWorld(), hoverCellX, hoverCellY));
      }
      workspace.update(
          frame,
          player.isPaused(),
          player.getSpeed(),
          player.finished(),
          player.stopReason(),
          status,
          -1,
          -1,
          player.getReplay().header().endTick());
      workspace.beginArena();
      updateHover(Gdx.input.getX(), Gdx.input.getY());
      renderer.render(
          frame,
          camera,
          new BattleRenderer.Inputs(
              session.getBattle().getWorld(),
              List.copyOf(newAreaHits),
              hoverTileX,
              hoverTileY,
              hoverCellX,
              hoverCellY,
              -1,
              -1,
              null,
              null,
              status,
              List.of(),
              view),
          false);
      newAreaHits.clear();
      workspace.draw(delta);
    } catch (Exception e) {
      log.error("CRASH during replay loop!", e);
      Gdx.app.exit();
    }
  }

  /** What a refused replay's screen shows: its description, with every reason. */
  private List<String> refusedLines() {
    List<String> lines = new ArrayList<>();
    lines.add("REPLAY NOT PLAYED");
    lines.addAll(player.getReplay().refusals());
    for (String line : player.getReplay().describe(view.getOrientation())) {
      if (line.startsWith("  refused,")) break;
      lines.add(line);
    }
    lines.add("");
    lines.add(
        browser == null
            ? "Drop another replay file on the window to open it."
            : "Pick another replay from the list [L], or drop another file on the window.");
    return lines;
  }

  /** Logs why the replay stopped, once per stop, with the two results. */
  private void logStop() {
    String stop = player.stopReason();
    if (stop != null && !stop.equals(loggedStop)) {
      loggedStop = stop;
      log.info(
          "Replay stopped: {}; battle result: {}; recorded result: {}",
          stop,
          player.battleResult(view.getOrientation()),
          player.getReplay().recordedResult());
    }
  }

  @Override
  public void resize(int width, int height) {
    workspace.resize(width, height);
  }

  @Override
  public void show() {
    Gdx.input.setInputProcessor(new InputMultiplexer(workspace.input(), controls));
    log.info(
        """
        === CRForge Replay Viewer (battle core) ===
          SPACE - Pause/Resume
          .     - Pause and advance one tick
          R     - Restart the replay from tick 0
          +/-   - Speed up/slow down
          P O D A H G N - Overlays, as on the debug screen
          F     - Flip the view (opens with side 1 at the bottom)
          T     - Hide/show the diagnostics sidebar
          L     - Hide/show the list of a crawl's replays
          [ ]   - Open the crawl's previous/next replay
          Click - Inspect a unit
          Drop a replay .json, or a crawl's .jsonl or .jsonl.gz, on the window to open it
        ===========================================""");
  }

  @Override
  public void hide() {}

  @Override
  public void pause() {}

  @Override
  public void resume() {}

  @Override
  public void dispose() {
    workspace.dispose();
    renderer.dispose();
  }
}
