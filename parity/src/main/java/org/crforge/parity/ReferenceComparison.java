package org.crforge.parity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The exact first-divergence comparison of a run's trace with a recorded reference trace.
 *
 * <p>Both sides are a manifest and the trace's bytes. Each side is first checked on its own: the
 * trace's SHA-256 is the manifest's {@code trace_sha256}; it has one observation per executed step
 * plus the first, numbered by tick from 0; and under the terminal-aware schema every observation
 * has a boolean {@code stopped}, only the last may be stopped, and the manifest's {@code
 * termination} agrees with it. Then the two must be of the same identity (schema, scenario and
 * horizon), and the observations are compared in order, field by field, with no tolerance: a number
 * is not equal to a number of another kind (an integer is not a decimal), and object fields are
 * visited in sorted order.
 *
 * <p>The outcome is {@code diagnostic_match} (every observation equal: agreement with a recorded
 * reference battle, not a release pass), {@code mismatch} (with the first divergent observation,
 * the path of the first differing field and both values) or {@code invalid} (a side that fails its
 * own checks, or different identities).
 */
public final class ReferenceComparison {

  /** The outcome of agreement. */
  public static final String DIAGNOSTIC_MATCH = "diagnostic_match";

  /** The outcome of a difference. */
  public static final String MISMATCH = "mismatch";

  /** The outcome of a side that fails its own checks, or of two sides of different identity. */
  public static final String INVALID = "invalid";

  /** The manifest fields both sides must agree on before their observations are compared. */
  static final List<String> IDENTITY = List.of("schema", "scenario_sha256", "ticks");

  /** A line is one JSON value and nothing after it. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private ReferenceComparison() {
    // Utility class
  }

  /**
   * A comparison's result.
   *
   * @param outcome {@link #DIAGNOSTIC_MATCH}, {@link #MISMATCH} or {@link #INVALID}
   * @param firstDivergentTick the first observation that differs (mismatch only, else -1)
   * @param equalPrefixSteps the steps known equal before it (mismatch only, else -1)
   * @param difference the first difference: {@code path} and the {@code reference} and {@code java}
   *     values, or {@code path} with {@code missing_fields} and {@code extra_fields} (mismatch
   *     only)
   * @param comparedSteps the steps compared (agreement only, else -1)
   * @param error why the comparison is invalid (invalid only)
   */
  public record Result(
      String outcome,
      int firstDivergentTick,
      int equalPrefixSteps,
      ObjectNode difference,
      int comparedSteps,
      String error) {

    /** Whether the run agrees with the reference. */
    public boolean matches() {
      return DIAGNOSTIC_MATCH.equals(outcome);
    }

    static Result mismatch(int tick, int equalPrefix, ObjectNode difference) {
      return new Result(MISMATCH, tick, equalPrefix, difference, -1, null);
    }

    static Result invalid(String error) {
      return new Result(INVALID, -1, -1, null, -1, error);
    }
  }

  /** A side that fails its own checks, or two sides that cannot be compared. */
  private static final class InvalidRunException extends Exception {
    InvalidRunException(String message) {
      super(message);
    }
  }

  /**
   * Compares a run with a reference.
   *
   * @param referenceManifest the reference's fields ({@code schema}, {@code ticks}, {@code
   *     scenario_sha256}, {@code observations}, {@code trace_sha256}, and under the terminal-aware
   *     schema {@code executed_ticks} and {@code termination})
   * @param referenceTrace the reference trace's bytes
   * @param runManifest the run's manifest, with the same fields
   * @param runTrace the run's trace bytes
   * @return the result
   */
  public static Result compare(
      JsonNode referenceManifest, byte[] referenceTrace, JsonNode runManifest, byte[] runTrace) {
    List<JsonNode> reference;
    List<JsonNode> run;
    try {
      reference = read("the reference", referenceManifest, referenceTrace);
      run = read("the run", runManifest, runTrace);
      for (String key : IDENTITY) {
        if (!referenceManifest.has(key) || !runManifest.has(key)) {
          throw new InvalidRunException("no identity field " + key);
        }
        if (!looselyEqual(referenceManifest.get(key), runManifest.get(key))) {
          throw new InvalidRunException("identity mismatch: " + key);
        }
      }
    } catch (InvalidRunException e) {
      return Result.invalid(e.getMessage());
    }
    int common = Math.min(reference.size(), run.size());
    for (int i = 0; i < common; i++) {
      ObjectNode delta = firstDifference(reference.get(i), run.get(i), "$");
      if (delta != null) {
        return Result.mismatch(i, Math.max(0, i - 1), delta);
      }
    }
    if (reference.size() != run.size()) {
      ObjectNode delta = JSON.objectNode();
      delta.put("path", "$.observations.length");
      delta.put("reference", reference.size());
      delta.put("java", run.size());
      return Result.mismatch(common, common - 1, delta);
    }
    return new Result(DIAGNOSTIC_MATCH, -1, -1, null, reference.size() - 1, null);
  }

  /**
   * The first difference between two values, or null when they are equal. Values of different kinds
   * differ at their path; objects differ first by their field names, then field by field in sorted
   * order; arrays first by their length, then element by element.
   */
  static ObjectNode firstDifference(JsonNode a, JsonNode b, String path) {
    if (kind(a) != kind(b)) {
      return valueDifference(path, a, b);
    }
    switch (kind(a)) {
      case OBJECT -> {
        TreeSet<String> left = fieldNames(a);
        TreeSet<String> right = fieldNames(b);
        if (!left.equals(right)) {
          ObjectNode delta = JSON.objectNode();
          delta.put("path", path);
          ArrayNode missing = delta.putArray("missing_fields");
          left.stream().filter(k -> !right.contains(k)).forEach(missing::add);
          ArrayNode extra = delta.putArray("extra_fields");
          right.stream().filter(k -> !left.contains(k)).forEach(extra::add);
          return delta;
        }
        for (String key : left) {
          ObjectNode delta = firstDifference(a.get(key), b.get(key), path + "." + key);
          if (delta != null) {
            return delta;
          }
        }
        return null;
      }
      case ARRAY -> {
        if (a.size() != b.size()) {
          ObjectNode delta = JSON.objectNode();
          delta.put("path", path + ".length");
          delta.put("reference", a.size());
          delta.put("java", b.size());
          return delta;
        }
        for (int i = 0; i < a.size(); i++) {
          ObjectNode delta = firstDifference(a.get(i), b.get(i), path + "[" + i + "]");
          if (delta != null) {
            return delta;
          }
        }
        return null;
      }
      default -> {
        return sameValue(a, b) ? null : valueDifference(path, a, b);
      }
    }
  }

  /** The kinds of JSON value the comparison tells apart. */
  private enum Kind {
    OBJECT,
    ARRAY,
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    NULL
  }

  private static Kind kind(JsonNode node) {
    if (node.isObject()) {
      return Kind.OBJECT;
    }
    if (node.isArray()) {
      return Kind.ARRAY;
    }
    if (node.isTextual()) {
      return Kind.STRING;
    }
    if (node.isIntegralNumber()) {
      return Kind.INTEGER;
    }
    if (node.isNumber()) {
      return Kind.DECIMAL;
    }
    if (node.isBoolean()) {
      return Kind.BOOLEAN;
    }
    if (node.isNull()) {
      return Kind.NULL;
    }
    throw new IllegalArgumentException("not a JSON value: " + node);
  }

  /** Two leaves of the same kind: integers by value, decimals as doubles, the rest by equality. */
  private static boolean sameValue(JsonNode a, JsonNode b) {
    return switch (kind(a)) {
      case INTEGER -> a.bigIntegerValue().equals(b.bigIntegerValue());
      case DECIMAL -> a.doubleValue() == b.doubleValue();
      case STRING -> a.textValue().equals(b.textValue());
      case BOOLEAN -> a.booleanValue() == b.booleanValue();
      case NULL -> true;
      default -> throw new IllegalArgumentException("not a leaf: " + a);
    };
  }

  private static ObjectNode valueDifference(String path, JsonNode a, JsonNode b) {
    ObjectNode delta = JSON.objectNode();
    delta.put("path", path);
    delta.set("reference", a);
    delta.set("java", b);
    return delta;
  }

  private static TreeSet<String> fieldNames(JsonNode node) {
    TreeSet<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /**
   * Equality across number kinds and booleans, as identity fields are compared: integers, decimals
   * and booleans (true as 1) by numeric value; arrays and objects element by element.
   */
  static boolean looselyEqual(JsonNode a, JsonNode b) {
    BigDecimal left = numeric(a);
    BigDecimal right = numeric(b);
    if (left != null || right != null) {
      return left != null && right != null && left.compareTo(right) == 0;
    }
    if (a.isObject() && b.isObject()) {
      if (!fieldNames(a).equals(fieldNames(b))) {
        return false;
      }
      for (Map.Entry<String, JsonNode> field : a.properties()) {
        if (!looselyEqual(field.getValue(), b.get(field.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        if (!looselyEqual(a.get(i), b.get(i))) {
          return false;
        }
      }
      return true;
    }
    return kind(a) == kind(b) && sameValue(a, b);
  }

  /** A number or boolean's numeric value, or null for any other value. */
  private static BigDecimal numeric(JsonNode node) {
    if (node.isBoolean()) {
      return node.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    if (node.isIntegralNumber()) {
      return new BigDecimal(node.bigIntegerValue());
    }
    if (node.isNumber()) {
      double value = node.doubleValue();
      // An infinite decimal equals nothing but itself here; no observation holds one.
      return Double.isFinite(value) ? new BigDecimal(value) : null;
    }
    return null;
  }

  private static boolean isInteger(JsonNode node, BigInteger value) {
    BigDecimal numeric = numeric(node);
    return numeric != null && numeric.compareTo(new BigDecimal(value)) == 0;
  }

  /**
   * Checks one side on its own and answers its observations.
   *
   * @param side how the side is named in an error
   */
  private static List<JsonNode> read(String side, JsonNode manifest, byte[] trace)
      throws InvalidRunException {
    if (manifest.has("status") && !"completed".equals(manifest.get("status").asText())) {
      throw new InvalidRunException(side + " did not complete");
    }
    if (!manifest.path("trace_sha256").isTextual()
        || !sha256(trace).equals(manifest.get("trace_sha256").textValue())) {
      throw new InvalidRunException(side + ": the trace digest does not match");
    }
    List<JsonNode> rows = new ArrayList<>();
    for (byte[] line : lines(trace)) {
      rows.add(parse(side, line));
    }
    boolean terminal = SmokeSchema.V2.id().equals(manifest.path("schema").asText(null));
    JsonNode ticks = manifest.get("ticks");
    JsonNode executed = terminal ? manifest.get("executed_ticks") : ticks;
    if (ticks == null || executed == null) {
      throw new InvalidRunException(side + ": no horizon");
    }
    if (!executed.isIntegralNumber()
        || numeric(ticks) == null
        || executed.bigIntegerValue().signum() < 0
        || new BigDecimal(executed.bigIntegerValue()).compareTo(numeric(ticks)) > 0) {
      throw new InvalidRunException(side + ": an invalid executed horizon");
    }
    JsonNode observations = manifest.get("observations");
    if (observations == null
        || !isInteger(observations, BigInteger.valueOf(rows.size()))
        || !executed
            .bigIntegerValue()
            .add(BigInteger.ONE)
            .equals(BigInteger.valueOf(rows.size()))) {
      throw new InvalidRunException(side + ": a truncated observation stream");
    }
    for (int i = 0; i < rows.size(); i++) {
      JsonNode row = rows.get(i);
      JsonNode tick = row.isObject() ? row.get("tick") : null;
      if (tick == null || !isInteger(tick, BigInteger.valueOf(i))) {
        throw new InvalidRunException(side + ": non-contiguous observation ticks");
      }
    }
    if (terminal) {
      for (int i = 0; i < rows.size(); i++) {
        JsonNode stopped = rows.get(i).get("stopped");
        if (stopped == null
            || !stopped.isBoolean()
            || (i < rows.size() - 1 && stopped.asBoolean())) {
          throw new InvalidRunException(
              side + ": a missing stop predicate or observations after the stop");
        }
      }
      boolean stoppedLast = rows.get(rows.size() - 1).get("stopped").asBoolean();
      ObjectNode expected = JSON.objectNode();
      expected.put("reason", stoppedLast ? "battle_stopped" : "horizon");
      expected.set("tick", executed);
      JsonNode termination = manifest.get("termination");
      if (termination == null
          || !looselyEqual(termination, expected)
          || (!stoppedLast && !looselyEqual(executed, ticks))) {
        throw new InvalidRunException(
            side + ": an unexplained truncation or an inconsistent termination");
      }
    }
    return rows;
  }

  /** The lines of a trace, split at LF, CRLF or CR; a final line break ends the last line. */
  static List<byte[]> lines(byte[] trace) {
    List<byte[]> lines = new ArrayList<>();
    int start = 0;
    int i = 0;
    while (i < trace.length) {
      byte b = trace[i];
      if (b == '\n' || b == '\r') {
        lines.add(Arrays.copyOfRange(trace, start, i));
        i += (b == '\r' && i + 1 < trace.length && trace[i + 1] == '\n') ? 2 : 1;
        start = i;
      } else {
        i++;
      }
    }
    if (start < trace.length) {
      lines.add(Arrays.copyOfRange(trace, start, trace.length));
    }
    return lines;
  }

  private static JsonNode parse(String side, byte[] line) throws InvalidRunException {
    try {
      // The bytes must be UTF-8 text before they are JSON.
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(line))
              .toString();
      JsonNode node = MAPPER.readTree(text);
      if (node == null || node.isMissingNode()) {
        throw new InvalidRunException(side + ": an empty observation line");
      }
      return node;
    } catch (CharacterCodingException | JsonProcessingException e) {
      throw new InvalidRunException(side + ": an observation line is not JSON");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
