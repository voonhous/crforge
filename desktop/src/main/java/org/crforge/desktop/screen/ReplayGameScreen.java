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
import lombok.extern.slf4j.Slf4j;
import org.crforge.desktop.battle.AreaHitLog;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.render.BattleRenderer;
import org.crforge.desktop.render.BattleWorkspace;
import org.crforge.desktop.render.GoldenOverlay;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.render.ViewState;
import org.crforge.desktop.render.WorkspaceAction;
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
 * </ul>
 */
@Slf4j
public class ReplayGameScreen implements Screen {

  /** The controls the status column lists, the replay screen's own. */
  private static final List<String> NOTES =
      List.of("SPACE pause, R restart, +/- speed", "clicks do not play in a replay");

  private final ReplayPlayer player;
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
   * A screen playing a replay from tick 0.
   *
   * @param replay the replay, refused or playable
   * @param versions the loaded tables and their provenance
   */
  public ReplayGameScreen(ReplayFile replay, DataVersions versions) {
    this.player = new ReplayPlayer(replay, versions.current());
    this.renderer = new BattleRenderer();

    this.camera = new OrthographicCamera();
    setupInput();
    workspace =
        new BattleWorkspace(camera, renderer, view, true, this::handleAction, (side, slot) -> {});
    workspace.setData(
        versions.current().version(),
        versions.source(),
        versions.currentFolder().toAbsolutePath().normalize().toString(),
        versions.current().contentSha(),
        versions.developmentVersion(),
        replay.dataLine());
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
              GoldenOverlay.none(),
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
    lines.add("Drop another replay file on the window to open it.");
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
          Click - Inspect a unit
          Drop a replay .json on the window to open it
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
