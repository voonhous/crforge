/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceComparisonTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCENARIO = "a1b2";

  /** A terminal-aware trace of {@code steps} steps, stopped on its last observation. */
  private static List<ObjectNode> rows(int steps) {
    List<ObjectNode> rows = new ArrayList<>();
    for (int tick = 0; tick <= steps; tick++) {
      ObjectNode row = MAPPER.createObjectNode();
      row.put("tick", tick);
      row.put("rng", 1000 + tick);
      row.putArray("entities").addObject().put("id", 1).put("x", 9500).put("y", 4000 + tick);
      row.put("stopped", tick == steps);
      rows.add(row);
    }
    return rows;
  }

  private static byte[] trace(List<ObjectNode> rows) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (JsonNode row : rows) {
      out.write(MAPPER.writeValueAsBytes(row));
      out.write('\n');
    }
    return out.toByteArray();
  }

  /** The manifest of a terminal-aware trace that stopped on its own before a horizon of 10. */
  private static ObjectNode manifest(byte[] trace, int steps) {
    ObjectNode manifest = MAPPER.createObjectNode();
    manifest.put("schema", SmokeSchema.V2.id());
    manifest.put("ticks", 10);
    manifest.put("executed_ticks", steps);
    manifest.putObject("termination").put("reason", "battle_stopped").put("tick", steps);
    manifest.put("observations", steps + 1);
    manifest.put("trace_sha256", sha256(trace));
    manifest.put("scenario_sha256", SCENARIO);
    return manifest;
  }

  private static ReferenceComparison.Result compareWith(List<ObjectNode> run) throws IOException {
    byte[] reference = trace(rows(5));
    byte[] java = trace(run);
    return ReferenceComparison.compare(
        manifest(reference, 5), reference, manifest(java, run.size() - 1), java);
  }

  @Test
  void anEqualTraceIsADiagnosticMatchOverEveryStep() throws IOException {
    ReferenceComparison.Result result = compareWith(rows(5));

    assertThat(result.outcome()).isEqualTo(ReferenceComparison.DIAGNOSTIC_MATCH);
    assertThat(result.comparedSteps()).isEqualTo(5);
  }

  @Test
  void aChangedValueIsAMismatchAtItsObservationWithItsPathAndBothValues() throws IOException {
    List<ObjectNode> run = rows(5);
    ((ObjectNode) run.get(3).path("entities").get(0)).put("y", 1);

    ReferenceComparison.Result result = compareWith(run);

    assertThat(result.outcome()).isEqualTo(ReferenceComparison.MISMATCH);
    assertThat(result.firstDivergentTick()).isEqualTo(3);
    assertThat(result.equalPrefixSteps()).isEqualTo(2);
    assertThat(result.difference().path("path").asText()).isEqualTo("$.entities[0].y");
    assertThat(result.difference().path("reference").asInt()).isEqualTo(4003);
    assertThat(result.difference().path("java").asInt()).isEqualTo(1);
  }

  @Test
  void anIntegerIsNotEqualToTheSameNumberAsADecimal() throws IOException {
    List<ObjectNode> run = rows(5);
    run.get(2).put("rng", 1002.0);

    ReferenceComparison.Result result = compareWith(run);

    assertThat(result.outcome()).isEqualTo(ReferenceComparison.MISMATCH);
    assertThat(result.difference().path("path").asText()).isEqualTo("$.rng");
  }

  @Test
  void aMissingFieldAndAShorterListAreMismatches() throws IOException {
    List<ObjectNode> missing = rows(5);
    missing.get(1).remove("rng");
    List<ObjectNode> shorter = rows(5);
    ((ArrayNode) shorter.get(4).path("entities")).removeAll();

    ReferenceComparison.Result field = compareWith(missing);
    ReferenceComparison.Result list = compareWith(shorter);

    assertThat(field.difference().path("missing_fields").get(0).asText()).isEqualTo("rng");
    assertThat(field.firstDivergentTick()).isEqualTo(1);
    assertThat(list.difference().path("path").asText()).isEqualTo("$.entities.length");
    assertThat(list.firstDivergentTick()).isEqualTo(4);
  }

  @Test
  void aMissingOrAnExtraObservationIsInvalid() throws IOException {
    List<ObjectNode> missing = rows(5);
    missing.remove(2);
    byte[] reference = trace(rows(5));
    byte[] gap = trace(missing);
    List<ObjectNode> extra = rows(5);
    extra.add(rows(6).get(6));
    byte[] longer = trace(extra);

    assertThat(
            ReferenceComparison.compare(manifest(reference, 5), reference, manifest(gap, 4), gap)
                .error())
        .contains("non-contiguous");
    assertThat(
            ReferenceComparison.compare(
                    manifest(reference, 5), reference, manifest(longer, 5), longer)
                .error())
        .contains("truncated");
  }

  @Test
  void aStopBeforeTheLastObservationOrAnInconsistentTerminationIsInvalid() throws IOException {
    List<ObjectNode> early = rows(5);
    early.get(2).put("stopped", true);
    byte[] reference = trace(rows(5));
    byte[] stopped = trace(early);
    byte[] java = trace(rows(5));
    ObjectNode horizon = manifest(java, 5);
    horizon.putObject("termination").put("reason", "horizon").put("tick", 5);

    assertThat(
            ReferenceComparison.compare(
                    manifest(reference, 5), reference, manifest(stopped, 5), stopped)
                .outcome())
        .isEqualTo(ReferenceComparison.INVALID);
    assertThat(
            ReferenceComparison.compare(manifest(reference, 5), reference, horizon, java).error())
        .contains("termination");
  }

  @Test
  void aStaleDigestOrAnotherScenarioIsInvalid() throws IOException {
    byte[] reference = trace(rows(5));
    byte[] java = trace(rows(5));
    ObjectNode stale = manifest(java, 5);
    stale.put("trace_sha256", sha256(reference).replace('a', 'b').replace('0', '1'));
    ObjectNode other = manifest(java, 5);
    other.put("scenario_sha256", "ffff");

    assertThat(ReferenceComparison.compare(manifest(reference, 5), reference, stale, java).error())
        .contains("digest");
    assertThat(ReferenceComparison.compare(manifest(reference, 5), reference, other, java).error())
        .isEqualTo("identity mismatch: scenario_sha256");
  }

  @Test
  void aStaleReferenceDigestIsInvalidWhenTheTracesAreTheSameBytes() throws IOException {
    byte[] reference = trace(rows(5));
    byte[] java = trace(rows(5));
    ObjectNode stale = manifest(reference, 5);
    stale.put("trace_sha256", sha256(reference).replace('a', 'b').replace('0', '1'));

    // The reference is checked against the run's digest, the digest of the same bytes.
    ReferenceComparison.Result result =
        ReferenceComparison.compare(stale, reference, manifest(java, 5), java, sha256(java));

    assertThat(result.outcome()).isEqualTo(ReferenceComparison.INVALID);
    assertThat(result.error()).isEqualTo("the reference: the trace digest does not match");
  }

  @Test
  void aKnownRunDigestGivesTheOutcomeOfAComputedOne() throws IOException {
    byte[] reference = trace(rows(5));
    List<ObjectNode> changed = rows(5);
    changed.get(3).put("rng", 1);
    ObjectNode stale = manifest(trace(rows(5)), 5);
    stale.put("trace_sha256", sha256(reference).replace('a', 'b').replace('0', '1'));
    for (byte[] java : List.of(trace(rows(5)), trace(changed), trace(rows(4)))) {
      for (ObjectNode runManifest : List.of(manifest(java, count(java) - 1), stale)) {
        ReferenceComparison.Result computed =
            ReferenceComparison.compare(manifest(reference, 5), reference, runManifest, java);
        ReferenceComparison.Result known =
            ReferenceComparison.compare(
                manifest(reference, 5), reference, runManifest, java, sha256(java));

        assertThat(known).isEqualTo(computed);
      }
    }
  }

  /** How many lines a trace has. */
  private static int count(byte[] trace) {
    return new String(trace, StandardCharsets.UTF_8).split("\n").length;
  }

  @Test
  void aLineReadForItsTickAndStopGivesTheRowOfItsWholeValue() throws IOException {
    String digits = "1".repeat(1001);
    List<String> lines =
        new ArrayList<>(
            List.of(
                "{\"tick\":0,\"stopped\":false}",
                "{\"stopped\":true,\"tick\":3,\"x\":[1,{\"a\":\"b\"}],\"y\":{\"z\":[]}}",
                "",
                "   ",
                "\t",
                "null",
                "5",
                "\"s\"",
                "[1,2]",
                "[]",
                "{}",
                "true",
                "[{\"tick\":1,\"stopped\":true}]",
                "{\"a\":{\"tick\":1,\"stopped\":true}}",
                // something after the value
                "{} {}",
                "{}x",
                "{\"tick\":1} 2",
                "5 5",
                "{\"tick\":1},",
                "[1] ]",
                // repeated names: the last is kept
                "{\"tick\":1,\"tick\":2}",
                "{\"tick\":1,\"tick\":{\"a\":1}}",
                "{\"tick\":{\"a\":1},\"tick\":7}",
                "{\"stopped\":true,\"stopped\":false}",
                // a tick or a stop of every kind
                "{\"tick\":true,\"stopped\":1}",
                "{\"tick\":1.0,\"stopped\":\"true\"}",
                "{\"tick\":\"1\",\"stopped\":null}",
                "{\"tick\":null,\"stopped\":[true]}",
                "{\"tick\":[1],\"stopped\":{}}",
                "{\"tick\":12345678901234567890123}",
                "{\"tick\":9223372036854775807}",
                "{\"tick\":1e400}",
                "{\"tick\":-0}",
                "{\"tick\":-0.0}",
                "{\"tick\":" + digits + "}",
                // lines that are not JSON, the fault outside the tick and the stop
                "{\"a\":\"\\q\",\"tick\":0}",
                "{\"a\":[\"\\u00zz\"],\"tick\":0}",
                "{\"tick\":0,\"a\":\"x",
                "{\"tick\":0,\"a\":1 \"b\":2}",
                "{\"tick\":0,\"a\":1,}",
                "{\"tick\":0,\"a\":[1,]}",
                "{/*c*/\"tick\":0}",
                "{\"tick\":0,\"a\":NaN}",
                "{\"tick\":0,\"a\":[01]}",
                "{tick:0}",
                "{'tick':0}",
                "{\"tick\":0,\"a\":{\"b\":[1,2,{\"c\":}]}}",
                "{\"tick\":0,\"a\":[1,2}",
                "{\"tick\":0,\"a\":{\"b\":1]}",
                "]",
                "}",
                "{\"tick\":0",
                "{\"tick\":",
                "{\"tick\"",
                "[",
                "{\"tick\":0,\"a\":\"tab\tinside\"}",
                "{\"tick\":0,\"a\":[\"control" + (char) 1 + "\"]}",
                "{\"tick\":0,\"a\":[" + digits + "]}",
                "{\"tick\":0,\"a\":{\"b\":0." + digits + "}}",
                "{\"tick\":0,\"a\":{\"" + "n".repeat(60_000) + "\":1}}",
                "{\"tick\":0,\"a\":" + "[".repeat(1500) + "]".repeat(1500) + "}",
                "{\"tick\":0,\"a\":\"\\ud800\"}",
                (char) 0xfeff + "{\"tick\":0}"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String line : lines) {
      out.write(line.getBytes(StandardCharsets.UTF_8));
      out.write('\n');
    }
    // bytes that are not UTF-8 text
    out.write(new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xff, '"', '}', '\n'});
    ReferenceComparison.Lines read = new ReferenceComparison.Lines(out.toByteArray());

    assertThat(read.count()).isEqualTo(lines.size() + 1);
    for (int i = 0; i < read.count(); i++) {
      assertThat(read.row(i)).as("line %d", i).isEqualTo(ReferenceComparison.Row.of(read.tree(i)));
    }
  }

  @Test
  void lineBreaksOfAnotherStyleSplitTheSameObservations() throws IOException {
    byte[] reference = trace(rows(5));
    byte[] crlf =
        new String(trace(rows(5)), StandardCharsets.UTF_8)
            .replace("\n", "\r\n")
            .getBytes(StandardCharsets.UTF_8);

    assertThat(
            ReferenceComparison.compare(manifest(reference, 5), reference, manifest(crlf, 5), crlf)
                .matches())
        .isTrue();
  }

  @Test
  void aTraceWrittenInAnotherKeyOrderMatchesAndStillFindsItsFirstDifference() throws IOException {
    byte[] reference = trace(rows(5));
    List<ObjectNode> run = rows(5);
    ((ObjectNode) run.get(4).path("entities").get(0)).put("x", 1);
    // The same values, each object's keys reversed: no line has the reference's bytes.
    byte[] reordered = trace(run.stream().map(ReferenceComparisonTest::reversed).toList());
    byte[] equal = trace(rows(5).stream().map(ReferenceComparisonTest::reversed).toList());

    ReferenceComparison.Result match =
        ReferenceComparison.compare(manifest(reference, 5), reference, manifest(equal, 5), equal);
    ReferenceComparison.Result mismatch =
        ReferenceComparison.compare(
            manifest(reference, 5), reference, manifest(reordered, 5), reordered);

    assertThat(match.outcome()).isEqualTo(ReferenceComparison.DIAGNOSTIC_MATCH);
    assertThat(mismatch.firstDivergentTick()).isEqualTo(4);
    assertThat(mismatch.difference().path("path").asText()).isEqualTo("$.entities[0].x");
  }

  @Test
  void aBrokenLineAfterTheFirstDifferenceStillMakesTheComparisonInvalid() throws IOException {
    List<ObjectNode> run = rows(5);
    run.get(1).put("rng", 1);
    byte[] java = trace(run);
    byte[] broken = brokenAt(trace(rows(5)), 4);

    ReferenceComparison.Result result =
        ReferenceComparison.compare(manifest(broken, 5), broken, manifest(java, 5), java);

    assertThat(result.outcome()).isEqualTo(ReferenceComparison.INVALID);
    assertThat(result.error()).isEqualTo("the reference: an observation line is not JSON");
  }

  @Test
  void aBrokenLineBothSidesShareIsTheReferencesErrorFirst() throws IOException {
    byte[] reference = brokenAt(trace(rows(5)), 2);
    byte[] java = brokenAt(trace(rows(5)), 2);

    ReferenceComparison.Result result =
        ReferenceComparison.compare(manifest(reference, 5), reference, manifest(java, 5), java);

    assertThat(result.error()).isEqualTo("the reference: an observation line is not JSON");
  }

  /** A trace whose line {@code index} has lost its last character, so it is not JSON. */
  private static byte[] brokenAt(byte[] trace, int index) {
    List<String> lines =
        new ArrayList<>(List.of(new String(trace, StandardCharsets.UTF_8).split("\n")));
    String line = lines.get(index);
    lines.set(index, line.substring(0, line.length() - 1));
    return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** A copy of an object with its keys, and those of every object in it, in reverse order. */
  private static ObjectNode reversed(ObjectNode row) {
    ObjectNode copy = MAPPER.createObjectNode();
    List<String> names = new ArrayList<>();
    row.fieldNames().forEachRemaining(names::add);
    for (int i = names.size() - 1; i >= 0; i--) {
      JsonNode value = row.get(names.get(i));
      if (value.isArray()) {
        ArrayNode list = copy.putArray(names.get(i));
        value.forEach(item -> list.add(item.isObject() ? reversed((ObjectNode) item) : item));
      } else {
        copy.set(names.get(i), value);
      }
    }
    return copy;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
