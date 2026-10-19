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

  /** The log-probability a closed choice is given at a temperature ({@link #atTemperature}). */
  private static final double CLOSED_LOGP = -1e9;

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

  /**
   * Where a temperature applies ({@link #atTemperature}): how a move is picked from the policy, not
   * the policy.
   */
  public enum TemperatureMode {
    /** The first choice (the wait, a hand slot or an ability) and the tile. */
    ALL,
    /**
     * The tile, and which hand slot or ability given that the model acts: the wait's probability,
     * and so the probability to act at all, stays as the policy gives it.
     */
    INNER
  }

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

  /**
   * Whether the model's win estimate reads the other side's header (a privileged critic of
   * training): the estimate then knows what the model's player does not see.
   */
  public boolean readsOtherSide() {
    return privileged;
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
    return decide(observation, mask, otherHeader, random, 1.0, TemperatureMode.ALL);
  }

  /**
   * As {@link #decide(int[], byte[], int[], Random)}, the action drawn at a temperature ({@link
   * #atTemperature}). The decision's log-probabilities are the policy's, as at temperature 1.
   *
   * @param random the generator to draw from; null takes the most likely choices whatever the
   *     temperature
   * @param temperature 1 the policy's own probabilities, below 1 sharper, 0 the most likely
   * @param mode where the temperature applies
   */
  public Decision decide(
      int[] observation,
      byte[] mask,
      int[] otherHeader,
      Random random,
      double temperature,
      TemperatureMode mode) {
    checkTemperature(temperature);
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
        int action = pick(first, tiles, mask, random, temperature, mode);
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
    return pick(first, tiles, mask, random, 1.0, TemperatureMode.ALL);
  }

  /**
   * As {@link #pick(float[], float[][], byte[], Random)}, each choice drawn from its probabilities
   * at a temperature ({@link #atTemperature}); a choice at temperature 0 is its most likely, so
   * draws nothing (in {@link TemperatureMode#INNER} the wait or not is still drawn).
   */
  static int pick(
      float[] first,
      float[][] tiles,
      byte[] mask,
      Random random,
      double temperature,
      TemperatureMode mode) {
    boolean tempered = random != null && temperature != 1.0;
    boolean drawFirst = !(temperature == 0.0 && mode == TemperatureMode.ALL);
    int choice =
        choose(
            tempered ? atTemperature(first, temperature, mode) : first, drawFirst ? random : null);
    int action;
    if (choice == 0) {
      action = Schema.WAIT;
    } else if (choice <= Schema.HAND_SLOTS) {
      float[] slot = tiles[choice - 1];
      int tile =
          choose(
              tempered ? atTemperature(slot, temperature, TemperatureMode.ALL) : slot,
              temperature == 0.0 ? null : random);
      action = 1 + (choice - 1) * Schema.TILES + tile;
    } else {
      action = Schema.FIRST_ABILITY + (choice - 1 - Schema.HAND_SLOTS);
    }
    return action < mask.length && mask[action] != 0 ? action : Schema.WAIT;
  }

  /**
   * One choice's log-probabilities (the first choice, or a slot's tiles) at a temperature: the
   * probabilities raised to {@code 1 / temperature} and normalised again, so below 1 the likely
   * choices gain and the unlikely lose; at 0 the most likely has it all. A closed choice stays
   * closed. {@link TemperatureMode#INNER}, for the first choice, keeps the wait's probability (and
   * so the probability to act at all) and sharpens only which of the other choices is taken given
   * that one is.
   *
   * @param logp the log-probabilities, a closed choice below {@link #CLOSED}
   * @param temperature 0 or more
   * @param mode where the temperature applies
   * @return the log-probabilities at the temperature, a closed choice at {@code -1e9}
   */
  static float[] atTemperature(float[] logp, double temperature, TemperatureMode mode) {
    checkTemperature(temperature);
    double[] values = new double[logp.length];
    for (int i = 0; i < logp.length; i++) {
      values[i] = logp[i];
    }
    double[] out;
    if (mode == TemperatureMode.ALL) {
      out = sharpen(values, 0, temperature);
    } else {
      // The wait as it is; the rest sharpened among themselves, then scaled back to the
      // probability to act.
      double act = logSumExp(values, 1);
      out = sharpen(values, 1, temperature);
      out[0] = values[0];
      for (int i = 1; i < out.length; i++) {
        out[i] = Math.max(act + out[i], CLOSED_LOGP);
      }
    }
    float[] result = new float[out.length];
    for (int i = 0; i < out.length; i++) {
      result[i] = (float) out[i];
    }
    return result;
  }

  /**
   * The entries from {@code from} on at a temperature, normalised over them; a closed entry stays
   * at {@link #CLOSED_LOGP}, the entries before {@code from} are left at 0 for the caller.
   */
  private static double[] sharpen(double[] logp, int from, double temperature) {
    double[] out = new double[logp.length];
    int best = -1;
    double top = Double.NEGATIVE_INFINITY;
    for (int i = from; i < logp.length; i++) {
      boolean closed = logp[i] < CLOSED;
      out[i] = closed ? CLOSED_LOGP : temperature == 0.0 ? 0.0 : logp[i] / temperature;
      if (!closed && (best < 0 || logp[i] > logp[best])) {
        best = i;
      }
      if (!closed) {
        top = Math.max(top, out[i]);
      }
    }
    if (best < 0) {
      return out; // every entry closed
    }
    if (temperature == 0.0) {
      for (int i = from; i < logp.length; i++) {
        out[i] = i == best ? 0.0 : CLOSED_LOGP;
      }
      return out;
    }
    double sum = 0;
    for (int i = from; i < logp.length; i++) {
      if (logp[i] >= CLOSED) {
        sum += Math.exp(out[i] - top);
      }
    }
    double norm = top + Math.log(sum);
    for (int i = from; i < logp.length; i++) {
      if (logp[i] >= CLOSED) {
        out[i] -= norm;
      }
    }
    return out;
  }

  /** The log of the summed probabilities of the open entries from {@code from} on. */
  private static double logSumExp(double[] logp, int from) {
    double top = Double.NEGATIVE_INFINITY;
    for (int i = from; i < logp.length; i++) {
      if (logp[i] >= CLOSED) {
        top = Math.max(top, logp[i]);
      }
    }
    if (top == Double.NEGATIVE_INFINITY) {
      return CLOSED_LOGP;
    }
    double sum = 0;
    for (int i = from; i < logp.length; i++) {
      if (logp[i] >= CLOSED) {
        sum += Math.exp(logp[i] - top);
      }
    }
    return top + Math.log(sum);
  }

  private static void checkTemperature(double temperature) {
    if (!(temperature >= 0.0) || Double.isInfinite(temperature)) {
      throw new IllegalArgumentException("a temperature is 0 or more, got " + temperature);
    }
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
