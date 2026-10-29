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
import java.util.stream.Stream;
import org.crforge.conformance.ReferenceSuite.Case;
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
 * <p>It also writes the score as a shields.io endpoint badge ({@link #badge}). A large set is
 * scored in parts, one per part file of the set ({@link #part}): each part's battles give a tally
 * ({@link #tally}), the counts behind a score, and the tallies of all parts sum up ({@link #merge})
 * to the score of the whole set, exactly as one run over all of it.
 *
 * <p>Arguments: see {@link #main}; the coverage set ({@value #PROPERTY}) and the game tables of its
 * data version are configured as system properties.
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
   * A tally of battles: the counts a score is made of. Tallies of parts of one data version's set
   * sum up ({@link #merge}) to the tally of the whole set, so a set can be scored in parts.
   *
   * @param version the data version
   * @param battles every battle's outcome
   */
  static ObjectNode tally(String version, List<Battle> battles) {
    Map<String, Long> outcomes = new TreeMap<>();
    Map<String, Long> failures = new TreeMap<>();
    long agreed = 0;
    long recorded = 0;
    long matched = 0;
    for (Battle battle : battles) {
      outcomes.merge(battle.result().outcome(), 1L, Long::sum);
      if (ReferenceComparison.DIAGNOSTIC_MATCH.equals(battle.result().outcome())) {
        matched++;
      }
      agreed += battle.agreedTicks();
      recorded += battle.executedTicks();
      battle.firstFailure().ifPresent(failure -> failures.merge(failure, 1L, Long::sum));
    }
    ObjectNode tally = JSON.objectNode();
    tally.put("content_version", version);
    // Counts are longs, as merge() sums them, so a tally and a sum of tallies compare equal.
    tally.put("battles", (long) battles.size());
    tally.put("matched", matched);
    tally.put("agreed_ticks", agreed);
    tally.put("recorded_ticks", recorded);
    outcomes.forEach(tally.putObject("outcomes")::put);
    failures.forEach(tally.putObject("failures")::put);
    return tally;
  }

  /**
   * The sum of tallies of one data version.
   *
   * @throws IllegalArgumentException when there are none, or they are of more than one version
   */
  static ObjectNode merge(List<? extends JsonNode> tallies) {
    if (tallies.isEmpty()) {
      throw new IllegalArgumentException("no tally to merge");
    }
    String version = tallies.get(0).path("content_version").asText();
    long battles = 0;
    long matched = 0;
    long agreed = 0;
    long recorded = 0;
    Map<String, Long> outcomes = new TreeMap<>();
    Map<String, Long> failures = new TreeMap<>();
    for (JsonNode tally : tallies) {
      if (!version.equals(tally.path("content_version").asText())) {
        throw new IllegalArgumentException(
            "tallies of " + version + " and " + tally.path("content_version").asText());
      }
      battles += tally.path("battles").asLong();
      matched += tally.path("matched").asLong();
      agreed += tally.path("agreed_ticks").asLong();
      recorded += tally.path("recorded_ticks").asLong();
      tally
          .path("outcomes")
          .fields()
          .forEachRemaining(e -> outcomes.merge(e.getKey(), e.getValue().asLong(), Long::sum));
      tally
          .path("failures")
          .fields()
          .forEachRemaining(e -> failures.merge(e.getKey(), e.getValue().asLong(), Long::sum));
    }
    ObjectNode sum = JSON.objectNode();
    sum.put("content_version", version);
    sum.put("battles", battles);
    sum.put("matched", matched);
    sum.put("agreed_ticks", agreed);
    sum.put("recorded_ticks", recorded);
    outcomes.forEach(sum.putObject("outcomes")::put);
    failures.forEach(sum.putObject("failures")::put);
    return sum;
  }

  /**
   * The score of a tally: the shares in percent, the outcomes and the most frequent first failures.
   */
  static ObjectNode score(JsonNode tally) {
    ObjectNode score = JSON.objectNode();
    score.put("content_version", tally.path("content_version").asText());
    score.put("battles", tally.path("battles").asLong());
    score.put(
        "to_the_end", percent(tally.path("matched").asLong(), tally.path("battles").asLong()));
    score.put(
        "ticks_agreed",
        percent(tally.path("agreed_ticks").asLong(), tally.path("recorded_ticks").asLong()));
    score.set("outcomes", tally.path("outcomes").deepCopy());
    ObjectNode first = score.putObject("first_failures");
    List<Map.Entry<String, Long>> failures = new ArrayList<>();
    tally
        .path("failures")
        .fields()
        .forEachRemaining(e -> failures.add(Map.entry(e.getKey(), e.getValue().asLong())));
    failures.stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(FIRST_FAILURES)
        .forEach(entry -> first.put(entry.getKey(), entry.getValue()));
    return score;
  }

  /** The score of a data version's battles. */
  static ObjectNode score(String version, List<Battle> battles) {
    return score(tally(version, battles));
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

  /**
   * The cases of one part of a set: the part's battles, in the listing's order. The listing under
   * {@code corpora/} names the parts and how many battles each holds; part {@code k} holds the
   * battles after those of the parts before it.
   *
   * @throws IllegalArgumentException when the set has no such part, or the part is not unpacked
   */
  static List<Case> part(Path folder, References references, int part) throws IOException {
    List<Path> listings;
    try (Stream<Path> files = Files.list(folder.resolve("corpora"))) {
      listings = files.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
    }
    if (listings.size() != 1) {
      throw new IllegalArgumentException("a coverage set has one listing, not " + listings.size());
    }
    JsonNode parts = MAPPER.readTree(listings.get(0).toFile()).path("parts");
    if (part < 0 || part >= parts.size()) {
      throw new IllegalArgumentException("the set has no part " + part + " of " + parts.size());
    }
    int start = 0;
    for (int k = 0; k < part; k++) {
      start += parts.get(k).path("battles").asInt();
    }
    List<Case> cases =
        references.cases().subList(start, start + parts.get(part).path("battles").asInt());
    for (Case entry : cases) {
      if (!Files.isDirectory(entry.reference())) {
        throw new IllegalArgumentException("part " + part + " is not unpacked: " + entry.id());
      }
    }
    return cases;
  }

  /**
   * Scores the configured coverage set, a part of it, or the tallies of its parts, into a folder.
   *
   * <ul>
   *   <li>{@code --out-dir DIR}: every battle of the set; writes {@code score-<version>.json} and
   *       {@code badge-<version>.json};
   *   <li>{@code --out-dir DIR --part K}: the battles of part K; writes its tally, {@code
   *       tally-<version>-<K>.json};
   *   <li>{@code --out-dir DIR --merge TALLIES}: no battle, the sum of the {@code tally-*.json} in
   *       TALLIES; writes the score and the badge.
   * </ul>
   *
   * <p>{@code --threads N} sets how many battles run at once.
   */
  public static void main(String[] args) throws IOException {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    Path out = Files.createDirectories(Paths.get(required(options, "out-dir")));
    if (options.containsKey("merge")) {
      List<JsonNode> tallies = new ArrayList<>();
      try (Stream<Path> files = Files.list(Paths.get(options.get("merge")))) {
        for (Path file :
            files
                .filter(p -> p.getFileName().toString().matches("tally-.*\\.json"))
                .sorted()
                .toList()) {
          tallies.add(MAPPER.readTree(file.toFile()));
        }
      }
      write(out, score(merge(tallies)));
      return;
    }
    String folder = System.getProperty(PROPERTY);
    if (folder == null || folder.isBlank()) {
      throw new IllegalArgumentException("set the property " + PROPERTY);
    }
    int threads =
        Integer.parseInt(
            options.getOrDefault(
                "threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
    References references = ReferenceSuite.load(Paths.get(folder));
    List<Case> cases =
        options.containsKey("part")
            ? part(Paths.get(folder), references, Integer.parseInt(options.get("part")))
            : references.cases();
    GameTables tables = GameTables.loadConfigured();
    ReferenceSuite.checkContent(references, tables);
    List<CaseResult> results = ReferenceSuite.runAll(cases, tables, threads);
    List<Battle> battles = new ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
      battles.add(new Battle(results.get(i), executedTicks(cases.get(i).reference())));
    }
    ObjectNode tally = tally(references.version(), battles);
    if (options.containsKey("part")) {
      Path file =
          out.resolve("tally-" + references.version() + "-" + options.get("part") + ".json");
      MAPPER.writeValue(file.toFile(), tally);
      System.out.println(MAPPER.writeValueAsString(score(tally)));
      return;
    }
    write(out, score(tally));
  }

  /** Writes a score and its badge into a folder, and prints the score. */
  private static void write(Path out, ObjectNode score) throws IOException {
    String version = score.path("content_version").asText();
    MAPPER.writeValue(out.resolve("score-" + version + ".json").toFile(), score);
    MAPPER.writeValue(out.resolve("badge-" + version + ".json").toFile(), badge(score));
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
