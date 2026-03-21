package org.crforge.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.bridge.dto.InitConfig;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.StepAction;
import org.crforge.bridge.dto.StepResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameSessionTest {

  private static final List<String> TEST_DECK =
      List.of(
          "knight", "archer", "fireball", "arrows", "giant", "musketeer", "minions", "valkyrie");

  private GameSession session;

  @BeforeEach
  void setUp() {
    session = new GameSession();
  }

  @Test
  void initAndResetCreatesPlayableMatch() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 1);
    session.init(config);

    ObservationDTO obs = session.observe();
    assertThat(obs.frame()).isZero();
    assertThat(obs.bluePlayer().hand()).hasSize(4);
    assertThat(obs.redPlayer().hand()).hasSize(4);
    assertThat(obs.entities()).hasSize(6); // 6 towers
  }

  @Test
  void stepAdvancesSimulation() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 30);
    session.init(config);

    // Step with no actions
    StepResultDTO result = session.step(null, null);

    assertThat(result.observation().frame()).isEqualTo(30);
    assertThat(result.observation().gameTimeSeconds())
        .isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
    assertThat(result.terminated()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void stepWithActionQueuesDeployment() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 1);
    session.init(config);

    // Play blue card at valid position
    StepAction blueAction = new StepAction(0, 9.0f, 5.0f);
    StepResultDTO result = session.step(blueAction, null);

    // After 1 tick, deployment is queued but not yet spawned (sync delay)
    assertThat(result.observation().frame()).isEqualTo(1);
  }

  @Test
  void resetAfterStepsResetsToInitialState() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 30);
    session.init(config);

    // Advance some ticks
    session.step(null, null);
    session.step(null, null);

    // Reset
    session.reset();
    ObservationDTO obs = session.observe();
    assertThat(obs.frame()).isZero();
  }

  @Test
  void resetWithoutInitThrows() {
    assertThatThrownBy(() -> session.reset()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void multipleResetsReuseEngineWithCleanState() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 30);
    session.init(config);

    // Advance significantly and deploy a card
    StepAction blueAction = new StepAction(0, 9.0f, 5.0f);
    session.step(blueAction, null);
    session.step(null, null);
    session.step(null, null);

    assertThat(session.observe().frame()).isGreaterThan(0);

    // Reset and verify clean state
    session.reset();
    ObservationDTO obs = session.observe();
    assertThat(obs.frame()).isZero();
    assertThat(obs.bluePlayer().elixir()).isEqualTo(5.0f);
    assertThat(obs.entities()).hasSize(6); // only 6 towers, no leftover entities

    // Reset again to verify repeated resets work
    session.step(null, null);
    session.reset();
    obs = session.observe();
    assertThat(obs.frame()).isZero();
    assertThat(obs.entities()).hasSize(6);
  }

  @Test
  void deterministicSeedProducesSameResultsAcrossResets() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 30, 42L);
    session.init(config);

    // Run 2 steps and capture observation
    session.step(null, null);
    StepResultDTO result1 = session.step(null, null);
    float elixir1 = result1.observation().bluePlayer().elixir();

    // Reset with same seed and run same steps
    session.reset(42L);
    session.step(null, null);
    StepResultDTO result2 = session.step(null, null);
    float elixir2 = result2.observation().bluePlayer().elixir();

    assertThat(elixir2).isEqualTo(elixir1);
  }

  @Test
  void noopActionsDoNotAffectElixir() {
    InitConfig config = new InitConfig(TEST_DECK, TEST_DECK, 11, 1);
    session.init(config);

    float initialBlueElixir = session.observe().bluePlayer().elixir();

    // Step with no-op
    StepResultDTO result =
        session.step(
            new StepAction(-1, 0, 0), // no-op
            null);

    // Elixir should only change by regen, not by spending
    float afterElixir = result.observation().bluePlayer().elixir();
    // 1 tick of regen at rate 1/2.8 per second, dt=1/30
    float expectedRegen = (1.0f / 2.8f) * (1.0f / 30.0f);
    assertThat(afterElixir)
        .isCloseTo(initialBlueElixir + expectedRegen, org.assertj.core.data.Offset.offset(0.01f));
  }
}
