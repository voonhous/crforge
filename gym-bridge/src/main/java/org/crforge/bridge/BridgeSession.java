package org.crforge.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.crforge.bridge.dto.InitConfig;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.RewardDTO;
import org.crforge.bridge.dto.StepAction;
import org.crforge.bridge.dto.StepResultDTO;
import org.crforge.bridge.observation.BinaryObservationEncoder;
import org.crforge.bridge.protocol.ZmqTransport;
import org.crforge.data.card.CardRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the protocol dispatch loop for a single connected client. Receives JSON messages over
 * ZMQ, dispatches to GameSession, sends responses. Supports both JSON and binary observation modes.
 */
public class BridgeSession {

  private static final Logger log = LoggerFactory.getLogger(BridgeSession.class);

  private final ZmqTransport transport;
  private final GameSession gameSession;
  private final ObjectMapper mapper;
  private final BinaryObservationEncoder binaryEncoder;

  private boolean binaryObs;

  public BridgeSession(ZmqTransport transport) {
    this.transport = transport;
    this.gameSession = new GameSession();
    this.mapper = transport.getMapper();
    this.binaryEncoder = new BinaryObservationEncoder();
    this.binaryObs = false;
  }

  /** Runs the protocol loop until a "close" message or error. */
  public void run() {
    log.info("Bridge session started, waiting for messages...");

    try {
      while (true) {
        JsonNode message = transport.receive();
        if (message == null) {
          log.info("Socket interrupted, ending session");
          break;
        }

        String type = message.path("type").asText("");
        JsonNode data = message.path("data");

        switch (type) {
          case "init" -> handleInit(data);
          case "reset" -> handleReset(data);
          case "step" -> handleStep(data);
          case "close" -> {
            log.info("Client requested close");
            transport.send("close_ok", null);
            return;
          }
          default -> {
            log.warn("Unknown message type: {}", type);
            transport.sendError("Unknown message type: " + type);
          }
        }
      }
    } catch (IOException e) {
      log.error("Protocol error in bridge session", e);
    }
  }

  private void handleInit(JsonNode data) throws IOException {
    try {
      InitConfig config = mapper.treeToValue(data, InitConfig.class);

      // Validate deck card IDs
      for (String id : config.blueDeck()) {
        if (!CardRegistry.exists(id)) {
          transport.sendError("Unknown card ID in blue deck: " + id);
          return;
        }
      }
      for (String id : config.redDeck()) {
        if (!CardRegistry.exists(id)) {
          transport.sendError("Unknown card ID in red deck: " + id);
          return;
        }
      }
      if (config.blueDeck().size() != 8 || config.redDeck().size() != 8) {
        transport.sendError("Each deck must contain exactly 8 cards");
        return;
      }

      // Store binary mode setting
      this.binaryObs = config.isBinaryObs();

      gameSession.init(config);

      // Respond with available card list
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("cardIds", CardRegistry.getAllIds());
      transport.send("init_ok", response);
    } catch (Exception e) {
      log.error("Error handling init", e);
      transport.sendError("Init failed: " + e.getMessage());
    }
  }

  private void handleReset(JsonNode data) throws IOException {
    try {
      Long seedOverride = null;
      if (data != null && data.has("seed") && !data.get("seed").isNull()) {
        seedOverride = data.get("seed").asLong();
      }
      gameSession.reset(seedOverride);

      if (binaryObs) {
        byte[] obsBytes =
            binaryEncoder.encodeObservation(
                gameSession.getEngine(), gameSession.getBluePlayer(), gameSession.getRedPlayer());
        transport.sendRaw(obsBytes);
      } else {
        ObservationDTO observation = gameSession.observe();
        transport.send("observation", observation);
      }
    } catch (Exception e) {
      log.error("Error handling reset", e);
      transport.sendError("Reset failed: " + e.getMessage());
    }
  }

  private void handleStep(JsonNode data) throws IOException {
    try {
      // Parse blue and red actions
      StepAction blueAction = null;
      StepAction redAction = null;

      if (data.has("blueAction") && !data.get("blueAction").isNull()) {
        blueAction = mapper.treeToValue(data.get("blueAction"), StepAction.class);
      }
      if (data.has("redAction") && !data.get("redAction").isNull()) {
        redAction = mapper.treeToValue(data.get("redAction"), StepAction.class);
      }

      if (binaryObs) {
        StepResultDTO result = gameSession.step(blueAction, redAction);
        RewardDTO reward = result.reward();

        byte[] bytes =
            binaryEncoder.encodeStepResult(
                gameSession.getEngine(),
                gameSession.getBluePlayer(),
                gameSession.getRedPlayer(),
                reward.blue(),
                reward.red(),
                result.terminated(),
                result.truncated(),
                result.blueActionFailed(),
                result.redActionFailed());
        transport.sendRaw(bytes);
      } else {
        StepResultDTO result = gameSession.step(blueAction, redAction);
        transport.send("step_result", result);
      }
    } catch (Exception e) {
      log.error("Error handling step", e);
      transport.sendError("Step failed: " + e.getMessage());
    }
  }
}
