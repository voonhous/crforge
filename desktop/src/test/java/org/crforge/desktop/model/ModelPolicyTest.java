/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import org.crforge.core.battle.GameData;
import org.crforge.env.EnvConfig;
import org.crforge.env.Schema;
import org.crforge.env.VectorEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java side of the model: ONNX Runtime run on an exported policy gives the numbers and picks
 * the actions the trainer's own path gives, and a model the environment would read differently is
 * refused.
 *
 * <p>The fixture ({@code synthetic-policy.onnx}, {@code synthetic-decisions.json}) is a small
 * network of random weights on the observation schema, exported as trained models are, with six
 * seats of the synthetic environment (no battle, no game data) and what the network gave on them
 * where it was trained: each seat's first choice log-probabilities, the tile log-probabilities of
 * its most likely slot, its value and win logit, and its most likely action. A change of the
 * observation schema fails {@link #theExportsSchemaIsTheEnvironments} until the fixture is made
 * again on the new schema.
 */
class ModelPolicyTest {

  /** Float rounding of two runtimes and of the padded and packed encoders. */
  private static final float TOLERANCE = 1e-4f;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static ModelPolicy policy;
  private static JsonNode seats;

  static Path fixture(String name) {
    try {
      return Paths.get(ModelPolicyTest.class.getResource(name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The fixture's policy, for the other tests of the package. */
  static ModelPolicy fixturePolicy() throws IOException {
    return ModelPolicy.load(fixture("synthetic-policy.onnx"));
  }

  @BeforeAll
  static void load() throws IOException {
    policy = fixturePolicy();
    try (InputStream in = ModelPolicyTest.class.getResourceAsStream("synthetic-decisions.json")) {
      seats = JSON.readTree(in).get("seats");
    }
  }

  @AfterAll
  static void close() {
    policy.close();
  }

  private static int[] observation(JsonNode seat) {
    int[] observation = new int[Schema.SEAT_INTS];
    JsonNode written = seat.get("observation");
    for (int i = 0; i < written.size(); i++) {
      observation[i] = written.get(i).asInt();
    }
    return observation;
  }

  private static byte[] mask(JsonNode seat) {
    byte[] mask = new byte[Schema.ACTIONS];
    seat.get("open").forEach(a -> mask[a.asInt()] = 1);
    return mask;
  }

  private static int[] ints(JsonNode list) {
    int[] values = new int[list.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = list.get(i).asInt();
    }
    return values;
  }

  @Test
  void theMostLikelyActionsAndTheirNumbersAreTheTrainersOwn() {
    assertThat(seats.size()).isEqualTo(6);
    int plays = 0;
    for (JsonNode seat : seats) {
      ModelPolicy.Decision decision =
          policy.decide(observation(seat), mask(seat), ints(seat.get("other_header")), null);
      assertThat(decision.action()).isEqualTo(seat.get("action").asInt());
      plays += decision.action() == Schema.WAIT ? 0 : 1;
      JsonNode first = seat.get("first_logp");
      assertThat(decision.first()).hasSize(first.size());
      for (int i = 0; i < first.size(); i++) {
        float expected = (float) first.get(i).asDouble();
        if (expected > ModelPolicy.CLOSED) {
          assertThat(decision.first()[i]).isCloseTo(expected, within(TOLERANCE));
        } else {
          assertThat(decision.first()[i]).isLessThan(ModelPolicy.CLOSED);
        }
      }
      JsonNode tiles = seat.get("tile_logp");
      float[] ours = decision.tiles()[seat.get("slot").asInt()];
      for (int t = 0; t < Schema.TILES; t++) {
        float expected = (float) tiles.get(t).asDouble();
        if (expected > ModelPolicy.CLOSED) {
          assertThat(ours[t]).isCloseTo(expected, within(TOLERANCE));
        }
      }
      assertThat(decision.value())
          .isCloseTo((float) seat.get("value").asDouble(), within(TOLERANCE));
      assertThat(decision.winLogit())
          .isCloseTo((float) seat.get("win_logit").asDouble(), within(TOLERANCE));
    }
    assertThat(plays).as("the fixture's seats play as well as wait").isGreaterThan(2);
  }

  @Test
  void aDrawnActionIsAlwaysOpen() {
    Random random = new Random(5);
    JsonNode seat = seats.get(0);
    byte[] mask = mask(seat);
    int distinct = 0;
    int previous = -1;
    for (int i = 0; i < 40; i++) {
      int action = policy.decide(observation(seat), mask, null, random).action();
      assertThat(mask[action]).isEqualTo((byte) 1);
      distinct += action != previous ? 1 : 0;
      previous = action;
    }
    assertThat(distinct).as("drawn, not always the most likely").isGreaterThan(5);
  }

  @Test
  void aPickTheMaskClosesIsTakenAsAWait() {
    float[] first = new float[1 + Schema.HAND_SLOTS + Schema.ABILITY_SLOTS];
    Arrays.fill(first, -1e9f);
    first[0] = -3f;
    first[1] = -0.1f;
    float[][] tiles = new float[Schema.HAND_SLOTS][Schema.TILES];
    byte[] mask = new byte[Schema.ACTIONS];
    mask[Schema.WAIT] = 1;
    assertThat(ModelPolicy.pick(first, tiles, mask, null)).isEqualTo(Schema.WAIT);
    mask[1] = 1;
    assertThat(ModelPolicy.pick(first, tiles, mask, null)).isEqualTo(1);
    first[5] = 0f;
    mask[Schema.FIRST_ABILITY] = 1;
    assertThat(ModelPolicy.pick(first, tiles, mask, null)).isEqualTo(Schema.FIRST_ABILITY);
  }

  @Test
  void theExportsSchemaIsTheEnvironments() {
    policy.checkSchema(VectorEnv.metadataJson(EnvConfig.DEFAULT, null));
    assertThat(policy.property("inputs")).isEqualTo("observations,masks,privileged");
    assertThat(policy.givesWinLogit()).isTrue();
  }

  @Test
  void aModelWithoutAVocabularyIsRefusedOnTheGameTables() {
    assertThatThrownBy(
            () -> policy.check(VectorEnv.vocabulary(GameData.tables()), policy.config(0, 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("carries no vocabulary");
  }

  @Test
  void aModelOfAnotherSchemaIsRefusedSayingWhatDiffers() throws IOException {
    JsonNode env = JSON.readTree(VectorEnv.metadataJson(EnvConfig.DEFAULT, null));
    ((ObjectNode) env.get("schema")).put("max_entities", 7);
    assertThatThrownBy(() -> policy.checkSchema(env.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max_entities");
    ((ObjectNode) env.get("schema")).put("version", 99);
    assertThatThrownBy(() -> policy.checkSchema(env.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "schema version " + Schema.VERSION + ", this environment writes version 99");
  }

  @Test
  void aFileThatIsNotAnExportedModelIsRefused(@TempDir Path folder) throws IOException {
    Path missing = folder.resolve("none.onnx");
    assertThatThrownBy(() -> ModelPolicy.load(missing))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no model file");
    Path junk = Files.writeString(folder.resolve("junk.onnx"), "not a model");
    assertThatThrownBy(() -> ModelPolicy.load(junk)).isInstanceOf(IOException.class);
  }

  @Test
  void theSettingsComeFromTheModelUnlessGiven() {
    EnvConfig config = policy.config(0, 0);
    assertThat(config.decisionTicks()).isEqualTo(EnvConfig.DEFAULT.decisionTicks());
    assertThat(config.level()).isEqualTo(EnvConfig.DEFAULT.level());
    EnvConfig asked = policy.config(14, 0);
    assertThat(asked.level()).isEqualTo(14);
    assertThat(asked.towerLevel()).isEqualTo(14);
    assertThat(policy.config(14, 9).towerLevel()).isEqualTo(9);
  }
}
