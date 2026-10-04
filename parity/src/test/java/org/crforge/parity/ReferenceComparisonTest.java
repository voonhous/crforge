package org.crforge.parity;

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

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
