/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.crforge.conformance.ReferenceSuite.CaseResult;
import org.crforge.conformance.ReferenceSuite.References;
import org.crforge.core.battle.data.GameTables;

/**
 * The coverage score of a data version: how much of a large set of the game's recorded battles the
 * battle core reproduces. A measurement, never a gate.
 *
 * <p>A coverage set has the layout of a references folder ({@link ReferenceSuite#load}): one corpus
 * listing under {@code corpora/} and its battles, each a scenario with the game's own trace. Every
 * battle runs as a reference case does ({@link ReferenceSuite#runAll}), and the outcomes are summed
 * up; no battle is named, so the score can be published:
 *
 * <ul>
 *   <li>{@code to_the_end}: the share of battles whose whole trace the battle core matches;
 *   <li>{@code ticks_agreed}: the share of all recorded ticks the battle core matched before its
 *       battle's first divergence (every tick of a match, the ticks before the first divergent
 *       observation of a mismatch, none of an unsupported or invalid battle);
 *   <li>{@code outcomes}: how many battles ended in each outcome;
 *   <li>{@code first_failures}: the most frequent first failures, a refusal by its feature and a
 *       mismatch by the field that differed first, list indices left out ({@code $.entities[].hp}).
 * </ul>
 *
 * <p>It also writes the score as a shields.io endpoint badge ({@link #badge}).
 *
 * <p>Arguments: {@code --out FILE --badge FILE [--threads N]}, with the coverage set ({@value
 * #PROPERTY}) and the game tables of its data version configured as system properties.
 */
public final class CoverageScore {

  /** The system property naming the coverage set's folder. */
  public static final String PROPERTY = "crforge.coverage";

  /** How many first failures the score lists. */
  static final int FIRST_FAILURES = 15;

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private CoverageScore() {}

  /**
   * One battle's outcome with the number of ticks its reference recorded.
   *
   * @param result the outcome
   * @param executedTicks the ticks the game played: the reference's executed ticks
   */
  record Battle(CaseResult result, int executedTicks) {

    /** The ticks the battle core matched before the battle's first divergence. */
    int agreedTicks() {
      return switch (result.outcome()) {
        case ReferenceComparison.DIAGNOSTIC_MATCH -> executedTicks;
        case ReferenceComparison.MISMATCH ->
            Math.min(executedTicks, result.expectation().path("first_divergent_tick").asInt(0));
        default -> 0;
      };
    }

    /** What first went wrong, or empty for a match. */
    Optional<String> firstFailure() {
      ObjectNode outcome = result.expectation();
      return switch (result.outcome()) {
        case ReferenceComparison.DIAGNOSTIC_MATCH -> Optional.empty();
        case ReferenceComparison.MISMATCH ->
            Optional.of("mismatch " + outcome.path("path").asText().replaceAll("\\[\\d+]", "[]"));
        case "unsupported" -> Optional.of("unsupported: " + kind(outcome.path("feature").asText()));
        default -> Optional.of(result.outcome());
      };
    }
  }

  /**
   * A refusal's feature as a kind: the battle's own names left out, so one mechanism counts once. A
   * command and what it spawned ({@code cmd24_0_clone0}) become {@code cmd#_clone#}, and an
   * object's id after its row name ({@code Deflect_3000006}) becomes {@code Deflect_#}.
   */
  static String kind(String feature) {
    return feature
        .replaceAll("\\bcmd\\d+(?:_\\d+)*", "cmd#")
        .replaceAll("(_clone)\\d+", "$1#")
        .replaceAll("_\\d{7}\\b", "_#");
  }

  /**
   * The score of a data version's battles.
   *
   * @param version the data version
   * @param battles every battle's outcome
   */
  static ObjectNode score(String version, List<Battle> battles) {
    Map<String, Integer> outcomes = new TreeMap<>();
    Map<String, Integer> failures = new LinkedHashMap<>();
    long agreed = 0;
    long recorded = 0;
    int matched = 0;
    for (Battle battle : battles) {
      outcomes.merge(battle.result().outcome(), 1, Integer::sum);
      if (ReferenceComparison.DIAGNOSTIC_MATCH.equals(battle.result().outcome())) {
        matched++;
      }
      agreed += battle.agreedTicks();
      recorded += battle.executedTicks();
      battle.firstFailure().ifPresent(failure -> failures.merge(failure, 1, Integer::sum));
    }
    ObjectNode score = JSON.objectNode();
    score.put("content_version", version);
    score.put("battles", battles.size());
    score.put("to_the_end", percent(matched, battles.size()));
    score.put("ticks_agreed", percent(agreed, recorded));
    ObjectNode counts = score.putObject("outcomes");
    outcomes.forEach(counts::put);
    ObjectNode first = score.putObject("first_failures");
    failures.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(FIRST_FAILURES)
        .forEach(entry -> first.put(entry.getKey(), entry.getValue()));
    return score;
  }

  /** A share in percent with one decimal, 0 of nothing. */
  static BigDecimal percent(long part, long whole) {
    if (whole == 0) {
      return BigDecimal.ZERO.setScale(1);
    }
    return BigDecimal.valueOf(part)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(whole), 1, RoundingMode.DOWN);
  }

  /**
   * The score as a shields.io endpoint badge: the data version and the share of battles played to
   * the end, coloured by that share.
   */
  static ObjectNode badge(ObjectNode score) {
    BigDecimal share = score.path("to_the_end").decimalValue();
    ObjectNode badge = JSON.objectNode();
    badge.put("schemaVersion", 1);
    badge.put("label", score.path("content_version").asText() + " battles reproduced");
    badge.put("message", share.toPlainString() + "%");
    badge.put("color", color(share.doubleValue()));
    return badge;
  }

  /** The badge colour of a share in percent. */
  static String color(double share) {
    if (share >= 90) {
      return "brightgreen";
    }
    if (share >= 75) {
      return "green";
    }
    if (share >= 60) {
      return "yellow";
    }
    if (share >= 40) {
      return "orange";
    }
    return "red";
  }

  /** The ticks the game played in a reference battle: its executed ticks, else its horizon. */
  static int executedTicks(Path reference) throws IOException {
    JsonNode json = MAPPER.readTree(reference.resolve("reference.json").toFile());
    return json.hasNonNull("executed_ticks")
        ? json.get("executed_ticks").asInt()
        : json.path("ticks").asInt();
  }

  /** Scores the configured coverage set and writes the score and its badge. */
  public static void main(String[] args) throws IOException {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    String folder = System.getProperty(PROPERTY);
    if (folder == null || folder.isBlank()) {
      throw new IllegalArgumentException("set the property " + PROPERTY);
    }
    Path out = Paths.get(required(options, "out"));
    Path badgeFile = Paths.get(required(options, "badge"));
    int threads =
        Integer.parseInt(
            options.getOrDefault(
                "threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
    References references = ReferenceSuite.load(Paths.get(folder));
    GameTables tables = GameTables.loadConfigured();
    ReferenceSuite.checkContent(references, tables);
    List<CaseResult> results = ReferenceSuite.runAll(references.cases(), tables, threads);
    List<Battle> battles = new ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
      battles.add(new Battle(results.get(i), executedTicks(references.cases().get(i).reference())));
    }
    ObjectNode score = score(references.version(), battles);
    Files.createDirectories(out.toAbsolutePath().getParent());
    Files.createDirectories(badgeFile.toAbsolutePath().getParent());
    MAPPER.writeValue(out.toFile(), score);
    MAPPER.writeValue(badgeFile.toFile(), badge(score));
    System.out.println(MAPPER.writeValueAsString(score));
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException("missing --" + name);
    }
    return value;
  }
}
