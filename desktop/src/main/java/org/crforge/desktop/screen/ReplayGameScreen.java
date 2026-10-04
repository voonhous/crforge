package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.desktop.battle.AreaHitLog;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.render.BattleRenderer;
import org.crforge.desktop.render.GoldenOverlay;
import org.crforge.desktop.render.RenderConstants;
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
  private final Vector3 touchPos = new Vector3();

  private int hoverTileX = -1;
  private int hoverTileY = -1;
  private int hoverCellX = -1;
  private int hoverCellY = -1;

  /** The area hits of the steps run since the last frame. */
  private final List<AreaHitLog.AreaHit> newAreaHits = new ArrayList<>();

  /** The stop reason last logged, so each stop is logged once. */
  private String loggedStop;

  /**
   * A screen playing a replay from tick 0.
   *
   * @param replay the replay, refused or playable
   * @param tables the tables it was read against
   */
  public ReplayGameScreen(ReplayFile replay, GameTables tables) {
    this.player = new ReplayPlayer(replay, tables);
    this.renderer = new BattleRenderer();

    TileMap tileMap = TileMap.standard1v1();
    float viewWidth = RenderConstants.unitsToPixels(tileMap.widthUnits());
    float viewHeight =
        RenderConstants.unitsToPixels(tileMap.heightUnits())
            + RenderConstants.TOP_UI_HEIGHT
            + RenderConstants.BOTTOM_UI_HEIGHT;
    this.camera = new OrthographicCamera(viewWidth, viewHeight);
    camera.position.set(viewWidth / 2, viewHeight / 2, 0);
    camera.update();
  }

  private void setupInput() {
    Gdx.input.setInputProcessor(
        new InputAdapter() {
          @Override
          public boolean keyDown(int keycode) {
            switch (keycode) {
              case Input.Keys.SPACE -> {
                player.togglePause();
                log.info("Replay {}", player.isPaused() ? "paused" : "resumed");
              }
              case Input.Keys.R -> {
                player.restart();
                newAreaHits.clear();
                loggedStop = null;
                log.info("Replay restarted from tick 0");
              }
              case Input.Keys.EQUALS, Input.Keys.PLUS -> {
                player.faster();
                log.info("Replay speed: {}x", player.getSpeed());
              }
              case Input.Keys.MINUS -> {
                player.slower();
                log.info("Replay speed: {}x", player.getSpeed());
              }
              case Input.Keys.P -> renderer.toggleDrawPaths();
              case Input.Keys.O -> renderer.toggleDrawRanges();
              case Input.Keys.D -> renderer.toggleDrawDamageNumbers();
              case Input.Keys.A -> renderer.toggleDrawAoeDamage();
              case Input.Keys.H -> renderer.toggleDrawHpNumbers();
              case Input.Keys.G -> renderer.toggleDrawCellCosts();
              case Input.Keys.N -> renderer.toggleDrawRoutes();
              default -> {
                return false;
              }
            }
            return true;
          }

          @Override
          public boolean mouseMoved(int screenX, int screenY) {
            updateHover(screenX, screenY);
            return false;
          }
        });
  }

  private void updateHover(int screenX, int screenY) {
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);
    float arenaY = touchPos.y - RenderConstants.BOTTOM_UI_HEIGHT;
    float arenaX = touchPos.x;
    hoverTileX = (int) Math.floor(arenaX / RenderConstants.TILE_PIXELS);
    hoverTileY = (int) Math.floor(arenaY / RenderConstants.TILE_PIXELS);
    hoverCellX = (int) Math.floor(arenaX / RenderConstants.CELL_PIXELS);
    hoverCellY = (int) Math.floor(arenaY / RenderConstants.CELL_PIXELS);
  }

  @Override
  public void render(float delta) {
    Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    try {
      camera.update();
      BattleSession session = player.getSession();
      if (session == null) {
        renderer.renderLines(camera, refusedLines());
        return;
      }
      player.advance(delta);
      newAreaHits.addAll(player.drainAreaHits());
      logStop();
      BattleFrame frame = BattleAdapter.frame(session);
      List<String> status = new ArrayList<>(player.statusLines());
      status.addAll(NOTES);
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
              List.of()));
      newAreaHits.clear();
    } catch (Exception e) {
      log.error("CRASH during replay loop!", e);
      Gdx.app.exit();
    }
  }

  /** What a refused replay's screen shows: its description, with every reason. */
  private List<String> refusedLines() {
    List<String> lines = new ArrayList<>();
    lines.add("REPLAY NOT PLAYED");
    lines.addAll(player.getReplay().describe());
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
          player.battleResult(),
          player.getReplay().recordedResult());
    }
  }

  @Override
  public void resize(int width, int height) {
    // No-op: fixed-size window
  }

  @Override
  public void show() {
    setupInput();
    log.info(
        """
        === CRForge Replay Viewer (battle core) ===
          SPACE - Pause/Resume
          R     - Restart the replay from tick 0
          +/-   - Speed up/slow down
          P O D A H G N - Overlays, as on the debug screen
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
    renderer.dispose();
  }
}
