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
import org.crfoge.data.card.CardRegistry;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.desktop.render.DebugRenderer;

/**
 * Debug screen for visualizing the game simulation.
 * <p>
 * Controls:
 * <ul>
 * <li>SPACE: Pause/resume simulation</li>
 * <li>R: Reset match</li>
 * <li>P: Toggle path visualization</li>
 * <li>1-4: Play card from blue player's hand at random position</li>
 * <li>5-8: Play card from red player's hand at random position</li>
 * <li>+/-: Speed up/slow down simulation</li>
 * <li>Click: Deploy selected card at position (TODO)</li>
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
    float arenaWidth = Arena.WIDTH * DebugRenderer.TILE_PIXELS;
    float arenaHeight = Arena.HEIGHT * DebugRenderer.TILE_PIXELS;

    // Viewport includes UI margins
    float viewWidth = arenaWidth;
    float viewHeight = arenaHeight + DebugRenderer.TOP_UI_HEIGHT + DebugRenderer.BOTTOM_UI_HEIGHT;

    this.camera = new OrthographicCamera(viewWidth, viewHeight);
    camera.position.set(viewWidth / 2, viewHeight / 2, 0);
    camera.update();

    setupMatch();
    setupInput();
  }

  private void setupMatch() {
    // Create a standard 1v1 match
    Standard1v1Match match = new Standard1v1Match();

    // Create test decks with available cards
    List<Card> blueCards = List.of(
        CardRegistry.get("barbarians"),
        CardRegistry.get("musketeer"),
        CardRegistry.get("giant"),
        CardRegistry.get("archer"),
        CardRegistry.get("tombstone"),
        CardRegistry.get("valkyrie"),
        CardRegistry.get("goblins"),
        CardRegistry.get("babydragon")
    );

    List<Card> redCards = List.of(
        CardRegistry.get("goblins"),
        CardRegistry.get("babydragon"),
        CardRegistry.get("minions"),
        CardRegistry.get("bomber"),
        CardRegistry.get("valkyrie"),
        CardRegistry.get("cannon"),
        CardRegistry.get("knight"),
        CardRegistry.get("musketeer")
    );

    Deck blueDeck = new Deck(blueCards);
    Deck redDeck = new Deck(redCards);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    redPlayer = new Player(Team.RED, redDeck, true);

    // Default selection
    selectedPlayer = bluePlayer;

    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine.setMatch(match);
    engine.initMatch();
  }

  private void setupInput() {
    Gdx.input.setInputProcessor(new InputAdapter() {
      @Override
      public boolean keyDown(int keycode) {
        switch (keycode) {
          case Input.Keys.SPACE -> paused = !paused;
          case Input.Keys.R -> resetMatch();
          case Input.Keys.P -> {
            renderer.toggleDrawPaths();
            System.out.println("Path visualization: " + (renderer.isDrawPaths() ? "ON" : "OFF"));
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
          selectedHandIndex = -1; // Deselect
          renderer.setSelectedHandIndex(-1);
          renderer.setSelectedTeam(null);
        }
        return true;
      }
    });
  }

  private void updateHover(int screenX, int screenY) {
    // Use camera unproject to handle coordinates correctly even if resized (though fixed size for now)
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);

    // touchPos.y is world Y (0 is bottom of UI)
    // Arena starts at BOTTOM_UI_HEIGHT
    float arenaY = touchPos.y - DebugRenderer.BOTTOM_UI_HEIGHT;
    float arenaX = touchPos.x;

    float rawTileX = arenaX / DebugRenderer.TILE_PIXELS;
    float rawTileY = arenaY / DebugRenderer.TILE_PIXELS;

    // Snap to grid
    hoverTileX = (int) Math.floor(rawTileX);
    hoverTileY = (int) Math.floor(rawTileY);
  }

  private void handleLeftClick(int screenX, int screenY) {
    touchPos.set(screenX, screenY, 0);
    camera.unproject(touchPos);

    // 1. Check for Card Selection (Clicking on hand in Bottom UI or Top UI)
    // Check Bottom (Blue)
    if (touchPos.y < DebugRenderer.BOTTOM_UI_HEIGHT) {
      checkHandSelection(touchPos.x, touchPos.y, bluePlayer, false);
      return;
    }

    // Check Top (Red)
    float topUiStart = camera.viewportHeight - DebugRenderer.TOP_UI_HEIGHT;
    if (touchPos.y > topUiStart) {
      checkHandSelection(touchPos.x, touchPos.y, redPlayer, true);
      return;
    }

    // 2. Play Card (Clicking on Arena)
    if (selectedHandIndex != -1 && selectedPlayer != null && hoverTileX >= 0
        && hoverTileX < Arena.WIDTH && hoverTileY >= 0 && hoverTileY < Arena.HEIGHT) {
      // Try to play selected card
      float playX = hoverTileX + 0.5f;
      float playY = hoverTileY + 0.5f;

      PlayerActionDTO action = PlayerActionDTO.play(selectedHandIndex, playX, playY);
      engine.queueAction(selectedPlayer, action);
    }
  }

  private void checkHandSelection(float worldX, float worldY, Player player, boolean isTop) {
    // Replicate layout logic from DebugRenderer for hit detection
    float cardWidth = 60;
    float cardHeight = 80;
    float spacing = 10;
    float handWidth = (cardWidth * 4) + (spacing * 3);
    float startX = (camera.viewportWidth - handWidth) / 2;

    // Calculate Y position based on whether it's top or bottom player
    // Matches DebugRenderer:
    // float cardY = isTop ? yPos + 30 : yPos + 30;
    // For bottom: yPos = 0 -> cardY = 30
    // For top: yPos = screenHeight - TOP_UI_HEIGHT -> cardY = (screenHeight - 140) + 30
    float baseY = isTop ? (camera.viewportHeight - DebugRenderer.TOP_UI_HEIGHT) : 0;
    float cardY = baseY + 30;

    // Simple bounding box check
    if (worldY >= cardY && worldY <= cardY + cardHeight) {
      for (int i = 0; i < 4; i++) {
        float cardX = startX + i * (cardWidth + spacing);
        if (worldX >= cardX && worldX <= cardX + cardWidth) {
          selectCard(player, i);
          return;
        }
      }
    }
  }

  private void selectCard(Player player, int index) {
    this.selectedPlayer = player;
    this.selectedHandIndex = index;
    renderer.setSelectedHandIndex(index);
    renderer.setSelectedTeam(player.getTeam());

    Card c = player.getHand().getCard(index);
    if (c != null) {
      System.out.println("Selected (" + player.getTeam() + "): " + c.getName());
    }
  }

  private void adjustSpeed(float factor) {
    simSpeed = Math.max(SIM_SPEED_MIN, Math.min(SIM_SPEED_MAX, simSpeed * factor));
    System.out.printf("Simulation speed: %.2fx%n", simSpeed);
  }

  private void resetMatch() {
    engine.getGameState().reset();
    setupMatch();
    paused = false;
    System.out.println("Match reset");
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
      renderer.render(engine, camera, hoverTileX, hoverTileY);
    } catch (Exception e) {
      log.error("CRASH during game loop!", e);
      // Pause to prevent log spam/hard crash loop if possible
      paused = true;
      // Exit to prevent hanging window
      Gdx.app.exit();
    }
  }

  @Override
  public void resize(int width, int height) {
    // Keep arena centered with correct aspect ratio
    // TODO: Disable resize feature (Although it is not working now, we should remove it entirely)
    float arenaWidth = Arena.WIDTH * DebugRenderer.TILE_PIXELS;
    float arenaHeight = Arena.HEIGHT * DebugRenderer.TILE_PIXELS;
    float totalHeight = arenaHeight + DebugRenderer.TOP_UI_HEIGHT + DebugRenderer.BOTTOM_UI_HEIGHT;

    camera.viewportWidth = arenaWidth;
    camera.viewportHeight = totalHeight;
    camera.position.set(arenaWidth / 2, totalHeight / 2, 0); // Center includes UI
    camera.update();
  }

  @Override
  public void show() {
    System.out.println("=== CRForge Debug Visualizer ===");
    System.out.println("Controls:");
    System.out.println("  SPACE - Pause/Resume");
    System.out.println("  R     - Reset match");
    System.out.println("  P     - Toggle path visualization");
    System.out.println("  +/-   - Speed up/slow down");
    System.out.println("  1-4   - Play blue card");
    System.out.println("  5-8   - Play red card");
    System.out.println("  Click - Deploy at position");
    System.out.println("================================");
  }

  @Override
  public void hide() {
  }

  @Override
  public void pause() {
  }

  @Override
  public void resume() {
  }

  @Override
  public void dispose() {
    renderer.dispose();
  }
}
