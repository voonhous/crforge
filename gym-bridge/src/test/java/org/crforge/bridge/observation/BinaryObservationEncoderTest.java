package org.crforge.bridge.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.crforge.bridge.dto.EntityDTO;
import org.crforge.bridge.dto.HandCardDTO;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.PlayerObsDTO;
import org.crforge.bridge.dto.TowerDTO;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies that BinaryObservationEncoder produces output consistent with ObservationBuilder. */
class BinaryObservationEncoderTest {

  private GameEngine engine;
  private Player bluePlayer;
  private Player redPlayer;
  private BinaryObservationEncoder encoder;

  @BeforeEach
  void setUp() {
    engine = new GameEngine();

    List<Card> deck =
        List.of(
            CardRegistry.get("knight"),
            CardRegistry.get("archer"),
            CardRegistry.get("fireball"),
            CardRegistry.get("arrows"),
            CardRegistry.get("giant"),
            CardRegistry.get("musketeer"),
            CardRegistry.get("minions"),
            CardRegistry.get("valkyrie"));

    LevelConfig levelConfig = new LevelConfig(11);
    bluePlayer = new Player(Team.BLUE, new Deck(deck), false, levelConfig);
    redPlayer = new Player(Team.RED, new Deck(deck), true, levelConfig);

    Standard1v1Match match = new Standard1v1Match(11);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine.setMatch(match);
    engine.initMatch();

    encoder = new BinaryObservationEncoder();
  }

  @Test
  void encodedObservationHasCorrectSize() {
    byte[] bytes = encoder.encodeObservation(engine, bluePlayer, redPlayer);
    assertThat(bytes.length).isEqualTo(BinaryObservationEncoder.OBS_BYTES);
  }

  @Test
  void stepResultHasCorrectSize() {
    byte[] bytes =
        encoder.encodeStepResult(
            engine, bluePlayer, redPlayer, 1.5f, -0.5f, false, false, true, false);
    assertThat(bytes.length).isEqualTo(BinaryObservationEncoder.STEP_RESULT_BYTES);
  }

  @Test
  void stepResultHeaderDecodesCorrectly() {
    byte[] bytes =
        encoder.encodeStepResult(
            engine, bluePlayer, redPlayer, 1.5f, -0.5f, true, false, true, false);

    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    assertThat(buf.getFloat(0)).isCloseTo(1.5f, offset(0.001f));
    assertThat(buf.getFloat(4)).isCloseTo(-0.5f, offset(0.001f));
    assertThat(bytes[8]).isEqualTo((byte) 1); // terminated
    assertThat(bytes[9]).isEqualTo((byte) 0); // truncated
    assertThat(bytes[10]).isEqualTo((byte) 1); // blueActionFailed
    assertThat(bytes[11]).isEqualTo((byte) 0); // redActionFailed
  }

  @Test
  void globalFieldsMatchJsonObservation() {
    engine.tick(30); // 1 second

    ObservationDTO json = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    float[] obs = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    // frame (index 0)
    assertThat(obs[0]).isCloseTo(json.frame(), offset(0.01f));
    // game_time (index 1)
    assertThat(obs[1]).isCloseTo(json.gameTimeSeconds(), offset(0.01f));
    // is_overtime (index 2)
    assertThat(obs[2]).isCloseTo(json.isOvertime() ? 1f : 0f, offset(0.01f));
  }

  @Test
  void elixirAndCrownsMatchJsonObservation() {
    ObservationDTO json = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    float[] obs = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    // elixir (indices 3, 4)
    assertThat(obs[3]).isCloseTo(json.bluePlayer().elixir(), offset(0.01f));
    assertThat(obs[4]).isCloseTo(json.redPlayer().elixir(), offset(0.01f));

    // crowns (indices 5, 6)
    assertThat(obs[5]).isCloseTo(json.bluePlayer().crowns(), offset(0.01f));
    assertThat(obs[6]).isCloseTo(json.redPlayer().crowns(), offset(0.01f));
  }

  @Test
  void handFieldsMatchJsonObservation() {
    ObservationDTO json = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    float[] obs = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    PlayerObsDTO blueObs = json.bluePlayer();
    List<HandCardDTO> hand = blueObs.hand();

    for (int i = 0; i < hand.size(); i++) {
      HandCardDTO card = hand.get(i);
      // hand_costs (indices 7-10, normalized /10)
      assertThat(obs[7 + i]).isCloseTo(card.cost() / 10f, offset(0.01f));
      // hand_card_ids (indices 15-18)
      assertThat(obs[15 + i]).isCloseTo(card.cardIndex(), offset(0.01f));
    }

    // next_card (indices 19-21)
    HandCardDTO next = blueObs.nextCard();
    if (next != null) {
      assertThat(obs[19]).isCloseTo(next.cost() / 10f, offset(0.01f));
      assertThat(obs[21]).isCloseTo(next.cardIndex(), offset(0.01f));
    }
  }

  @Test
  void towerFieldsMatchJsonObservation() {
    ObservationDTO json = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    float[] obs = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    // Blue towers at indices 22-33, Red towers at 34-45
    List<TowerDTO> blueTowers = json.bluePlayer().towers();
    for (int i = 0; i < blueTowers.size(); i++) {
      TowerDTO t = blueTowers.get(i);
      int base = 22 + i * 4;
      float expectedHpFrac = (float) t.hp() / Math.max(t.maxHp(), 1);
      assertThat(obs[base]).isCloseTo(expectedHpFrac, offset(0.01f));
      assertThat(obs[base + 1]).isCloseTo(t.x() / 18f, offset(0.01f));
      assertThat(obs[base + 2]).isCloseTo(t.y() / 32f, offset(0.01f));
      assertThat(obs[base + 3]).isCloseTo(t.alive() ? 1f : 0f, offset(0.01f));
    }
  }

  @Test
  void entityCountMatchesJsonObservation() {
    ObservationDTO json = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    float[] obs = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    // num_entities at index 1070
    assertThat((int) obs[1070]).isEqualTo(json.entities().size());
  }

  @Test
  void entityFeaturesMatchJsonObservation() {
    // Deploy a knight to have a non-tower entity
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 10f);
    engine.queueAction(bluePlayer, action);
    engine.tick(60); // 2 seconds for deploy + movement

    ObservationDTO json = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    float[] obs = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    List<EntityDTO> entities = json.entities();
    int numEntities = Math.min(entities.size(), 64);
    assertThat((int) obs[1070]).isEqualTo(numEntities);

    for (int i = 0; i < numEntities; i++) {
      EntityDTO e = entities.get(i);
      int base = 46 + i * 16;

      // Team
      float expectedTeam = e.team().equals("BLUE") ? 0f : 1f;
      assertThat(obs[base]).isCloseTo(expectedTeam, offset(0.01f));

      // Position normalized
      assertThat(obs[base + 3]).isCloseTo(e.x() / 18f, offset(0.01f));
      assertThat(obs[base + 4]).isCloseTo(e.y() / 32f, offset(0.01f));

      // HP fraction
      int maxHp = Math.max(e.maxHp(), 1);
      assertThat(obs[base + 5]).isCloseTo((float) e.hp() / maxHp, offset(0.01f));
    }
  }

  @Test
  void reusesBufferAcrossMultipleCalls() {
    float[] obs1 = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));
    engine.tick(30);
    float[] obs2 = decodeObs(encoder.encodeObservation(engine, bluePlayer, redPlayer));

    // Frame should be different
    assertThat(obs2[0]).isGreaterThan(obs1[0]);
    // Both should have the same total number of floats
    assertThat(obs1.length).isEqualTo(obs2.length);
  }

  private float[] decodeObs(byte[] bytes) {
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    float[] result = new float[BinaryObservationEncoder.OBS_FLOATS];
    for (int i = 0; i < result.length; i++) {
      result[i] = buf.getFloat();
    }
    return result;
  }
}
