/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.crforge.env.EnvConfig;
import org.crforge.env.Schema;
import org.crforge.env.VectorEnv;
import org.crforge.env.Vocabulary;

/**
 * A trained policy exported as an Open Neural Network Exchange (ONNX) file, run by ONNX Runtime: a
 * seat's observation and legality mask in, the policy's log-probabilities and the action it picks
 * out.
 *
 * <p>The file's graph takes {@code observations}, int32 {@code (batch, }{@link Schema#SEAT_INTS}
 * {@code )}, each seat's observation as the environment writes it, and {@code masks}, bool {@code
 * (batch, }{@link Schema#ACTIONS}{@code )}; a model with a privileged critic also takes {@code
 * privileged}, the other seat's header, which only its win estimate reads. It gives {@code
 * first_logp} {@code (batch, 1 + hand slots + ability slots)}, the log-probabilities of the first
 * choice (the wait, each hand slot, each champion ability), {@code tile_logp} {@code (batch, hand
 * slots, tiles)}, each slot's tile log-probabilities, {@code value}, and with a win head {@code
 * win_logit}. The graph masks what the mask closes: a closed choice's log-probability is about
 * minus a billion.
 *
 * <p>The file's metadata carries the observation schema and the vocabulary the model was trained
 * on. {@link #check} refuses a model the environment would read differently: another export format,
 * schema (version, sizes, field names) or vocabulary (a card numbered otherwise, as on another data
 * version), each with a message saying what differs.
 */
public final class ModelPolicy implements AutoCloseable {

  /** The export format this reader reads ({@code crforge.export_format}). */
  public static final int EXPORT_FORMAT = 1;

  /** The metadata keys' prefix. */
  static final String PREFIX = "crforge.";

  /** The first choice's log-probabilities of a closed choice are below this. */
  public static final float CLOSED = -1e8f;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final OrtEnvironment environment;
  private final OrtSession session;
  private final Map<String, String> properties;
  private final JsonNode metadata;
  private final boolean privileged;
  private final boolean wins;
  private final String source;

  /**
   * One decision of the policy for a seat.
   *
   * @param first the first choice's log-probabilities: the wait, each hand slot, each ability
   * @param tiles each hand slot's tile log-probabilities, tiles numbered {@code row * }{@link
   *     Schema#COLS}{@code + col} in the seat's frame
   * @param value the value head's estimate
   * @param winLogit the logit of the probability that the seat wins, or NaN for a model without a
   *     win head
   * @param action the action picked
   */
  public record Decision(float[] first, float[][] tiles, float value, float winLogit, int action) {}

  private ModelPolicy(OrtEnvironment environment, OrtSession session, String source)
      throws OrtException {
    this.environment = environment;
    this.session = session;
    this.source = source;
    this.properties = Map.copyOf(session.getMetadata().getCustomMetadata());
    String format = properties.get(PREFIX + "export_format");
    if (!String.valueOf(EXPORT_FORMAT).equals(format)) {
      throw new IllegalArgumentException(
          source
              + " is "
              + (format == null
                  ? "not a model exported for crforge (its metadata names no export format)"
                  : "of export format " + format + ", this visualizer reads " + EXPORT_FORMAT));
    }
    String text = properties.get(PREFIX + "metadata");
    if (text == null) {
      throw new IllegalArgumentException(source + " carries no schema metadata");
    }
    try {
      this.metadata = JSON.readTree(text);
    } catch (IOException e) {
      throw new IllegalArgumentException(source + "'s schema metadata is not JSON", e);
    }
    Map<String, NodeInfo> inputs = session.getInputInfo();
    Map<String, NodeInfo> outputs = session.getOutputInfo();
    for (String name : List.of("observations", "masks")) {
      if (!inputs.containsKey(name)) {
        throw new IllegalArgumentException(source + " takes no input " + name);
      }
    }
    for (String name : List.of("first_logp", "tile_logp", "value")) {
      if (!outputs.containsKey(name)) {
        throw new IllegalArgumentException(source + " gives no output " + name);
      }
    }
    this.privileged = inputs.containsKey("privileged");
    this.wins = outputs.containsKey("win_logit");
    checkShape(outputs, "first_logp", 1 + Schema.HAND_SLOTS + Schema.ABILITY_SLOTS);
    checkShape(outputs, "tile_logp", Schema.HAND_SLOTS, Schema.TILES);
  }

  /**
   * Reads an exported model.
   *
   * @param file the ONNX file
   * @return the policy, not yet checked against any tables ({@link #check})
   * @throws IOException when the file cannot be read
   * @throws IllegalArgumentException when the file is not a model of this export format
   */
  public static ModelPolicy load(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IOException("no model file at " + file.toAbsolutePath().normalize());
    }
    OrtEnvironment environment = OrtEnvironment.getEnvironment();
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      // One decision of one seat at a time: a thread or two is plenty, and leaves the rest of the
      // machine to the battle and the screen.
      options.setIntraOpNumThreads(2);
      OrtSession session = environment.createSession(file.toString(), options);
      try {
        return new ModelPolicy(environment, session, file.getFileName().toString());
      } catch (RuntimeException e) {
        session.close();
        throw e;
      }
    } catch (OrtException e) {
      throw new IOException("ONNX Runtime cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  private void checkShape(Map<String, NodeInfo> outputs, String name, long... expected) {
    if (!(outputs.get(name).getInfo() instanceof TensorInfo info)) {
      throw new IllegalArgumentException(source + "'s " + name + " is not a tensor");
    }
    long[] shape = info.getShape();
    long[] tail = Arrays.copyOfRange(shape, 1, shape.length);
    if (!Arrays.equals(tail, expected)) {
      throw new IllegalArgumentException(
          source
              + "'s "
              + name
              + " is "
              + Arrays.toString(shape)
              + "; this environment's schema needs (batch, "
              + Arrays.toString(expected).replaceAll("[\\[\\]]", "")
              + ")");
    }
  }

  /** The file's name, for messages. */
  public String source() {
    return source;
  }

  /** A metadata value of the export ({@code crforge.<key>}), or null. */
  public String property(String key) {
    return properties.get(PREFIX + key);
  }

  /** Whether the model gives a win estimate. */
  public boolean givesWinLogit() {
    return wins;
  }

  /** The data version of the vocabulary the model was trained on, or null. */
  public String dataVersion() {
    JsonNode version = metadata.path("vocabulary").path("data_version");
    return version.isMissingNode() ? null : version.asText();
  }

  /**
   * The environment's settings the model was trained with: its decision interval and mask rule, and
   * the levels given, else those its export names, else the default.
   *
   * @param level the card level asked for, or 0 for the model's
   * @param towerLevel the tower level asked for, or 0 for the model's
   */
  public EnvConfig config(int level, int towerLevel) {
    JsonNode config = metadata.path("config");
    int ticks = config.path("decision_ticks").asInt(EnvConfig.DEFAULT.decisionTicks());
    int cards = level > 0 ? level : intProperty("level", EnvConfig.DEFAULT.level());
    int towers =
        towerLevel > 0
            ? towerLevel
            : intProperty("tower_level", level > 0 ? level : EnvConfig.DEFAULT.towerLevel());
    int mask = metadata.path("mask_version").asInt(Schema.MASK_VERSION);
    return new EnvConfig(ticks, cards, towers, 0, null, mask);
  }

  private int intProperty(String key, int otherwise) {
    String value = property(key);
    return value == null || value.isBlank() ? otherwise : Integer.parseInt(value.trim());
  }

  /**
   * Refuses a model this environment would read differently from how it was trained: a schema other
   * than the environment's (version, sizes, field names, scales), or a vocabulary that numbers a
   * card or buff otherwise than these tables, as a model of another data version may.
   *
   * @param vocabulary the vocabulary of the tables the battle is played on
   * @param config the environment's settings
   * @throws IllegalArgumentException naming the first difference
   */
  public void check(Vocabulary vocabulary, EnvConfig config) {
    checkSchema(VectorEnv.metadataJson(config, vocabulary));
    if (!metadata.has("vocabulary")) {
      throw new IllegalArgumentException(
          source + " carries no vocabulary: it was not trained on Ladder battles");
    }
    try {
      vocabulary.checkSame(metadata.toString());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          source
              + " was trained on the tables of data version "
              + dataVersion()
              + ", which these ("
              + vocabulary.dataVersion()
              + ") number otherwise: "
              + e.getMessage()
              + "; open the visualizer on data version "
              + dataVersion()
              + " (-Pcrforge.dataVersion="
              + dataVersion()
              + ")",
          e);
    }
  }

  /**
   * Refuses a model whose schema differs from the one of an environment's metadata export.
   *
   * @param environmentMetadata the export, {@link VectorEnv#metadataJson}
   */
  void checkSchema(String environmentMetadata) {
    JsonNode ours;
    try {
      ours = JSON.readTree(environmentMetadata).path("schema");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    JsonNode theirs = metadata.path("schema");
    if (theirs.path("version").asInt(-1) != ours.path("version").asInt()) {
      throw new IllegalArgumentException(
          source
              + " was trained on observation schema version "
              + theirs.path("version").asText("none")
              + ", this environment writes version "
              + ours.path("version").asInt()
              + ": export a model trained on this version");
    }
    List<String> differ = new ArrayList<>();
    for (Map.Entry<String, JsonNode> field : ours.properties()) {
      if (!field.getValue().equals(theirs.get(field.getKey()))) {
        differ.add(field.getKey());
      }
    }
    if (!differ.isEmpty()) {
      throw new IllegalArgumentException(
          source + "'s observation schema differs from this environment's in " + differ);
    }
  }

  /**
   * Runs the policy on one seat's observation and picks an action: the most likely first choice
   * and, for a play, the most likely tile of its slot; or, with a generator, each drawn from its
   * probabilities. An action the mask closes is never picked (the wait is picked instead, which the
   * mask always opens).
   *
   * @param observation the seat's observation, {@link Schema#SEAT_INTS} integers
   * @param mask the seat's legality mask, {@link Schema#ACTIONS} values, 1 for open
   * @param otherHeader the other seat's header, {@link Schema#HEADER_INTS} integers, for a model
   *     with a privileged critic's win estimate; null gives a model that reads it zeros, so its win
   *     estimate is not meaningful
   * @param random the generator to draw from, or null for the most likely choices
   * @return the decision
   */
  public Decision decide(int[] observation, byte[] mask, int[] otherHeader, Random random) {
    if (observation.length != Schema.SEAT_INTS || mask.length != Schema.ACTIONS) {
      throw new IllegalArgumentException(
          "an observation is "
              + Schema.SEAT_INTS
              + " integers and a mask "
              + Schema.ACTIONS
              + " values, got "
              + observation.length
              + " and "
              + mask.length);
    }
    boolean[][] open = new boolean[1][Schema.ACTIONS];
    for (int a = 0; a < Schema.ACTIONS; a++) {
      open[0][a] = mask[a] != 0;
    }
    Map<String, OnnxTensor> feed = new HashMap<>();
    try {
      feed.put(
          "observations",
          OnnxTensor.createTensor(
              environment, IntBuffer.wrap(observation), new long[] {1, Schema.SEAT_INTS}));
      feed.put("masks", OnnxTensor.createTensor(environment, open));
      if (privileged) {
        int[] header = otherHeader == null ? new int[Schema.HEADER_INTS] : otherHeader;
        feed.put(
            "privileged",
            OnnxTensor.createTensor(
                environment,
                IntBuffer.wrap(Arrays.copyOf(header, Schema.HEADER_INTS)),
                new long[] {1, Schema.HEADER_INTS}));
      }
      try (OrtSession.Result result = session.run(feed)) {
        float[] first = ((float[][]) value(result, "first_logp"))[0];
        float[][] tiles = ((float[][][]) value(result, "tile_logp"))[0];
        float value = ((float[]) value(result, "value"))[0];
        float win = wins ? ((float[]) value(result, "win_logit"))[0] : Float.NaN;
        int action = pick(first, tiles, mask, random);
        return new Decision(first, tiles, value, win, action);
      }
    } catch (OrtException e) {
      throw new IllegalStateException(source + " failed to run: " + e.getMessage(), e);
    } finally {
      feed.values().forEach(OnnxTensor::close);
    }
  }

  private static Object value(OrtSession.Result result, String name) throws OrtException {
    OnnxValue value = result.get(name).orElseThrow();
    return value.getValue();
  }

  /**
   * The action of a decision's log-probabilities: the first choice, then for a play its slot's
   * tile, each the most likely or drawn (Gumbel-max) from a generator; the wait when the pick is
   * closed in the mask.
   */
  static int pick(float[] first, float[][] tiles, byte[] mask, Random random) {
    int choice = choose(first, random);
    int action;
    if (choice == 0) {
      action = Schema.WAIT;
    } else if (choice <= Schema.HAND_SLOTS) {
      int tile = choose(tiles[choice - 1], random);
      action = 1 + (choice - 1) * Schema.TILES + tile;
    } else {
      action = Schema.FIRST_ABILITY + (choice - 1 - Schema.HAND_SLOTS);
    }
    return action < mask.length && mask[action] != 0 ? action : Schema.WAIT;
  }

  private static int choose(float[] logp, Random random) {
    int best = 0;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < logp.length; i++) {
      if (logp[i] < CLOSED) {
        continue;
      }
      double score = logp[i];
      if (random != null) {
        double uniform = Math.min(Math.max(random.nextDouble(), 1e-10), 1.0 - 1e-7);
        score -= Math.log(-Math.log(uniform));
      }
      if (score > bestScore) {
        bestScore = score;
        best = i;
      }
    }
    return best;
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException e) {
      throw new IllegalStateException(e);
    }
  }
}
