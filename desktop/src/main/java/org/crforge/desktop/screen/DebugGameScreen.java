package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.crforge.desktop.render.CardLayout;
import org.crforge.desktop.render.DebugRenderer;
import org.crforge.desktop.render.RenderConstants;

/**
 * Debug screen for visualizing the game simulation.
 *
 * <p>Controls:
 *
 * <ul>
 *   <li>SPACE: Pause/resume simulation
 *   <li>R: Reset match
 *   <li>P: Toggle path visualization
 *   <li>O: Toggle attack range circles
 *   <li>D: Toggle floating damage numbers
 *   <li>A: Toggle AOE damage indicators
 *   <li>1-4: Play card from blue player's hand at random position
 *   <li>5-8: Play card from red player's hand at random position
 *   <li>+/-: Speed up/slow down simulation
 *   <li>Click: Deploy selected card at position
 * </ul>
 */
@Slf4j
public class DebugGameScreen implements Screen {

  private static final float SIM_SPEED_MIN = 0.25f;
  private static final float SIM_SPEED_MAX = 8f;

  private final GameEngine engine;
  private final DebugRenderer renderer;
  private final OrthographicCamera camera;
  private final Vector3 touchPos = new Vector3();

  private float simSpeed = 1f;
  private boolean paused = false;
  private float accumulator = 0f;

  private Player bluePlayer;
  private Player redPlayer;

  private int hoverTileX = -1;
  private int hoverTileY = -1;

  private int selectedHandIndex = -1;
  private Player selectedPlayer = null;

  public DebugGameScreen() {
    this.engine = new GameEngine();
    this.renderer = new DebugRenderer();

    // Setup camera
    float arenaWidth = Arena.WIDTH * RenderConstants.TILE_PIXELS;
    float arenaHeight = Arena.HEIGHT * RenderConstants.TILE_PIXELS;

    // Viewport includes UI margins
    float viewWidth = arenaWidth;
    float viewHeight =
        arenaHeight + RenderConstants.TOP_UI_HEIGHT + RenderConstants.BOTTOM_UI_HEIGHT;

    this.camera = new OrthographicCamera(viewWidth, viewHeight);
    camera.position.set(viewWidth / 2, viewHeight / 2, 0);
    camera.update();

    setupMatch();
    setupInput();
  }

  private void setupMatch() {
    // Create a standard 1v1 match
    Standard1v1Match match = new Standard1v1Match();

    // Decks showcasing special abilities and effects:
    // Blue: charge, hook, variable damage, deploy effect, spawner, area effect spell, kamikaze air
    List<Card> blueCards =
        List.of(
            CardRegistry.get("darkprince"), // Charge + Shield
            CardRegistry.get("prince"), // Charge
            CardRegistry.get("fisherman"), // Hook
            CardRegistry.get("infernodragon"), // Variable damage (inferno stages)
            CardRegistry.get("electrowizard"), // Deploy stun effect
            CardRegistry.get("witch"), // Live spawner (skeletons)
            CardRegistry.get("zap"), // Area effect spell (stun)
            CardRegistry.get("mortar") // Test 3x volley shots of arrow
            );

    // Red: dash, reflect, shield, spawner building, live spawn, area effect, charge
    List<Card> redCards =
        List.of(
            CardRegistry.get("megaknight"), // Dash
            CardRegistry.get("electrogiant"), // Reflect
            CardRegistry.get("assassin"), // Dash
            CardRegistry.get("skeletonwarriors"), // Shield
            CardRegistry.get("tombstone"), // Spawner building
            CardRegistry.get("darkwitch"), // Live spawn + death spawn
            CardRegistry.get("freeze"), // Area effect spell
            CardRegistry.get("ramrider") // Charge + live spawn
            );

    Deck blueDeck = new Deck(blueCards);
    Deck redDeck = new Deck(redCards);

    LevelConfig levelCfg = new LevelConfig(11); // Level 11 for standard ladder gameplay
    bluePlayer = new Player(Team.BLUE, blueDeck, false, levelCfg);
    redPlayer = new Player(Team.RED, redDeck, true, levelCfg);

    // Default selection
    selectedPlayer = bluePlayer;

    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine.setMatch(match);
    engine.initMatch();
  }

  private void setupInput() {
    Gdx.input.setInputProcessor(
        new InputAdapter() {
          @Override
          public boolean keyDown(int keycode) {
            switch (keycode) {
              case Input.Keys.SPACE -> paused = !paused;
              case Input.Keys.R -> resetMatch();
              case Input.Keys.P -> {
                renderer.toggleDrawPaths();
                log.info("Path visualization: {}", renderer.isDrawPaths() ? "ON" : "OFF");
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
              case Input.Keys.EQUALS, Input.Keys.PLUS -> adjustSpeed(2f);
              case Input.Keys.MINUS -> adjustSpeed(0.5f);

              // Select card from hand (Blue Player) via keyboard
              case Input.Keys.NUM_1 -> selectCard(bluePlayer, 0);
              case Input.Keys.NUM_2 -> selectCard(bluePlayer, 1);
              case Input.Keys.NUM_3 -> selectCard(bluePlayer, 2);
              case Input.Keys.NUM_4 -> selectCard(bluePlayer, 3);

              // Select card from hand (Red Player) via keyboard
              case Input.Keys.NUM_5 -> selectCard(redPlayer, 0);
              case Input.Keys.NUM_6 -> selectCard(redPlayer, 1);
              case Input.Keys.NUM_7 -> selectCard(redPlayer, 2);
              case Input.Keys.NUM_8 -> selectCard(redPlayer, 3);

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
              selectedHandIndex = -1;
              selectedPlayer = null;
            }
            return true;
          }
        });
  }

  private void updateHover(int screenX, int screenY) {
    // Use camera unproject to handle coordinates correctly even if resized (though fixed size for
    // now)
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);

    // touchPos.y is world Y (0 is bottom of UI)
    // Arena starts at BOTTOM_UI_HEIGHT
    float arenaY = touchPos.y - RenderConstants.BOTTOM_UI_HEIGHT;
    float arenaX = touchPos.x;

    float rawTileX = arenaX / RenderConstants.TILE_PIXELS;
    float rawTileY = arenaY / RenderConstants.TILE_PIXELS;

    // Snap to grid
    hoverTileX = (int) Math.floor(rawTileX);
    hoverTileY = (int) Math.floor(rawTileY);
  }

  private void handleLeftClick(int screenX, int screenY) {
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);

    // 1. Check for Card Selection (Clicking on hand in Bottom UI or Top UI)
    // Check Bottom (Blue)
    if (touchPos.y < RenderConstants.BOTTOM_UI_HEIGHT) {
      checkHandSelection(touchPos.x, touchPos.y, bluePlayer, false);
      return;
    }

    // Check Top (Red)
    float topUiStart = camera.viewportHeight - RenderConstants.TOP_UI_HEIGHT;
    if (touchPos.y > topUiStart) {
      checkHandSelection(touchPos.x, touchPos.y, redPlayer, true);
      return;
    }

    // 2. Play Card (Clicking on Arena)
    if (selectedHandIndex != -1
        && selectedPlayer != null
        && hoverTileX >= 0
        && hoverTileX < Arena.WIDTH
        && hoverTileY >= 0
        && hoverTileY < Arena.HEIGHT) {
      float playX = hoverTileX + 0.5f;
      float playY = hoverTileY + 0.5f;

      PlayerActionDTO action = PlayerActionDTO.play(selectedHandIndex, playX, playY);

      // Pre-validate: check placement and elixir before queuing
      Card card = selectedPlayer.getHand().getCard(selectedHandIndex);
      if (card == null) {
        return;
      }

      boolean validPlacement = engine.getMatch().validateAction(selectedPlayer, action);
      boolean canAfford = selectedPlayer.getElixir().has(card.getCost());

      if (!validPlacement) {
        log.warn(
            "[{}s] Invalid placement for {} at ({}, {})",
            engine.getGameTimeSeconds(),
            card.getName(),
            playX,
            playY);
        return;
      }

      if (!canAfford) {
        log.warn(
            "[{}s] Not enough elixir for {} (cost {}, have {})",
            engine.getGameTimeSeconds(),
            card.getName(),
            card.getCost(),
            selectedPlayer.getElixir().getFloor());
        return;
      }

      engine.queueAction(selectedPlayer, action);
      log.info(
          "[{}s] {} played {} at ({}, {})",
          engine.getGameTimeSeconds(),
          selectedPlayer.getTeam(),
          card.getName(),
          playX,
          playY);

      // Deselect after successful play (matches real CR)
      selectedHandIndex = -1;
      selectedPlayer = null;
    }
  }

  private void checkHandSelection(float worldX, float worldY, Player player, boolean isTop) {
    int index =
        CardLayout.hitTest(worldX, worldY, isTop, camera.viewportWidth, camera.viewportHeight);
    if (index != -1) {
      selectCard(player, index);
    }
  }

  private void selectCard(Player player, int index) {
    this.selectedPlayer = player;
    this.selectedHandIndex = index;

    Card c = player.getHand().getCard(index);
    if (c != null) {
      log.info(
          "[{}s] Selected ({}): {} (cost {})",
          engine.getGameTimeSeconds(),
          player.getTeam(),
          c.getName(),
          c.getCost());
    }
  }

  private void adjustSpeed(float factor) {
    simSpeed = Math.max(SIM_SPEED_MIN, Math.min(SIM_SPEED_MAX, simSpeed * factor));
    log.info("Simulation speed: {}x", simSpeed);
  }

  private void resetMatch() {
    engine.getGameState().reset();
    engine.getDeploymentSystem().reset();
    setupMatch();
    paused = false;
    log.info("Match reset");
  }

  @Override
  public void render(float delta) {
    // Clear screen
    Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    try {
      // Update simulation
      if (!paused && engine.isRunning()) {
        accumulator += delta * simSpeed;

        // Fixed timestep simulation
        float tickDelta = GameEngine.DELTA_TIME;
        while (accumulator >= tickDelta) {
          engine.tick();
          accumulator -= tickDelta;
        }
      }

      // Render
      camera.update();
      Team selTeam = selectedPlayer != null ? selectedPlayer.getTeam() : null;
      renderer.render(engine, camera, hoverTileX, hoverTileY, selectedHandIndex, selTeam);
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
        === CRForge Debug Visualizer ===
        Controls:
          SPACE - Pause/Resume
          R     - Reset match
          P     - Toggle path visualization
          O     - Toggle attack range circles
          D     - Toggle floating damage numbers
          A     - Toggle AOE damage indicators
          +/-   - Speed up/slow down
          1-4   - Play blue card
          5-8   - Play red card
          Click - Deploy at position
        ================================""");
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
