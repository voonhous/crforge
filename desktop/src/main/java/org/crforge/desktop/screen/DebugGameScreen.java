package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
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

/**
 * Debug screen for visualizing a battle on the battle core: a Ladder battle between the decks of
 * {@link org.crforge.desktop.battle.BattleDecks}, its hands, elixir and clock the battle's own, and
 * each card played through the battle's play path (see {@link BattleSession}).
 *
 * <p>Controls:
 *
 * <ul>
 *   <li>SPACE: Pause/resume simulation
 *   <li>R: Reset to a new Ladder battle
 *   <li>P: Toggle heading lines (each troop's direction of travel)
 *   <li>O: Toggle attack, minimum and sight range circles
 *   <li>D: Toggle floating damage numbers
 *   <li>A: Toggle area damage indicators
 *   <li>H: Toggle HP numbers
 *   <li>M: Not offered: the battle core has one set of movement rules; logs a note
 *   <li>G: Toggle the routing cell cost overlay, read from the battle's own grid
 *   <li>N: Toggle the route, reference and state overlay
 *   <li>E: Export the recorded trajectories of every played unit to build/trajectories
 *   <li>V: Switch to the next data version of the data root and start a new Ladder battle on it; a
 *       version the battle core refuses is reported in the messages and the battle stays
 *   <li>F: Not offered here: the view flips in the replay viewer only, since this screen's clicks,
 *       hand panels and number keys play for a side as the arena is drawn standing; logs a note
 *   <li>T: Hide or show the text annotations (status column, messages)
 *   <li>1-4: Select a card from the blue player's hand
 *   <li>5-8: Select a card from the red player's hand
 *   <li>+/-: Speed up/slow down simulation
 *   <li>Click: Select a card from a hand, or play the selected one at the tile
 *   <li>Right click: Deselect
 * </ul>
 */
@Slf4j
public class DebugGameScreen implements Screen {

  private static final float SIM_SPEED_MIN = 0.25f;
  private static final float SIM_SPEED_MAX = 8f;

  /** Seconds of game time one battle step covers. */
  private static final float STEP_SECONDS = Battle.STEP_MS / 1000f;

  /** Where the trajectory export writes its files. */
  private static final Path TRAJECTORY_DIRECTORY = Path.of("build", "trajectories");

  /** The note the status column carries for the control this screen no longer offers. */
  private static final String M_NOTE = "M: n/a on the battle core";

  /** The note the status column carries for the flip this screen does not offer. */
  private static final String F_NOTE = "F: flips a replay only";

  /** The data versions {@code V} switches between, and the tables every battle reads now. */
  private final DataVersions versions;

  private final BattleRenderer renderer;
  private final OrthographicCamera camera;
  private final BattleWorkspace workspace;
  private InputAdapter controls;
  private final Vector3 touchPos = new Vector3();

  /** The view settings: standing, side 0 at the bottom; F does not flip it, T hides annotations. */
  private final ViewState view = ViewState.ladder();

  private BattleSession session;

  private float simSpeed = 1f;
  private boolean paused = false;
  private float accumulator = 0f;

  private int hoverTileX = -1;
  private int hoverTileY = -1;

  /** The routing cell under the mouse, which is four to a tile. */
  private int hoverCellX = -1;

  private int hoverCellY = -1;

  /** The area hits of the steps run since the last frame. */
  private final List<AreaHitLog.AreaHit> newAreaHits = new ArrayList<>();

  private int selectedSlot = -1;
  private int selectedSide = -1;

  /**
   * A screen on the current tables of the given versions, starting with the given Ladder battle.
   *
   * @param versions the data versions, whose current tables every battle of the screen reads
   * @param first the first battle, on those tables
   */
  public DebugGameScreen(DataVersions versions, BattleSession first) {
    this.versions = versions;
    this.renderer = new BattleRenderer();

    this.camera = new OrthographicCamera();

    this.session = first;
    setupInput();
    workspace =
        new BattleWorkspace(camera, renderer, view, false, this::handleAction, this::selectCard);
    workspace.configureVersions(
        versions.versions(),
        versions.current().version(),
        version -> switchDataVersion(versions.select(version)));
  }

  private boolean handleAction(WorkspaceAction action) {
    switch (action) {
      case PAUSE -> paused = !paused;
      case STEP -> stepOnce();
      case TOGGLE_INSPECT -> {
        deselect();
        workspace.setInspecting(!workspace.isInspecting());
      }
      case DEPLOY -> {
        deselect();
        workspace.setInspecting(false);
      }
      case INSPECT -> {
        deselect();
        workspace.setInspecting(true);
      }
      case RESTART -> resetBattle();
      case HEADINGS -> {
        renderer.toggleDrawPaths();
        log.info("Heading lines: {}", renderer.isDrawPaths() ? "ON" : "OFF");
      }
      case RANGES -> {
        renderer.toggleDrawRanges();
        log.info("Range circles: {}", renderer.isDrawRanges() ? "ON" : "OFF");
      }
      case DAMAGE -> {
        renderer.toggleDrawDamageNumbers();
        log.info("Damage numbers: {}", renderer.isDrawDamageNumbers() ? "ON" : "OFF");
      }
      case AREA_HITS -> {
        renderer.toggleDrawAoeDamage();
        log.info("AOE damage indicators: {}", renderer.isDrawAoeDamage() ? "ON" : "OFF");
      }
      case HP -> {
        renderer.toggleDrawHpNumbers();
        log.info("HP numbers: {}", renderer.isDrawHpNumbers() ? "ON" : "OFF");
      }
      case LEGACY_PATHFINDING ->
          log.info(
              "M flips the original engine's pathfinding mode; the battle core has one set"
                  + " of movement rules, so there is nothing to flip");
      case CELL_COSTS -> {
        renderer.toggleDrawCellCosts();
        log.info("Cell cost overlay: {}", renderer.isDrawCellCosts() ? "ON" : "OFF");
      }
      case ROUTES -> {
        renderer.toggleDrawRoutes();
        log.info("Route overlay: {}", renderer.isDrawRoutes() ? "ON" : "OFF");
      }
      case FLIP -> {
        if (!view.flip()) {
          log.info(
              "F flips the view in the replay viewer only: this screen's clicks, hand"
                  + " panels and number keys play for a side as the arena stands");
        }
      }
      case SIDEBAR -> {
        view.toggleAnnotations();
        log.info("Annotations: {}", view.isAnnotations() ? "ON" : "OFF");
      }
      case EXPORT -> exportTrajectories();
      case NEXT_VERSION -> switchDataVersion();
      case FASTER -> adjustSpeed(2f);
      case SLOWER -> adjustSpeed(0.5f);

      // Select card from hand (Blue Player) via keyboard
      case CARD_1 -> selectCard(0, 0);
      case CARD_2 -> selectCard(0, 1);
      case CARD_3 -> selectCard(0, 2);
      case CARD_4 -> selectCard(0, 3);

      // Select card from hand (Red Player) via keyboard
      case CARD_5 -> selectCard(1, 0);
      case CARD_6 -> selectCard(1, 1);
      case CARD_7 -> selectCard(1, 2);
      case CARD_8 -> selectCard(1, 3);

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
            if (button == Input.Buttons.LEFT) {
              handleLeftClick(screenX, screenY);
            } else if (button == Input.Buttons.RIGHT) {
              deselect();
            }
            return true;
          }
        };
  }

  private void updateHover(int screenX, int screenY) {
    if (!workspace.onArena(screenX, screenY)) {
      hoverTileX = hoverTileY = hoverCellX = hoverCellY = -1;
      return;
    }
    touchPos.set(screenX, screenY, 0);
    workspace.unproject(touchPos);

    // touchPos.y is world Y (0 is bottom of UI); the arena starts at BOTTOM_UI_HEIGHT. The view
    // maps the pixel to the battle's tile, which is the pixel's own on this standing screen.
    ViewOrientation orientation = view.getOrientation();
    hoverTileX = orientation.tileColumnAt(touchPos.x);
    hoverTileY = orientation.tileRowAt(touchPos.y);

    // Routing cells are half a tile across, so the cost overlay needs its own hover
    hoverCellX = orientation.cellColumnAt(touchPos.x);
    hoverCellY = orientation.cellRowAt(touchPos.y);
  }

  /** Whether the hovered tile lies on the arena. */
  private boolean hoverOnArena() {
    TileMap tileMap = session.getBattle().getWorld().getTileMap();
    return hoverTileX >= 0
        && hoverTileY >= 0
        && hoverTileX < tileMap.widthUnits() / GameUnits.UNITS_PER_TILE
        && hoverTileY < tileMap.heightUnits() / GameUnits.UNITS_PER_TILE;
  }

  private void handleLeftClick(int screenX, int screenY) {
    if (!workspace.onArena(screenX, screenY)) return;
    touchPos.set(screenX, screenY, 0);
    workspace.unproject(touchPos);
    if (workspace.isInspecting()) {
      workspace.inspectAt(touchPos.x, touchPos.y);
      return;
    }

    // 2. Play the selected card at the clicked tile's centre, in game units
    updateHover(screenX, screenY);
    if (selectedSlot < 0 || !hoverOnArena()) {
      return;
    }
    boolean played =
        session.play(
            selectedSide,
            selectedSlot,
            GameUnits.tileCenter(hoverTileX),
            GameUnits.tileCenter(hoverTileY));
    logLatestMessage();
    if (played) {
      // Deselect after a play that was given (matches real CR)
      deselect();
    }
  }

  private void selectCard(int side, int slot) {
    String unavailable = session.cardUnavailableReason(side, slot);
    if (unavailable != null) {
      log.info("[tick {}] {}", session.tick(), unavailable);
      return;
    }
    MatchCard card = session.handCard(side, slot);
    this.selectedSide = side;
    this.selectedSlot = slot;
    workspace.setInspecting(false);
    log.info(
        "[tick {}] Selected ({}): {} (cost {})",
        session.tick(),
        sideName(side),
        card.name(),
        card.cost());
  }

  private void deselect() {
    selectedSlot = -1;
    selectedSide = -1;
  }

  private static String sideName(int side) {
    return BattleSession.sideName(side);
  }

  private void adjustSpeed(float factor) {
    simSpeed = Math.max(SIM_SPEED_MIN, Math.min(SIM_SPEED_MAX, simSpeed * factor));
    log.info("Simulation speed: {}x", simSpeed);
  }

  /** Starts a new Ladder battle: the same decks, dealt and played the same way as the last. */
  private void resetBattle() {
    startSession(versions.ladder());
    log.info("Battle reset");
  }

  /** Shows a new battle from its first step, clearing what the last one left on the screen. */
  private void startSession(BattleSession next) {
    workspace.reset();
    session = next;
    newAreaHits.clear();
    deselect();
    accumulator = 0f;
    paused = false;
  }

  /**
   * Switches to the data root's next data version and starts a Ladder battle on its tables. When
   * its tables cannot be read or the battle core refuses a battle on them, the battle on screen
   * stays and the refusal joins its messages; V again tries the version after it.
   */
  private void switchDataVersion() {
    switchDataVersion(versions.next());
  }

  private void switchDataVersion(DataVersions.Switched switched) {
    if (switched.session() == null) {
      session.note(switched.refusal());
      log.warn("Data version switch refused: {}", switched.refusal());
      return;
    }
    startSession(switched.session());
    workspace.versionChanged(switched.version());
    session.note(
        "data version "
            + versions.current().version()
            + " from "
            + versions.currentFolder().toAbsolutePath().normalize());
    log.info(
        "Data version {} ({}), content sha {}",
        versions.current().version(),
        versions.currentFolder().toAbsolutePath().normalize(),
        versions.current().contentSha());
  }

  /** Writes one file per recorded unit and logs where they went. */
  private void exportTrajectories() {
    try {
      List<Path> written = session.getTrajectories().export(TRAJECTORY_DIRECTORY);
      if (written.isEmpty()) {
        log.info("No trajectories recorded yet: play a card first");
        return;
      }
      for (Path file : written) {
        log.info("Wrote trajectory {}", file.toAbsolutePath());
      }
    } catch (IOException e) {
      log.error("Failed to export trajectories", e);
    }
  }

  private void logLatestMessage() {
    List<String> messages = session.messages();
    if (!messages.isEmpty()) {
      log.info(messages.get(messages.size() - 1));
    }
  }

  /** Pauses and advances exactly one tick, including all diagnostics from normal playback. */
  private void stepOnce() {
    paused = true;
    accumulator = 0;
    if (session.getHalted() != null || session.isOver()) return;
    if (!session.step()) logLatestMessage();
    newAreaHits.addAll(session.getAreaHits().drain());
  }

  @Override
  public void render(float delta) {
    // Clear screen
    var background = BattleWorkspace.background();
    Gdx.gl.glClearColor(background.r, background.g, background.b, 1);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    try {
      // Fixed timestep simulation: a frame covers several steps at high speeds and none at low
      // ones.
      if (!paused && session.getHalted() == null && !session.isOver()) {
        accumulator += delta * simSpeed;
        while (accumulator >= STEP_SECONDS) {
          accumulator -= STEP_SECONDS;
          boolean stepped = session.step();
          newAreaHits.addAll(session.getAreaHits().drain());
          if (!stepped) {
            logLatestMessage();
            accumulator = 0f;
            break;
          }
        }
      }

      camera.update();
      workspace.setData(
          versions.current().version(),
          versions.source(),
          versions.currentFolder().toAbsolutePath().normalize().toString(),
          versions.current().contentSha(),
          versions.developmentVersion());
      BattleFrame frame = BattleAdapter.frame(session, view.getOrientation()::sideName);
      List<String> status = new ArrayList<>();
      if (renderer.isDrawCellCosts()) {
        status.add(
            renderer.hoveredCellStatus(session.getBattle().getWorld(), hoverCellX, hoverCellY));
      }
      workspace.update(
          frame,
          paused,
          simSpeed,
          session.isOver() || session.getHalted() != null,
          null,
          status,
          selectedSide,
          selectedSlot,
          -1);
      workspace.beginArena();
      updateHover(Gdx.input.getX(), Gdx.input.getY());
      boolean previewing = !workspace.isInspecting() && selectedSlot >= 0 && hoverOnArena();
      MatchCard selected = selectedSlot >= 0 ? session.handCard(selectedSide, selectedSlot) : null;
      DeployCard selectedCard = session.deployCard(selected);
      CardPlacement.Result preview =
          previewing
              ? session.preview(
                  selectedSide,
                  selectedSlot,
                  GameUnits.tileCenter(hoverTileX),
                  GameUnits.tileCenter(hoverTileY))
              : null;
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
              selectedSide,
              selectedSlot,
              selectedCard,
              preview,
              List.of(),
              List.of(versions.statusLine(), M_NOTE, F_NOTE),
              view),
          false);
      newAreaHits.clear();
      workspace.draw(delta);
    } catch (Exception e) {
      log.error("CRASH during game loop!", e);
      paused = true;
      Gdx.app.exit();
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
        === CRForge Debug Visualizer (battle core) ===
        Battle:
          SPACE - Pause/Resume
          .     - Pause and advance one tick
          R     - Reset to a new Ladder battle
          +/-   - Speed up/slow down

        Cards:
          1-4   - Select blue card
          5-8   - Select red card
          Click - Select a card, or play the selected one
          Right click - Deselect
          I     - Toggle unit inspection

        Combat overlays:
          O     - Toggle attack, minimum and sight range circles
          D     - Toggle floating damage numbers
          A     - Toggle area damage indicators
          H     - Toggle HP numbers

        Pathing:
          P     - Toggle heading lines
          M     - Not offered on the battle core (one set of movement rules)
          G     - Toggle routing cell cost overlay
          N     - Toggle route / reference / state overlay
          E     - Export played units' trajectories to build/trajectories

        Data:
          V     - Switch to the next data version of the data root (new Ladder battle)

        View:
          F     - Not offered here: flips the replay viewer only
          T     - Hide/show the diagnostics sidebar
        ==============================================""");
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
