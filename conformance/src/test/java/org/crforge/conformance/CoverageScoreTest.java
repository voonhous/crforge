/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.conformance.CoverageScore.Battle;
import org.crforge.conformance.ReferenceSuite.CaseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageScoreTest {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  @TempDir Path folder;

  private static Battle match(int ticks) {
    return battle(outcome(ReferenceComparison.DIAGNOSTIC_MATCH), ticks);
  }

  private static Battle mismatch(int ticks, int firstDivergentTick, String path) {
    ObjectNode outcome = outcome(ReferenceComparison.MISMATCH);
    outcome.put("first_divergent_tick", firstDivergentTick);
    outcome.put("path", path);
    return battle(outcome, ticks);
  }

  private static Battle unsupported(int ticks, String feature) {
    ObjectNode outcome = outcome("unsupported");
    outcome.put("feature", feature);
    outcome.put("input", "cmd[3]");
    return battle(outcome, ticks);
  }

  private static ObjectNode outcome(String name) {
    ObjectNode outcome = JSON.objectNode();
    outcome.put("outcome", name);
    return outcome;
  }

  private static Battle battle(ObjectNode outcome, int ticks) {
    return new Battle(new CaseResult("coverage/battle", outcome, null, 1), ticks);
  }

  @Test
  void scoreSumsTheOutcomesAndTheTicksAgreed() {
    List<Battle> battles =
        List.of(
            match(1000),
            match(3000),
            mismatch(2000, 500, "$.entities[12].hp"),
            unsupported(4000, "the game mode X"));

    ObjectNode score = CoverageScore.score("16.402.21", battles);

    assertThat(score.path("content_version").asText()).isEqualTo("16.402.21");
    assertThat(score.path("battles").asInt()).isEqualTo(4);
    assertThat(score.path("to_the_end").decimalValue()).isEqualByComparingTo("50.0");
    // 1000 + 3000 + 500 of 10000 recorded ticks.
    assertThat(score.path("ticks_agreed").decimalValue()).isEqualByComparingTo("45.0");
    assertThat(score.path("outcomes").path("diagnostic_match").asInt()).isEqualTo(2);
    assertThat(score.path("outcomes").path("mismatch").asInt()).isEqualTo(1);
    assertThat(score.path("outcomes").path("unsupported").asInt()).isEqualTo(1);
  }

  @Test
  void firstFailuresAreCountedByFeatureAndByFieldWithoutIndices() {
    List<Battle> battles = new ArrayList<>();
    battles.add(mismatch(100, 10, "$.entities[3].hp"));
    battles.add(mismatch(100, 20, "$.entities[17].hp"));
    battles.add(mismatch(100, 30, "$.sides[1].elixir"));
    battles.add(unsupported(100, "the game mode X"));
    battles.add(unsupported(100, "the game mode X"));
    battles.add(unsupported(100, "the game mode X"));
    battles.add(match(100));

    ObjectNode first = (ObjectNode) CoverageScore.score("v", battles).path("first_failures");

    assertThat(first.fieldNames())
        .toIterable()
        .containsExactly(
            "unsupported: the game mode X",
            "mismatch $.entities[].hp",
            "mismatch $.sides[].elixir");
    assertThat(first.path("unsupported: the game mode X").asInt()).isEqualTo(3);
    assertThat(first.path("mismatch $.entities[].hp").asInt()).isEqualTo(2);
  }

  @Test
  void aRefusalCountsByItsKindNotTheBattlesNames() {
    assertThat(
            CoverageScore.kind(
                "Deflect_3000006 deflects proj_4000205 without a root owner, which is not modelled"))
        .isEqualTo("Deflect_# deflects proj_# without a root owner, which is not modelled");
    assertThat(CoverageScore.kind("cmd24_0_clone0 would be a clone of a champion"))
        .isEqualTo("cmd#_clone# would be a clone of a champion");
    assertThat(CoverageScore.kind("GoblinDrill_EV1_relocate looks for a ring"))
        .isEqualTo("GoblinDrill_EV1_relocate looks for a ring");

    ObjectNode first =
        (ObjectNode)
            CoverageScore.score(
                    "v",
                    List.of(
                        unsupported(100, "Deflect_3000001 deflects proj_4000042"),
                        unsupported(100, "Deflect_3000011 deflects proj_4000312")))
                .path("first_failures");
    assertThat(first.path("unsupported: Deflect_# deflects proj_#").asInt()).isEqualTo(2);
  }

  @Test
  void firstFailuresKeepTheMostFrequentOnly() {
    List<Battle> battles = new ArrayList<>();
    for (int i = 0; i < CoverageScore.FIRST_FAILURES + 5; i++) {
      battles.add(unsupported(100, "feature " + i));
    }

    assertThat(CoverageScore.score("v", battles).path("first_failures").size())
        .isEqualTo(CoverageScore.FIRST_FAILURES);
  }

  @Test
  void aMismatchAgreesOnTheTicksBeforeItsFirstDivergenceOnly() {
    assertThat(mismatch(2000, 700, "$.tick").agreedTicks()).isEqualTo(700);
    assertThat(match(2000).agreedTicks()).isEqualTo(2000);
    assertThat(unsupported(2000, "f").agreedTicks()).isZero();
    assertThat(battle(outcome(ReferenceComparison.INVALID), 2000).agreedTicks()).isZero();
  }

  @Test
  void percentRoundsDownToOneDecimal() {
    assertThat(CoverageScore.percent(2, 3)).isEqualByComparingTo(new BigDecimal("66.6"));
    assertThat(CoverageScore.percent(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(CoverageScore.percent(5, 5)).isEqualByComparingTo(new BigDecimal("100.0"));
  }

  @Test
  void badgeNamesTheVersionAndTheShareToTheEnd() {
    ObjectNode badge =
        CoverageScore.badge(CoverageScore.score("16.402.19", List.of(match(10), match(10))));

    assertThat(badge.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(badge.path("label").asText()).isEqualTo("16.402.19 battles reproduced");
    assertThat(badge.path("message").asText()).isEqualTo("100.0%");
    assertThat(badge.path("color").asText()).isEqualTo("brightgreen");
    assertThat(CoverageScore.color(74.9)).isEqualTo("yellow");
    assertThat(CoverageScore.color(39.9)).isEqualTo("red");
  }

  @Test
  void talliesOfPartsMergeToTheScoreOfTheWholeSet() {
    List<Battle> first =
        List.of(match(1000), mismatch(2000, 500, "$.entities[3].hp"), unsupported(700, "f"));
    List<Battle> second =
        List.of(
            match(3000), mismatch(900, 100, "$.entities[9].hp"), match(10), unsupported(5, "g"));
    List<Battle> all = new ArrayList<>(first);
    all.addAll(second);

    ObjectNode merged =
        CoverageScore.merge(
            List.of(CoverageScore.tally("v", first), CoverageScore.tally("v", second)));

    assertThat(CoverageScore.score(merged)).isEqualTo(CoverageScore.score("v", all));
    assertThat(merged.path("failures").path("mismatch $.entities[].hp").asInt()).isEqualTo(2);
  }

  @Test
  void mergeRefusesTalliesOfTwoVersionsAndNoTally() {
    List<ObjectNode> mixed =
        List.of(CoverageScore.tally("a", List.of(match(1))), CoverageScore.tally("b", List.of()));

    assertThatThrownBy(() -> CoverageScore.merge(mixed))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CoverageScore.merge(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPartIsTheBattlesAfterThoseOfTheEarlierParts() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode listing = mapper.createObjectNode();
    listing.put("corpus", "coverage-v");
    listing.put("content_version", "v");
    listing.put("content_sha", "s");
    listing
        .putArray("parts")
        .add(mapper.createObjectNode().put("battles", 2))
        .add(mapper.createObjectNode().put("battles", 1));
    for (int i = 0; i < 3; i++) {
      listing
          .withArray("cases")
          .add(
              mapper
                  .createObjectNode()
                  .put("id", "battle_" + i)
                  .put("reference", "battles/battle_" + i)
                  .put("scenario_sha256", "x")
                  .put("ticks", 7000));
    }
    Files.createDirectories(folder.resolve("corpora"));
    mapper.writeValue(folder.resolve("corpora/coverage-v.json").toFile(), listing);
    // Only part 1 is unpacked, as in a CI job that downloaded that part alone.
    Files.createDirectories(folder.resolve("battles/battle_2"));
    ReferenceSuite.References references = ReferenceSuite.load(folder);

    assertThat(CoverageScore.part(folder, references, 1))
        .extracting(ReferenceSuite.Case::id)
        .containsExactly("battle_2");
    assertThatThrownBy(() -> CoverageScore.part(folder, references, 0))
        .hasMessageContaining("not unpacked");
    assertThatThrownBy(() -> CoverageScore.part(folder, references, 2))
        .hasMessageContaining("no part 2");
  }

  @Test
  void executedTicksReadsTheReference() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Path terminal = Files.createDirectories(folder.resolve("a"));
    Files.writeString(
        terminal.resolve("reference.json"), "{\"ticks\": 7000, \"executed_ticks\": 3678}");
    Path horizon = Files.createDirectories(folder.resolve("b"));
    Files.writeString(
        horizon.resolve("reference.json"),
        mapper.writeValueAsString(mapper.createObjectNode().put("ticks", 2400)));

    assertThat(CoverageScore.executedTicks(terminal)).isEqualTo(3678);
    assertThat(CoverageScore.executedTicks(horizon)).isEqualTo(2400);
  }
}
