/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * <p>Both sides are written the same way (one line per observation, keys sorted, no spaces), so an
 * agreeing observation is usually the same bytes on both sides: such a line is read once and is
 * equal without a field walk, and no observation is kept as a value once its checks have what they
 * need. The outcome is the one a walk of every value would give.
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
    Lines reference = new Lines(referenceTrace);
    Lines run = new Lines(runTrace);
    int common = Math.min(reference.count(), run.count());
    // A reference line of the same bytes as the run's line of the same number reads as the same
    // value: it is read once, for both sides, and the two are equal without a field walk.
    boolean[] same = new boolean[common];
    for (int i = 0; i < common; i++) {
      same[i] = reference.sameBytes(i, run, i);
    }
    Row[] referenceRows = new Row[reference.count()];
    Row[] runRows = new Row[run.count()];
    // The first difference of the observations, found before either side is checked, so no line
    // is kept as a value: the walk stops at the first difference, or at a line that does not read
    // (the checks below then answer invalid, as they would have from that line).
    int divergent = -1;
    ObjectNode delta = null;
    for (int i = 0; i < common && delta == null; i++) {
      if (same[i]) {
        continue;
      }
      JsonNode left = reference.tree(i);
      JsonNode right = run.tree(i);
      referenceRows[i] = Row.of(left);
      runRows[i] = Row.of(right);
      if (referenceRows[i].failure() != null || runRows[i].failure() != null) {
        break;
      }
      delta = firstDifference(left, right, "$");
      if (delta != null) {
        divergent = i;
      }
    }
    try {
      read(
          "the reference",
          referenceManifest,
          referenceTrace,
          reference,
          referenceRows,
          i -> {
            if (i < common && same[i]) {
              if (runRows[i] == null) {
                runRows[i] = Row.of(run.tree(i));
              }
              return runRows[i];
            }
            return Row.of(reference.tree(i));
          });
      read("the run", runManifest, runTrace, run, runRows, i -> Row.of(run.tree(i)));
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
    if (delta != null) {
      return Result.mismatch(divergent, Math.max(0, divergent - 1), delta);
    }
    if (reference.count() != run.count()) {
      ObjectNode length = JSON.objectNode();
      length.put("path", "$.observations.length");
      length.put("reference", reference.count());
      length.put("java", run.count());
      return Result.mismatch(common, common - 1, length);
    }
    return new Result(DIAGNOSTIC_MATCH, -1, -1, null, reference.count() - 1, null);
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
   * What the checks of one side need of an observation line: the value it reads as, or why it does
   * not read; the line's value itself is not kept.
   *
   * @param failure why the line is not an observation (an empty line, or not JSON), else null
   * @param tick the value's {@code tick} field when the value is an object, else null
   * @param stopped the value's {@code stopped} field, or null
   */
  private record Row(String failure, JsonNode tick, JsonNode stopped) {

    private static final Row EMPTY = new Row("an empty observation line", null, null);
    private static final Row NOT_JSON = new Row("an observation line is not JSON", null, null);

    /** The row of a line's value; a null value is a line that is not JSON. */
    static Row of(JsonNode value) {
      if (value == null) {
        return NOT_JSON;
      }
      if (value.isMissingNode()) {
        return EMPTY;
      }
      return new Row(null, value.isObject() ? value.get("tick") : null, value.get("stopped"));
    }
  }

  /** A side's observation line by its number: its row, read when first asked for. */
  @FunctionalInterface
  private interface LineReader {
    Row row(int line);
  }

  /**
   * Checks one side on its own.
   *
   * @param side how the side is named in an error
   * @param rows the rows already read, by line, null where none is; filled in here
   * @param reader reads a line not read yet
   */
  private static void read(
      String side, JsonNode manifest, byte[] trace, Lines lines, Row[] rows, LineReader reader)
      throws InvalidRunException {
    if (manifest.has("status") && !"completed".equals(manifest.get("status").asText())) {
      throw new InvalidRunException(side + " did not complete");
    }
    if (!manifest.path("trace_sha256").isTextual()
        || !sha256(trace).equals(manifest.get("trace_sha256").textValue())) {
      throw new InvalidRunException(side + ": the trace digest does not match");
    }
    int count = lines.count();
    for (int i = 0; i < count; i++) {
      if (rows[i] == null) {
        rows[i] = reader.row(i);
      }
      if (rows[i].failure() != null) {
        throw new InvalidRunException(side + ": " + rows[i].failure());
      }
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
        || !isInteger(observations, BigInteger.valueOf(count))
        || !executed.bigIntegerValue().add(BigInteger.ONE).equals(BigInteger.valueOf(count))) {
      throw new InvalidRunException(side + ": a truncated observation stream");
    }
    for (int i = 0; i < count; i++) {
      JsonNode tick = rows[i].tick();
      if (tick == null || !isInteger(tick, BigInteger.valueOf(i))) {
        throw new InvalidRunException(side + ": non-contiguous observation ticks");
      }
    }
    if (terminal) {
      for (int i = 0; i < count; i++) {
        JsonNode stopped = rows[i].stopped();
        if (stopped == null || !stopped.isBoolean() || (i < count - 1 && stopped.asBoolean())) {
          throw new InvalidRunException(
              side + ": a missing stop predicate or observations after the stop");
        }
      }
      boolean stoppedLast = rows[count - 1].stopped().asBoolean();
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
  }

  /**
   * The lines of a trace, split at LF, CRLF or CR; a final line break ends the last line. A line is
   * kept as where it starts and ends in the trace, not copied.
   */
  static final class Lines {
    private final byte[] trace;
    private int[] starts = new int[256];
    private int[] ends = new int[256];
    private int count;

    Lines(byte[] trace) {
      this.trace = trace;
      int start = 0;
      int i = 0;
      while (i < trace.length) {
        byte b = trace[i];
        if (b == '\n' || b == '\r') {
          add(start, i);
          i += (b == '\r' && i + 1 < trace.length && trace[i + 1] == '\n') ? 2 : 1;
          start = i;
        } else {
          i++;
        }
      }
      if (start < trace.length) {
        add(start, trace.length);
      }
    }

    private void add(int start, int end) {
      if (count == starts.length) {
        starts = Arrays.copyOf(starts, count * 2);
        ends = Arrays.copyOf(ends, count * 2);
      }
      starts[count] = start;
      ends[count] = end;
      count++;
    }

    /** How many lines. */
    int count() {
      return count;
    }

    /** Whether line {@code i} has the same bytes as line {@code j} of another trace. */
    boolean sameBytes(int i, Lines other, int j) {
      return Arrays.equals(trace, starts[i], ends[i], other.trace, other.starts[j], other.ends[j]);
    }

    /**
     * A line's value: the missing node for an empty line, null for a line that is not UTF-8 text or
     * not one JSON value.
     */
    JsonNode tree(int i) {
      try {
        // The bytes must be UTF-8 text before they are JSON.
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(trace, starts[i], ends[i] - starts[i]))
                .toString();
        JsonNode node = MAPPER.readTree(text);
        return node == null ? MissingNode.getInstance() : node;
      } catch (CharacterCodingException | JsonProcessingException e) {
        return null;
      }
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
