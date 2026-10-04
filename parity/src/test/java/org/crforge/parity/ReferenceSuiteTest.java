package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.crforge.core.battle.data.GameTables;
import org.crforge.parity.ReferenceSuite.CaseResult;
import org.crforge.parity.ReferenceSuite.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceSuiteTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path folder;

  private static ObjectNode outcome(String name) {
    return MAPPER.createObjectNode().put("outcome", name);
  }

  private static ObjectNode unsupported(String feature) {
    return outcome("unsupported").put("feature", feature).put("input", "the run");
  }

  private static CaseResult result(String key, ObjectNode expectation) {
    return new CaseResult(key, expectation, null, 0);
  }

  @Test
  void aCaseThatNewlyMatchesOrWhoseRefusalTextChangesIsADeviation() {
    Map<String, JsonNode> expected =
        Map.of(
            "c/same", outcome("diagnostic_match"),
            "c/fixed", unsupported("Row sets columns the battle does not model: [A]"),
            "c/reworded", unsupported("Row sets columns the battle does not model: [A]"));
    List<CaseResult> results =
        List.of(
            result("c/same", outcome("diagnostic_match")),
            result("c/fixed", outcome("diagnostic_match")),
            result("c/reworded", unsupported("Row sets columns the battle does not model: [B]")),
            result("c/new", outcome("diagnostic_match")));

    List<String> deviations = ReferenceSuite.deviations(results, expected);

    assertThat(deviations).hasSize(3);
    assertThat(deviations.get(0)).startsWith("c/fixed: expected {\"outcome\": \"unsupported\"");
    assertThat(deviations.get(0)).endsWith("now {\"outcome\": \"diagnostic_match\"}");
    assertThat(deviations.get(1)).startsWith("c/reworded:").contains("[B]");
    assertThat(deviations.get(2)).startsWith("c/new: no expectation");
  }

  @Test
  void theExpectationsFileHasOneCaseALineInKeyOrderAndReadsBack() throws IOException {
    Path file = folder.resolve("9.9.9.json");
    List<CaseResult> results =
        List.of(
            result("b/two", unsupported("x")),
            result("a/one", outcome("diagnostic_match")),
            result("a/three", outcome("invalid").put("error", "e")));

    ReferenceSuite.writeExpectations(file, "9.9.9", results);

    assertThat(Files.readAllLines(file))
        .containsExactly(
            "{",
            " \"content_version\": \"9.9.9\",",
            " \"cases\": {",
            "  \"a/one\": {\"outcome\": \"diagnostic_match\"},",
            "  \"a/three\": {\"outcome\": \"invalid\", \"error\": \"e\"},",
            "  \"b/two\": {\"outcome\": \"unsupported\", \"feature\": \"x\", \"input\": \"the run\"}",
            " }",
            "}");
    assertThat(ReferenceSuite.deviations(results, ReferenceSuite.readExpectations(file))).isEmpty();
  }

  @Test
  void theShardsSplitTheCasesWithoutOverlap() {
    List<ReferenceSuite.Case> cases = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      cases.add(new ReferenceSuite.Case("c", "case" + i, folder, "", 1, null));
    }

    List<ReferenceSuite.Case> first = ReferenceSuite.shard(cases, 0, 2);
    List<ReferenceSuite.Case> second = ReferenceSuite.shard(cases, 1, 2);

    assertThat(first).hasSize(4);
    assertThat(second).hasSize(3);
    List<ReferenceSuite.Case> all = new ArrayList<>(first);
    all.addAll(second);
    assertThat(all).containsExactlyInAnyOrderElementsOf(cases);
  }

  @Test
  void aRecordedBattleIsRunInProcessAndComparedWithItsReference() throws IOException {
    GameTables tables = GameTables.load(GameTables.configuredDirectory().orElseThrow());
    byte[] scenario = MAPPER.writeValueAsBytes(Scenarios.knight());
    ReplaySmokeRun.InProcessRun recorded =
        ReplaySmokeRun.runInProcess(SmokeSchema.V1, 260, scenario, tables);
    // One battle recorded as given, one with its stream state changed on observation 230.
    writeReference("same", scenario, recorded.trace());
    List<String> lines =
        new ArrayList<>(List.of(new String(recorded.trace(), StandardCharsets.UTF_8).split("\n")));
    ObjectNode changed = (ObjectNode) MAPPER.readTree(lines.get(230));
    changed.put("rng", changed.path("rng").asLong() + 1);
    lines.set(230, MAPPER.writeValueAsString(changed));
    writeReference(
        "changed", scenario, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    ObjectNode corpus = MAPPER.createObjectNode();
    corpus.put("corpus", "knight");
    corpus.put("content_version", tables.version());
    corpus.put("content_sha", tables.contentSha());
    for (String name : List.of("same", "changed")) {
      corpus
          .withArray("cases")
          .addObject()
          .put("id", name)
          .put("reference", "battles/" + name)
          .put("scenario_sha256", sha256(scenario))
          .put("ticks", 260);
    }
    Files.createDirectories(folder.resolve("corpora"));
    MAPPER.writeValue(folder.resolve("corpora/knight.json").toFile(), corpus);

    References references = ReferenceSuite.load(folder);
    ReferenceSuite.checkContent(references, tables);
    List<CaseResult> results = ReferenceSuite.runAll(references.cases(), tables, 2);

    assertThat(results)
        .extracting(CaseResult::key)
        .containsExactly("knight/same", "knight/changed");
    assertThat(results.get(0).expectation()).isEqualTo(outcome("diagnostic_match"));
    assertThat(results.get(0).traceSha256()).isEqualTo(sha256(recorded.trace()));
    assertThat(results.get(1).expectation())
        .isEqualTo(outcome("mismatch").put("first_divergent_tick", 230).put("path", "$.rng"));
  }

  @Test
  void aCorpusOfGeneratedCasesNamesTheirShapeAndItsCasesAreReadAsSuch() throws IOException {
    GameTables tables = Version16Tables.load();
    byte[] scenario = MAPPER.writeValueAsBytes(Scenarios.generatedKnightOfVersion16());
    ReplaySmokeRun.InProcessRun recorded =
        ReplaySmokeRun.runInProcess(SmokeSchema.V1, 260, scenario, tables, ScenarioShape.GENERATED);
    writeReference("knight", scenario, recorded.trace());
    Files.createDirectories(folder.resolve("corpora"));
    // One listing names its cases generated, the other names no shape: its cases are replays.
    for (String name : List.of("generated", "replays")) {
      ObjectNode corpus = MAPPER.createObjectNode();
      corpus.put("corpus", name);
      corpus.put("content_version", tables.version());
      corpus.put("content_sha", tables.contentSha());
      if (name.equals("generated")) {
        corpus.put("scenario_shape", "generated");
      }
      corpus
          .withArray("cases")
          .addObject()
          .put("id", "knight")
          .put("reference", "battles/knight")
          .put("scenario_sha256", sha256(scenario))
          .put("ticks", 260);
      MAPPER.writeValue(folder.resolve("corpora/" + name + ".json").toFile(), corpus);
    }

    References references = ReferenceSuite.load(folder);
    List<CaseResult> results = ReferenceSuite.runAll(references.cases(), tables, 1);

    assertThat(references.cases())
        .extracting(ReferenceSuite.Case::shape)
        .containsExactly(ScenarioShape.GENERATED, ScenarioShape.REPLAY);
    assertThat(results.get(0).expectation()).isEqualTo(outcome("diagnostic_match"));
    assertThat(results.get(1).outcome()).isEqualTo("invalid");
    assertThat(results.get(1).expectation().path("error").asText())
        .contains("the scenario has no srq");
  }

  /** Writes a reference battle folder: its scenario, its minimal manifest and its packed trace. */
  private void writeReference(String name, byte[] scenario, byte[] trace) throws IOException {
    Path battle = Files.createDirectories(folder.resolve("battles").resolve(name));
    Files.write(battle.resolve("scenario.json"), scenario);
    try (OutputStream out =
        new GZIPOutputStream(Files.newOutputStream(battle.resolve("observations.jsonl.gz")))) {
      out.write(trace);
    }
    ObjectNode reference = MAPPER.createObjectNode();
    reference.put("schema", SmokeSchema.V1.id());
    reference.put("ticks", 260);
    reference.put("observations", 261);
    reference.put("trace_sha256", sha256(trace));
    reference.put("scenario_sha256", sha256(scenario));
    MAPPER.writeValue(battle.resolve("reference.json").toFile(), reference);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
