package org.crforge.bridge;

import java.util.List;
import java.util.Random;
import org.crforge.bridge.dto.InitConfig;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.RewardDTO;
import org.crforge.bridge.dto.StepAction;
import org.crforge.bridge.dto.StepResultDTO;
import org.crforge.bridge.observation.ObservationBuilder;
import org.crforge.bridge.observation.RewardCalculator;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a single game session: engine lifecycle, stepping, and observation building. Follows the
 * pattern from DebugGameScreen.setupMatch().
 */
public class GameSession {

  private static final Logger log = LoggerFactory.getLogger(GameSession.class);

  private GameEngine engine;
  private Player bluePlayer;
  private Player redPlayer;
  private final RewardCalculator rewardCalculator;

  private InitConfig config;
  private int ticksPerStep;
  private Long seed;
  private long resetCount;

  public GameSession() {
    this.rewardCalculator = new RewardCalculator();
  }

  /** Initializes a new match with the given configuration. */
  public void init(InitConfig config) {
    this.config = config;
    this.ticksPerStep = config.ticksPerStep();
    this.seed = config.seed();
    this.resetCount = 0;
    reset();
  }

  /**
   * Resets the match to initial state using the current configuration. Supports optional seed
   * override for per-reset seeding from Python.
   */
  public void reset() {
    reset(null);
  }

  /**
   * Resets the match with an optional seed override. If seedOverride is non-null, it replaces the
   * session seed for this and future resets.
   */
  public void reset(Long seedOverride) {
    if (config == null) {
      throw new IllegalStateException("Must call init() before reset()");
    }

    if (seedOverride != null) {
      this.seed = seedOverride;
    }

    this.engine = new GameEngine();

    // Build decks from card IDs
    List<Card> blueCards = config.blueDeck().stream().map(CardRegistry::get).toList();
    List<Card> redCards = config.redDeck().stream().map(CardRegistry::get).toList();

    Deck blueDeck = new Deck(blueCards);
    Deck redDeck = new Deck(redCards);

    // Derive per-player seeded RNG for deterministic deck shuffling.
    // When an explicit seedOverride is provided, use it directly for reproducibility.
    // When using the session-level seed (no override), add resetCount so that
    // repeated resets within the same session get different shuffles.
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

    log.info(
        "Game session reset: level={}, ticksPerStep={}, seed={}",
        config.level(),
        ticksPerStep,
        seed);
  }

  /**
   * Executes one RL step: apply actions, tick the engine, return results. Detects when a submitted
   * action fails (not enough elixir, invalid placement, etc.) and reports it via the actionFailed
   * flags in StepResultDTO.
   *
   * @param blueAction action for the blue player (null = no-op)
   * @param redAction action for the red player (null = no-op)
   * @return step result with observation, reward, terminated, truncated, and action failure flags
   */
  public StepResultDTO step(StepAction blueAction, StepAction redAction) {
    // Track elixir before to detect failed actions
    boolean blueAttempted = blueAction != null && !blueAction.isNoop();
    boolean redAttempted = redAction != null && !redAction.isNoop();
    float blueElixirBefore = bluePlayer.getElixir().getCurrent();
    float redElixirBefore = redPlayer.getElixir().getCurrent();

    // Queue actions if not no-op
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

    // Detect action failure: if elixir didn't decrease, the action was rejected
    boolean blueActionFailed =
        blueAttempted && bluePlayer.getElixir().getCurrent() >= blueElixirBefore;
    boolean redActionFailed = redAttempted && redPlayer.getElixir().getCurrent() >= redElixirBefore;

    // Run simulation for ticksPerStep ticks
    engine.tick(ticksPerStep);

    // Build observation and compute reward
    ObservationDTO observation = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    RewardDTO reward = rewardCalculator.computeReward(engine.getGameState());

    boolean terminated = engine.getGameState().isGameOver() || !engine.isRunning();
    boolean truncated = false;

    return new StepResultDTO(
        observation, reward, terminated, truncated, blueActionFailed, redActionFailed);
  }

  /** Returns the current observation without stepping. */
  public ObservationDTO observe() {
    return ObservationBuilder.build(engine, bluePlayer, redPlayer);
  }

  public GameEngine getEngine() {
    return engine;
  }

  public Player getBluePlayer() {
    return bluePlayer;
  }

  public Player getRedPlayer() {
    return redPlayer;
  }
}
