package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
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
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.GoldenScenario;
import org.crforge.desktop.battle.AreaHitLog;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.render.BattleRenderer;
import org.crforge.desktop.render.CardLayout;
import org.crforge.desktop.render.GoldenOverlay;
import org.crforge.desktop.render.RenderConstants;

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
 *   <li>S: Run the next golden scenario (passive towers, the reference unit placed on tick 0)
 *   <li>E: Export the recorded trajectories of every played unit to build/trajectories
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
  private static final List<String> NOTES = List.of("M: n/a on the battle core");

  private final GameTables tables;
  private final BattleRenderer renderer;
  private final OrthographicCamera camera;
  private final Vector3 touchPos = new Vector3();

  private BattleSession session;

  private float simSpeed = 1f;
  private boolean paused = false;
  private float accumulator = 0f;

  private int hoverTileX = -1;
  private int hoverTileY = -1;

  /** The routing cell under the mouse, which is four to a tile. */
  private int hoverCellX = -1;

  private int hoverCellY = -1;

  private final GoldenScenario goldenScenario = new GoldenScenario();

  /** The area hits of the steps run since the last frame. */
  private final List<AreaHitLog.AreaHit> newAreaHits = new ArrayList<>();

  private int selectedSlot = -1;
  private int selectedSide = -1;

  /**
   * A screen on the given tables, starting with a Ladder battle.
   *
   * @param tables the game tables every battle of the screen reads
   */
  public DebugGameScreen(GameTables tables) {
    this.tables = tables;
    this.renderer = new BattleRenderer();

    // Viewport includes UI margins
    TileMap tileMap = TileMap.standard1v1();
    float viewWidth = RenderConstants.unitsToPixels(tileMap.widthUnits());
    float viewHeight =
        RenderConstants.unitsToPixels(tileMap.heightUnits())
            + RenderConstants.TOP_UI_HEIGHT
            + RenderConstants.BOTTOM_UI_HEIGHT;

    this.camera = new OrthographicCamera(viewWidth, viewHeight);
    camera.position.set(viewWidth / 2, viewHeight / 2, 0);
    camera.update();

    this.session = BattleSession.ladder(tables);
    setupInput();
  }

  private void setupInput() {
    Gdx.input.setInputProcessor(
        new InputAdapter() {
          @Override
          public boolean keyDown(int keycode) {
            switch (keycode) {
              case Input.Keys.SPACE -> paused = !paused;
              case Input.Keys.R -> resetBattle();
              case Input.Keys.P -> {
                renderer.toggleDrawPaths();
                log.info("Heading lines: {}", renderer.isDrawPaths() ? "ON" : "OFF");
              }
              case Input.Keys.O -> {
                renderer.toggleDrawRanges();
                log.info("Range circles: {}", renderer.isDrawRanges() ? "ON" : "OFF");
              }
              case Input.Keys.D -> {
                renderer.toggleDrawDamageNumbers();
                log.info("Damage numbers: {}", renderer.isDrawDamageNumbers() ? "ON" : "OFF");
              }
              case Input.Keys.A -> {
                renderer.toggleDrawAoeDamage();
                log.info("AOE damage indicators: {}", renderer.isDrawAoeDamage() ? "ON" : "OFF");
              }
              case Input.Keys.H -> {
                renderer.toggleDrawHpNumbers();
                log.info("HP numbers: {}", renderer.isDrawHpNumbers() ? "ON" : "OFF");
              }
              case Input.Keys.M ->
                  log.info(
                      "M flips the original engine's pathfinding mode; the battle core has one set"
                          + " of movement rules, so there is nothing to flip");
              case Input.Keys.G -> {
                renderer.toggleDrawCellCosts();
                log.info("Cell cost overlay: {}", renderer.isDrawCellCosts() ? "ON" : "OFF");
              }
              case Input.Keys.N -> {
                renderer.toggleDrawRoutes();
                log.info("Route overlay: {}", renderer.isDrawRoutes() ? "ON" : "OFF");
              }
              case Input.Keys.S -> startGoldenScenario();
              case Input.Keys.E -> exportTrajectories();
              case Input.Keys.EQUALS, Input.Keys.PLUS -> adjustSpeed(2f);
              case Input.Keys.MINUS -> adjustSpeed(0.5f);

              // Select card from hand (Blue Player) via keyboard
              case Input.Keys.NUM_1 -> selectCard(0, 0);
              case Input.Keys.NUM_2 -> selectCard(0, 1);
              case Input.Keys.NUM_3 -> selectCard(0, 2);
              case Input.Keys.NUM_4 -> selectCard(0, 3);

              // Select card from hand (Red Player) via keyboard
              case Input.Keys.NUM_5 -> selectCard(1, 0);
              case Input.Keys.NUM_6 -> selectCard(1, 1);
              case Input.Keys.NUM_7 -> selectCard(1, 2);
              case Input.Keys.NUM_8 -> selectCard(1, 3);

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

          @Override
          public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button == Input.Buttons.LEFT) {
              handleLeftClick(screenX, screenY);
            } else if (button == Input.Buttons.RIGHT) {
              deselect();
            }
            return true;
          }
        });
  }

  private void updateHover(int screenX, int screenY) {
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);

    // touchPos.y is world Y (0 is bottom of UI); the arena starts at BOTTOM_UI_HEIGHT
    float arenaY = touchPos.y - RenderConstants.BOTTOM_UI_HEIGHT;
    float arenaX = touchPos.x;

    hoverTileX = (int) Math.floor(arenaX / RenderConstants.TILE_PIXELS);
    hoverTileY = (int) Math.floor(arenaY / RenderConstants.TILE_PIXELS);

    // Routing cells are half a tile across, so the cost overlay needs its own hover
    hoverCellX = (int) Math.floor(arenaX / RenderConstants.CELL_PIXELS);
    hoverCellY = (int) Math.floor(arenaY / RenderConstants.CELL_PIXELS);
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
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);

    // 1. Card selection: the bottom panel is blue's hand, the top panel red's
    if (touchPos.y < RenderConstants.BOTTOM_UI_HEIGHT) {
      checkHandSelection(touchPos.x, touchPos.y, 0, false);
      return;
    }
    float topUiStart = camera.viewportHeight - RenderConstants.TOP_UI_HEIGHT;
    if (touchPos.y > topUiStart) {
      checkHandSelection(touchPos.x, touchPos.y, 1, true);
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

  private void checkHandSelection(float worldX, float worldY, int side, boolean isTop) {
    int index =
        CardLayout.hitTest(worldX, worldY, isTop, camera.viewportWidth, camera.viewportHeight);
    if (index != -1) {
      selectCard(side, index);
    }
  }

  private void selectCard(int side, int slot) {
    MatchCard card = session.handCard(side, slot);
    if (card == null) {
      log.info("[tick {}] No card in {} slot {}", session.tick(), sideName(side), slot + 1);
      return;
    }
    this.selectedSide = side;
    this.selectedSlot = slot;
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
    session = BattleSession.ladder(tables);
    goldenScenario.clear();
    newAreaHits.clear();
    deselect();
    accumulator = 0f;
    paused = false;
    log.info("Battle reset");
  }

  /**
   * Starts the next golden scenario on a battle of its own: the towers passive and the reference
   * unit placed on tick 0, so the battle's first step is the reference's tick 0.
   */
  private void startGoldenScenario() {
    GoldenScenario.Case scenarioCase = GoldenScenario.load(goldenScenario.nextCaseName());
    session = BattleSession.scenario(tables, scenarioCase);
    goldenScenario.begin(scenarioCase, session.tick());
    newAreaHits.clear();
    deselect();
    accumulator = 0f;
    paused = false;
    log.info(
        "Golden scenario {}: {} for side {} at ({}, {})",
        scenarioCase.name(),
        scenarioCase.card(),
        scenarioCase.side(),
        scenarioCase.deployX(),
        scenarioCase.deployY());
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

  /** The golden scenario's trajectory as the renderer needs it. */
  private GoldenOverlay goldenOverlay() {
    if (!goldenScenario.isActive()) {
      return GoldenOverlay.none();
    }
    int tick = goldenScenario.referenceTick(session.tick());
    return new GoldenOverlay(
        goldenScenario.goldenPath(),
        goldenScenario.goldenAt(tick),
        goldenScenario.deviationPoint());
  }

  /** Compares the golden scenario's unit with the reference trajectory for the step just run. */
  private void sampleGoldenScenario() {
    CharacterEntity unit = session.getScenarioUnit();
    if (!goldenScenario.isActive() || unit == null) {
      return;
    }
    goldenScenario.sample(session.tick(), unit.getView().getX(), unit.getView().getY());
  }

  private void logLatestMessage() {
    List<String> messages = session.messages();
    if (!messages.isEmpty()) {
      log.info(messages.get(messages.size() - 1));
    }
  }

  @Override
  public void render(float delta) {
    // Clear screen
    Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    try {
      // Fixed timestep simulation. Sampling happens inside this loop, not once a frame: a frame
      // covers several steps at high speeds and none at low ones.
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
          sampleGoldenScenario();
        }
      }

      camera.update();
      BattleFrame frame = BattleAdapter.frame(session);
      boolean previewing = selectedSlot >= 0 && hoverOnArena();
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
              goldenOverlay(),
              goldenScenario.statusLines(),
              NOTES));
      newAreaHits.clear();
    } catch (Exception e) {
      log.error("CRASH during game loop!", e);
      paused = true;
      Gdx.app.exit();
    }
  }

  @Override
  public void resize(int width, int height) {
    // No-op: fixed-size debug window
  }

  @Override
  public void show() {
    log.info(
        """
        === CRForge Debug Visualizer (battle core) ===
        Battle:
          SPACE - Pause/Resume
          R     - Reset to a new Ladder battle
          +/-   - Speed up/slow down

        Cards:
          1-4   - Select blue card
          5-8   - Select red card
          Click - Select a card, or play the selected one
          Right click - Deselect

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
          S     - Run next golden scenario (passive towers, reference unit on tick 0)
          E     - Export played units' trajectories to build/trajectories
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
    renderer.dispose();
  }
}
