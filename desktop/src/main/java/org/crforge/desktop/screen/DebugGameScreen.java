package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardRegistry;
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
public class DebugGameScreen implements Screen {

  private static final float SIM_SPEED_MIN = 0.25f;
  private static final float SIM_SPEED_MAX = 8f;

  private final GameEngine engine;
  private final DebugRenderer renderer;
  private final OrthographicCamera camera;

  private float simSpeed = 1f;
  private boolean paused = false;
  private float accumulator = 0f;

  // Players for testing
  private Player bluePlayer;
  private Player redPlayer;

  public DebugGameScreen() {
    this.engine = new GameEngine();
    this.renderer = new DebugRenderer();

    // Setup camera to view the arena
    float viewWidth = Arena.WIDTH * DebugRenderer.TILE_PIXELS;
    float viewHeight = Arena.HEIGHT * DebugRenderer.TILE_PIXELS;
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
        CardRegistry.get("knight"),
        CardRegistry.get("musketeer"),
        CardRegistry.get("giant"),
        CardRegistry.get("archers"),
        CardRegistry.get("tombstone"),
        CardRegistry.get("valkyrie"),
        CardRegistry.get("goblins"),
        CardRegistry.get("arrows")
    );

    List<Card> redCards = List.of(
        CardRegistry.get("barbarians"),
        CardRegistry.get("baby_dragon"),
        CardRegistry.get("minions"),
        CardRegistry.get("bomber"),
        CardRegistry.get("zap"),
        CardRegistry.get("cannon"),
        CardRegistry.get("knight"),
        CardRegistry.get("musketeer")
    );

    Deck blueDeck = new Deck(blueCards);
    Deck redDeck = new Deck(redCards);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    redPlayer = new Player(Team.RED, redDeck, true);

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

          // Blue player cards (1-4)
          case Input.Keys.NUM_1 -> playCard(bluePlayer, 0);
          case Input.Keys.NUM_2 -> playCard(bluePlayer, 1);
          case Input.Keys.NUM_3 -> playCard(bluePlayer, 2);
          case Input.Keys.NUM_4 -> playCard(bluePlayer, 3);

          // Red player cards (5-8)
          case Input.Keys.NUM_5 -> playCard(redPlayer, 0);
          case Input.Keys.NUM_6 -> playCard(redPlayer, 1);
          case Input.Keys.NUM_7 -> playCard(redPlayer, 2);
          case Input.Keys.NUM_8 -> playCard(redPlayer, 3);

          default -> {
            return false;
          }
        }
        return true;
      }

      @Override
      public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        // Convert screen to world coordinates
        float worldX = screenX / DebugRenderer.TILE_PIXELS;
        float worldY = (Gdx.graphics.getHeight() - screenY) / DebugRenderer.TILE_PIXELS;

        // Determine which side was clicked
        float midY = Arena.HEIGHT / 2f;
        Player player = worldY < midY ? bluePlayer : redPlayer;

        // Play first card in hand at clicked position
        if (player != null) {
          PlayerActionDTO action = PlayerActionDTO.play(0, worldX, worldY);
          engine.queueAction(player, action);
          System.out.printf("Click deploy: %.1f, %.1f (%s)%n", worldX, worldY, player.getTeam());
        }

        return true;
      }
    });
  }

  private void playCard(Player player, int handIndex) {
    if (player == null) {
      return;
    }

    // Get a valid spawn position for this player
    float x = Arena.WIDTH / 2f + (float) (Math.random() - 0.5) * 8;
    float y;

    if (player.getTeam() == Team.BLUE) {
      y = 8f + (float) (Math.random()) * 5;  // Blue spawns in lower area
    } else {
      y = Arena.HEIGHT - 8f - (float) (Math.random()) * 5;  // Red spawns in upper area
    }

    PlayerActionDTO action = PlayerActionDTO.play(handIndex, x, y);
    engine.queueAction(player, action);

    Card card = player.getHand().getCard(handIndex);
    if (card != null) {
      System.out.printf("%s playing %s at (%.1f, %.1f)%n",
          player.getTeam(), card.getName(), x, y);
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
    renderer.render(engine, camera);
  }

  @Override
  public void resize(int width, int height) {
    // Keep arena centered
    float viewWidth = Arena.WIDTH * DebugRenderer.TILE_PIXELS;
    float viewHeight = Arena.HEIGHT * DebugRenderer.TILE_PIXELS;
    camera.viewportWidth = viewWidth;
    camera.viewportHeight = viewHeight;
    camera.position.set(viewWidth / 2, viewHeight / 2, 0);
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