/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.crforge.conformance.ReferenceSuite.CaseResult;
import org.crforge.conformance.ReferenceSuite.References;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every recorded reference battle of the configured references folder, run on the simulator and
 * held to the expected outcome in {@code reference-expectations/<version>.json}.
 *
 * <p>Run with {@code ./gradlew :conformance:referenceTest} and the references folder named by
 * {@code -Pcrforge.references=<dir>} (skipped without it); the build makes the game tables. {@code
 * -Pcrforge.references.shard} and {@code -Pcrforge.references.shards} run one shard of the cases.
 * Each run writes a scorecard of every case's outcome under {@code build/reference-scorecard}.
 */
@Tag("reference")
class ReferenceBattlesTest {

  @Test
  void everyReferenceBattleHasItsExpectedOutcome() throws IOException {
    Optional<Path> folder = ReferenceSuite.configuredDirectory();
    assumeTrue(
        folder.isPresent(), "no reference battles configured: set " + ReferenceSuite.PROPERTY);
    Optional<Path> tablesFolder = GameTables.configuredDirectory();
    assertThat(tablesFolder)
        .as("the reference battles need the game tables the build makes (crforge.assetSource)")
        .isPresent();

    References references = ReferenceSuite.load(folder.get());
    GameTables tables = GameTables.load(tablesFolder.get());
    ReferenceSuite.checkContent(references, tables);
    int index = setting("crforge.references.shard", 0);
    int count = setting("crforge.references.shards", 1);
    int threads = setting("crforge.references.threads", Runtime.getRuntime().availableProcessors());
    List<ReferenceSuite.Case> cases = ReferenceSuite.shard(references.cases(), index, count);

    long start = System.nanoTime();
    List<CaseResult> results = ReferenceSuite.runAll(cases, tables, threads);
    long seconds = (System.nanoTime() - start) / 1_000_000_000L;

    String shard = index + "/" + count;
    Path scorecard =
        Paths.get(System.getProperty("crforge.references.scorecard", "build/reference-scorecard"))
            .resolve("shard-" + index + "-of-" + count + ".json");
    ReferenceSuite.writeScorecard(scorecard, references, shard, results);
    Map<String, Integer> counts = new TreeMap<>();
    results.forEach(r -> counts.merge(r.outcome(), 1, Integer::sum));
    System.out.println(
        "reference battles, shard "
            + shard
            + ": "
            + results.size()
            + " cases in "
            + seconds
            + " s on "
            + threads
            + " threads: "
            + counts
            + "; scorecard "
            + scorecard);

    Path expectations =
        ReferenceSuite.expectationsFile(
            Paths.get(
                System.getProperty("crforge.references.expectations", "reference-expectations")),
            references.version());
    String update =
        "If the change is intended, run ./gradlew :conformance:updateReferenceExpectations"
            + " and commit "
            + expectations.getFileName()
            + " with it.";
    if (!Files.exists(expectations)) {
      fail(
          "no expectations for content "
              + references.version()
              + " at "
              + expectations
              + ". "
              + update);
    }
    Map<String, JsonNode> expected = ReferenceSuite.readExpectations(expectations);
    List<String> deviations = new ArrayList<>(ReferenceSuite.deviations(results, expected));
    if (index == 0) {
      // Every shard sees the whole listing; one of them reports expectations without a case.
      deviations.addAll(ReferenceSuite.staleExpectations(references, expected));
    }
    if (!deviations.isEmpty()) {
      fail(
          deviations.size()
              + " reference battle(s) differ from "
              + expectations
              + " (a newly matching case counts too). "
              + update
              + "\n  "
              + String.join("\n  ", deviations));
    }
  }

  /** An integer setting from a system property, or the default. */
  private static int setting(String property, int fallback) {
    String value = System.getProperty(property);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
  }
}
