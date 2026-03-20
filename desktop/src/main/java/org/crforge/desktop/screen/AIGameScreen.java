package org.crforge.desktop.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.crforge.bridge.dto.InitConfig;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.RewardDTO;
import org.crforge.bridge.dto.StepAction;
import org.crforge.bridge.dto.StepResultDTO;
import org.crforge.bridge.observation.ObservationBuilder;
import org.crforge.bridge.observation.RewardCalculator;
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
import org.crforge.desktop.render.DebugRenderer;
import org.crforge.desktop.render.RenderConstants;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * AI visualizer screen that accepts Python control via the ZMQ bridge protocol. Renders the game in
 * real-time while the model controls card deployment.
 *
 * <p>Controls:
 *
 * <ul>
 *   <li>SPACE: Pause/resume simulation (Python blocks while paused)
 *   <li>P: Toggle path visualization
 *   <li>O: Toggle attack range circles
 *   <li>D: Toggle floating damage numbers
 *   <li>A: Toggle AOE damage indicators
 *   <li>H: Toggle HP numbers
 *   <li>+/-: Speed up/slow down simulation
 * </ul>
 */
@Slf4j
public class AIGameScreen implements Screen {

  private static final float SIM_SPEED_MIN = 0.25f;
  private static final float SIM_SPEED_MAX = 8f;

  private enum State {
    WAITING_FOR_INIT,
    WAITING_FOR_COMMAND,
    TICKING_STEP
  }

  /** A pending request from the ZMQ thread waiting for the render thread to process it. */
  private record PendingRequest(
      String type, JsonNode data, CompletableFuture<String> responseFuture) {}

  private final int port;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<PendingRequest> pendingRequest = new AtomicReference<>();

  // ZMQ thread
  private Thread zmqThread;
  private volatile boolean running = true;

  // Game state
  private GameEngine engine;
  private Player bluePlayer;
  private Player redPlayer;
  private RewardCalculator rewardCalculator;
  private InitConfig config;
  private Long seed;
  private long resetCount;

  // Step metering
  private State state = State.WAITING_FOR_INIT;
  private int ticksRemaining = 0;
  private float accumulator = 0f;
  private CompletableFuture<String> stepResponseFuture;
  private boolean blueActionFailed;
  private boolean redActionFailed;

  // Rendering
  private DebugRenderer renderer;
  private OrthographicCamera camera;
  private float simSpeed = 1f;
  private boolean paused = false;

  public AIGameScreen(int port) {
    this.port = port;
  }

  @Override
  public void show() {
    this.renderer = new DebugRenderer();
    this.rewardCalculator = new RewardCalculator();

    // Setup camera (same as DebugGameScreen)
    float arenaWidth = Arena.WIDTH * RenderConstants.TILE_PIXELS;
    float arenaHeight = Arena.HEIGHT * RenderConstants.TILE_PIXELS;
    float viewWidth = arenaWidth;
    float viewHeight =
        arenaHeight + RenderConstants.TOP_UI_HEIGHT + RenderConstants.BOTTOM_UI_HEIGHT;

    this.camera = new OrthographicCamera(viewWidth, viewHeight);
    camera.position.set(viewWidth / 2, viewHeight / 2, 0);
    camera.update();

    setupInput();
    startZmqThread();

    log.info(
        "=== CRForge AI Visualizer ===\n"
            + "Listening on port {}\n"
            + "Waiting for Python client...\n"
            + "Controls: SPACE=pause, +/-=speed, P/O/D/A/H=overlays",
        port);
  }

  private void setupInput() {
    Gdx.input.setInputProcessor(
        new InputAdapter() {
          @Override
          public boolean keyDown(int keycode) {
            switch (keycode) {
              case Input.Keys.SPACE -> paused = !paused;
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
              case Input.Keys.H -> {
                renderer.toggleDrawHpNumbers();
                log.info("HP numbers: {}", renderer.isDrawHpNumbers() ? "ON" : "OFF");
              }
              case Input.Keys.EQUALS, Input.Keys.PLUS -> adjustSpeed(2f);
              case Input.Keys.MINUS -> adjustSpeed(0.5f);
              default -> {
                return false;
              }
            }
            return true;
          }
        });
  }

  private void adjustSpeed(float factor) {
    simSpeed = Math.max(SIM_SPEED_MIN, Math.min(SIM_SPEED_MAX, simSpeed * factor));
    log.info("Simulation speed: {}x", simSpeed);
  }

  // ---- ZMQ background thread ----

  private void startZmqThread() {
    zmqThread =
        new Thread(
            () -> {
              String endpoint = "tcp://*:" + port;
              try (ZContext ctx = new ZContext()) {
                ZMQ.Socket socket = ctx.createSocket(SocketType.PAIR);
                socket.bind(endpoint);
                log.info("ZMQ PAIR socket bound on {}", endpoint);

                while (running) {
                  byte[] data = socket.recv(0);
                  if (data == null) {
                    break;
                  }

                  String json = new String(data, StandardCharsets.UTF_8);
                  log.debug("Received: {}", json);

                  try {
                    JsonNode message = mapper.readTree(json);
                    String type = message.path("type").asText("");
                    JsonNode msgData = message.path("data");

                    // Handle close immediately on the ZMQ thread
                    if ("close".equals(type)) {
                      log.info("Client requested close");
                      String response = buildResponse("close_ok", null);
                      socket.send(response.getBytes(StandardCharsets.UTF_8), 0);
                      break;
                    }

                    // Hand off to render thread and wait for response
                    CompletableFuture<String> future = new CompletableFuture<>();
                    PendingRequest request = new PendingRequest(type, msgData, future);
                    pendingRequest.set(request);

                    // Block until the render thread produces a response
                    String response = future.get();
                    log.debug("Sending: {}", response);
                    socket.send(response.getBytes(StandardCharsets.UTF_8), 0);
                  } catch (Exception e) {
                    if (running) {
                      log.error("Error processing message", e);
                      String errorResponse = buildErrorResponse(e.getMessage());
                      socket.send(errorResponse.getBytes(StandardCharsets.UTF_8), 0);
                    }
                  }
                }
              }
              log.info("ZMQ thread exiting");
            },
            "ai-zmq-thread");
    zmqThread.setDaemon(true);
    zmqThread.start();
  }

  // ---- Render loop (LibGDX thread) ----

  @Override
  public void render(float delta) {
    // Clear screen
    Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    try {
      // 1. Poll for pending requests when not mid-step
      if (state == State.WAITING_FOR_INIT || state == State.WAITING_FOR_COMMAND) {
        PendingRequest request = pendingRequest.getAndSet(null);
        if (request != null) {
          dispatchRequest(request);
        }
      }

      // 2. Meter ticks for in-progress step
      if (state == State.TICKING_STEP && !paused) {
        accumulator += delta * simSpeed;

        float tickDelta = GameEngine.DELTA_TIME;
        while (accumulator >= tickDelta && ticksRemaining > 0) {
          engine.tick();
          ticksRemaining--;
          accumulator -= tickDelta;
        }

        if (ticksRemaining == 0) {
          completeStep();
        }
      }

      // 3. Render (always, even when waiting)
      if (engine != null) {
        camera.update();
        renderer.render(engine, camera, -1, -1, -1, null);
      }
    } catch (Exception e) {
      log.error("CRASH during game loop!", e);
      paused = true;
      Gdx.app.exit();
    }
  }

  private void dispatchRequest(PendingRequest request) {
    try {
      switch (request.type()) {
        case "init" -> handleInit(request);
        case "reset" -> handleReset(request);
        case "step" -> handleStep(request);
        default -> {
          log.warn("Unknown message type: {}", request.type());
          request
              .responseFuture()
              .complete(buildErrorResponse("Unknown message type: " + request.type()));
        }
      }
    } catch (Exception e) {
      log.error("Error dispatching request: {}", request.type(), e);
      request.responseFuture().complete(buildErrorResponse(e.getMessage()));
    }
  }

  private void handleInit(PendingRequest request) {
    try {
      InitConfig initConfig = mapper.treeToValue(request.data(), InitConfig.class);

      // Validate deck card IDs
      for (String id : initConfig.blueDeck()) {
        if (!CardRegistry.exists(id)) {
          request
              .responseFuture()
              .complete(buildErrorResponse("Unknown card ID in blue deck: " + id));
          return;
        }
      }
      for (String id : initConfig.redDeck()) {
        if (!CardRegistry.exists(id)) {
          request
              .responseFuture()
              .complete(buildErrorResponse("Unknown card ID in red deck: " + id));
          return;
        }
      }
      if (initConfig.blueDeck().size() != 8 || initConfig.redDeck().size() != 8) {
        request
            .responseFuture()
            .complete(buildErrorResponse("Each deck must contain exactly 8 cards"));
        return;
      }

      this.config = initConfig;
      this.seed = initConfig.seed();
      this.resetCount = 0;

      // Respond with available card list
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("cardIds", CardRegistry.getAllIds());
      request.responseFuture().complete(buildResponse("init_ok", response));

      state = State.WAITING_FOR_COMMAND;
      log.info(
          "AI session initialized: level={}, ticksPerStep={}",
          config.level(),
          config.ticksPerStep());
    } catch (Exception e) {
      log.error("Error handling init", e);
      request.responseFuture().complete(buildErrorResponse("Init failed: " + e.getMessage()));
    }
  }

  private void handleReset(PendingRequest request) {
    try {
      Long seedOverride = null;
      JsonNode data = request.data();
      if (data != null && data.has("seed") && !data.get("seed").isNull()) {
        seedOverride = data.get("seed").asLong();
      }
      if (seedOverride != null) {
        this.seed = seedOverride;
      }

      // Create fresh engine and match (mirrors GameSession.reset())
      this.engine = new GameEngine();

      List<Card> blueCards = config.blueDeck().stream().map(CardRegistry::get).toList();
      List<Card> redCards = config.redDeck().stream().map(CardRegistry::get).toList();

      Deck blueDeck = new Deck(blueCards);
      Deck redDeck = new Deck(redCards);

      Random blueRandom;
      Random redRandom;
      if (seed != null) {
        long derivedSeed = (seedOverride != null) ? seed : seed + resetCount;
        blueRandom = new Random(derivedSeed);
        redRandom = new Random(derivedSeed + 1);
      } else {
        blueRandom = new Random();
        redRandom = new Random();
      }
      resetCount++;

      LevelConfig levelConfig = new LevelConfig(config.level());
      bluePlayer = new Player(Team.BLUE, blueDeck, false, levelConfig, blueRandom);
      redPlayer = new Player(Team.RED, redDeck, true, levelConfig, redRandom);

      Standard1v1Match match = new Standard1v1Match(config.level());
      match.addPlayer(bluePlayer);
      match.addPlayer(redPlayer);

      engine.setMatch(match);
      engine.initMatch();

      rewardCalculator.reset(engine.getGameState(), bluePlayer, redPlayer);

      // Send observation
      ObservationDTO observation = ObservationBuilder.build(engine, bluePlayer, redPlayer);
      request.responseFuture().complete(buildResponse("observation", observation));

      state = State.WAITING_FOR_COMMAND;
      accumulator = 0f;
      log.info(
          "Game reset: level={}, ticksPerStep={}, seed={}",
          config.level(),
          config.ticksPerStep(),
          seed);
    } catch (Exception e) {
      log.error("Error handling reset", e);
      request.responseFuture().complete(buildErrorResponse("Reset failed: " + e.getMessage()));
    }
  }

  private void handleStep(PendingRequest request) {
    try {
      JsonNode data = request.data();

      // Parse actions
      StepAction blueAction = null;
      StepAction redAction = null;
      if (data.has("blueAction") && !data.get("blueAction").isNull()) {
        blueAction = mapper.treeToValue(data.get("blueAction"), StepAction.class);
      }
      if (data.has("redAction") && !data.get("redAction").isNull()) {
        redAction = mapper.treeToValue(data.get("redAction"), StepAction.class);
      }

      // Track elixir for action failure detection
      boolean blueAttempted = blueAction != null && !blueAction.isNoop();
      boolean redAttempted = redAction != null && !redAction.isNoop();
      float blueElixirBefore = bluePlayer.getElixir().getCurrent();
      float redElixirBefore = redPlayer.getElixir().getCurrent();

      // Queue actions
      if (blueAttempted) {
        PlayerActionDTO action =
            PlayerActionDTO.play(blueAction.handIndex(), blueAction.x(), blueAction.y());
        engine.queueAction(bluePlayer, action);
      }
      if (redAttempted) {
        PlayerActionDTO action =
            PlayerActionDTO.play(redAction.handIndex(), redAction.x(), redAction.y());
        engine.queueAction(redPlayer, action);
      }

      // Detect action failure
      blueActionFailed = blueAttempted && bluePlayer.getElixir().getCurrent() >= blueElixirBefore;
      redActionFailed = redAttempted && redPlayer.getElixir().getCurrent() >= redElixirBefore;

      // Start metered ticking
      ticksRemaining = config.ticksPerStep();
      accumulator = 0f;
      stepResponseFuture = request.responseFuture();
      state = State.TICKING_STEP;
    } catch (Exception e) {
      log.error("Error handling step", e);
      request.responseFuture().complete(buildErrorResponse("Step failed: " + e.getMessage()));
    }
  }

  private void completeStep() {
    ObservationDTO observation = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    RewardDTO reward = rewardCalculator.computeReward(engine.getGameState());

    boolean terminated = engine.getGameState().isGameOver() || !engine.isRunning();
    boolean truncated = false;

    StepResultDTO result =
        new StepResultDTO(
            observation, reward, terminated, truncated, blueActionFailed, redActionFailed);

    stepResponseFuture.complete(buildResponse("step_result", result));
    stepResponseFuture = null;
    state = State.WAITING_FOR_COMMAND;
  }

  // ---- JSON helpers ----

  private String buildResponse(String type, Object payload) {
    try {
      ObjectNode response = mapper.createObjectNode();
      response.put("type", type);
      if (payload != null) {
        response.set("data", mapper.valueToTree(payload));
      }
      return mapper.writeValueAsString(response);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize response", e);
    }
  }

  private String buildErrorResponse(String message) {
    try {
      ObjectNode response = mapper.createObjectNode();
      response.put("type", "error");
      response.put("message", message);
      return mapper.writeValueAsString(response);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize error response", e);
    }
  }

  // ---- Lifecycle ----

  @Override
  public void resize(int width, int height) {
    // No-op: fixed-size window
  }

  @Override
  public void hide() {}

  @Override
  public void pause() {}

  @Override
  public void resume() {}

  @Override
  public void dispose() {
    running = false;
    if (zmqThread != null) {
      zmqThread.interrupt();
    }
    if (renderer != null) {
      renderer.dispose();
    }
  }
}
