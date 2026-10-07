package org.crforge.parity;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.crforge.core.battle.data.GameTables;

/**
 * Runs the recorded reference battles of one content version and holds each case's outcome to the
 * expectations kept with the code.
 *
 * <p>A references folder (one content version, e.g. {@code references/14.593.1} of the game data
 * repository) holds {@code corpora/<corpus>.json}, each listing its cases (an id, a reference
 * folder, the scenario's SHA-256 and the horizon) and the content the references were recorded
 * with, and one folder per reference battle: {@code scenario.json}, {@code reference.json} (the
 * schema, horizon, executed steps and termination, observation count and the digests of the trace
 * and the scenario) and {@code observations.jsonl.gz}. A listing may name the kind of scenario its
 * cases are ({@code scenario_shape}, {@link ScenarioShape}): {@code generated} for cases generated
 * in 14.593.1's replay shape; without it they are replays.
 *
 * <p>Each case is run in this process through {@link ReplaySmokeRun#runInProcess} and compared with
 * {@link ReferenceComparison}. Its outcome is {@code diagnostic_match}, {@code mismatch} (with the
 * first divergent observation and field), {@code unsupported} (with the feature the simulator
 * refused and the input) or {@code invalid} (with the error). The expectations file names one
 * outcome per case; any case whose outcome or text differs from it, a case that newly matches
 * included, is a deviation, so a change of outcome is always reviewed with the expectations it
 * updates.
 *
 * <p>The cases can be split into shards by their position in the listing (case {@code i} belongs to
 * shard {@code i % count}), and a shard's cases run in parallel. The tables are shared between the
 * runs and only read; nothing else is.
 *
 * <p>As a program it rewrites the expectations file from a full run: {@code --references DIR
 * --expectations DIR [--threads N]}, with the game tables configured as for the tests.
 */
public final class ReferenceSuite {

  /** The system property naming the references folder of one content version. */
  public static final String PROPERTY = "crforge.references";

  /** The environment variable naming the references folder, when the property is not set. */
  public static final String ENVIRONMENT = "CRFORGE_REFERENCES";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private ReferenceSuite() {
    // Utility class
  }

  /**
   * One case of a corpus.
   *
   * @param corpus the corpus's name
   * @param id the case's id within its corpus
   * @param reference the reference battle's folder
   * @param scenarioSha256 the scenario's SHA-256 the corpus names
   * @param ticks the horizon the corpus names
   * @param schema the schema the corpus declares, or null
   * @param shape the kind of scenario the corpus names its cases, a replay when it names none
   */
  public record Case(
      String corpus,
      String id,
      Path reference,
      String scenarioSha256,
      int ticks,
      String schema,
      ScenarioShape shape) {

    /** A case of a corpus of replays. */
    public Case(
        String corpus, String id, Path reference, String scenarioSha256, int ticks, String schema) {
      this(corpus, id, reference, scenarioSha256, ticks, schema, ScenarioShape.REPLAY);
    }

    /** The case's key in the expectations: corpus and id. */
    public String key() {
      return corpus + "/" + id;
    }
  }

  /**
   * The cases of a references folder and the content they were recorded with.
   *
   * @param version the content version
   * @param contentSha the content hash
   * @param cases every case, corpus by corpus in name order, each corpus in its own order
   */
  public record References(String version, String contentSha, List<Case> cases) {}

  /**
   * One case's outcome.
   *
   * @param key the case's key
   * @param expectation the outcome as the expectations file holds it
   * @param traceSha256 the SHA-256 of the simulator's trace when the run completed, else null
   * @param millis how long the case took
   */
  public record CaseResult(String key, ObjectNode expectation, String traceSha256, long millis) {

    /** The outcome's name. */
    public String outcome() {
      return expectation.path("outcome").asText();
    }
  }

  /**
   * The configured references folder, or empty when neither the property nor the variable is set.
   */
  public static Optional<Path> configuredDirectory() {
    String configured = System.getProperty(PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(ENVIRONMENT);
    }
    return configured == null || configured.isBlank()
        ? Optional.empty()
        : Optional.of(Paths.get(configured));
  }

  /**
   * Lists the cases of a references folder. Every corpus must name the same content.
   *
   * @param folder the references folder of one content version
   */
  public static References load(Path folder) throws IOException {
    List<Path> listings;
    try (Stream<Path> files = Files.list(folder.resolve("corpora"))) {
      listings = files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    }
    if (listings.isEmpty()) {
      throw new IllegalStateException("no corpora in " + folder);
    }
    String version = null;
    String contentSha = null;
    List<Case> cases = new ArrayList<>();
    for (Path listing : listings) {
      JsonNode corpus = MAPPER.readTree(listing.toFile());
      String corpusVersion = corpus.path(ContentFields.CONTENT_VERSION).asText();
      String corpusSha = corpus.path(ContentFields.CONTENT_SHA).asText();
      if (version == null) {
        version = corpusVersion;
        contentSha = corpusSha;
      } else if (!version.equals(corpusVersion) || !contentSha.equals(corpusSha)) {
        throw new IllegalStateException(listing + " is of another content");
      }
      String name = corpus.path("corpus").asText();
      String schema = corpus.hasNonNull("schema") ? corpus.get("schema").asText() : null;
      // The listing names its cases' kind; a listing that names none lists replays.
      ScenarioShape shape =
          corpus.hasNonNull("scenario_shape")
              ? ScenarioShape.of(corpus.get("scenario_shape").asText())
              : ScenarioShape.REPLAY;
      for (JsonNode entry : corpus.path("cases")) {
        cases.add(
            new Case(
                name,
                entry.path("id").asText(),
                folder.resolve(entry.path("reference").asText()),
                entry.path("scenario_sha256").asText(),
                entry.path("ticks").asInt(),
                schema,
                shape));
      }
    }
    return new References(version, contentSha, cases);
  }

  /**
   * The cases of one shard: case {@code i} belongs to shard {@code i % count}.
   *
   * @param index the shard, from 0
   * @param count how many shards
   */
  public static List<Case> shard(List<Case> cases, int index, int count) {
    if (count < 1 || index < 0 || index >= count) {
      throw new IllegalArgumentException("not a shard: " + index + " of " + count);
    }
    return IntStream.range(0, cases.size())
        .filter(i -> i % count == index)
        .mapToObj(cases::get)
        .toList();
  }

  /**
   * Checks that the tables are of the content the references were recorded with.
   *
   * @throws IllegalStateException when they are not
   */
  public static void checkContent(References references, GameTables tables) {
    if (!references.version().equals(tables.version())
        || !references.contentSha().equals(tables.contentSha())) {
      throw new IllegalStateException(
          "the game tables are of content "
              + tables.version()
              + " / "
              + tables.contentSha()
              + ", the references of "
              + references.version()
              + " / "
              + references.contentSha());
    }
  }

  /**
   * Runs cases in parallel and answers their results in the cases' order.
   *
   * @param threads how many cases run at once
   */
  public static List<CaseResult> runAll(List<Case> cases, GameTables tables, int threads) {
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
    try {
      List<Future<CaseResult>> pending = new ArrayList<>();
      for (Case entry : cases) {
        pending.add(pool.submit(() -> run(entry, tables)));
      }
      List<CaseResult> results = new ArrayList<>();
      for (Future<CaseResult> result : pending) {
        results.add(result.get());
      }
      return results;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("a case failed to run: " + e.getCause(), e.getCause());
    } finally {
      pool.shutdownNow();
    }
  }

  /** Runs one case and compares it with its reference battle. */
  public static CaseResult run(Case entry, GameTables tables) {
    long start = System.nanoTime();
    String[] traceSha = {null};
    ObjectNode outcome;
    try {
      outcome = outcome(entry, tables, traceSha);
    } catch (IOException e) {
      outcome = invalid("the reference battle cannot be read: " + e);
    }
    return new CaseResult(
        entry.key(), outcome, traceSha[0], (System.nanoTime() - start) / 1_000_000);
  }

  /**
   * One case's outcome.
   *
   * @param traceSha receives the digest of the simulator's trace when the run completes
   */
  private static ObjectNode outcome(Case entry, GameTables tables, String[] traceSha)
      throws IOException {
    JsonNode reference = MAPPER.readTree(entry.reference().resolve("reference.json").toFile());
    byte[] scenario = Files.readAllBytes(entry.reference().resolve("scenario.json"));
    byte[] trace;
    try (InputStream packed =
        new GZIPInputStream(
            Files.newInputStream(entry.reference().resolve("observations.jsonl.gz")))) {
      trace = packed.readAllBytes();
    }
    // The reference must be of the frozen input and horizon its corpus names, and of the corpus's
    // schema when the corpus declares one.
    String scenarioSha = sha256(scenario);
    if (!scenarioSha.equals(entry.scenarioSha256())
        || !scenarioSha.equals(reference.path("scenario_sha256").asText())
        || reference.path("ticks").asInt(-1) != entry.ticks()) {
      return invalid("the reference is not of the corpus case (scenario or horizon differs)");
    }
    String schemaId = reference.path("schema").asText();
    if (entry.schema() != null && !entry.schema().equals(schemaId)) {
      return invalid("the reference is not of the corpus schema " + entry.schema());
    }
    Optional<SmokeSchema> schema =
        Stream.of(SmokeSchema.values()).filter(s -> s.id().equals(schemaId)).findFirst();
    if (schema.isEmpty()) {
      return invalid("an unsupported smoke schema: " + schemaId);
    }

    ReplaySmokeRun.InProcessRun run =
        ReplaySmokeRun.runInProcess(schema.get(), entry.ticks(), scenario, tables, entry.shape());
    ObjectNode result = JSON.objectNode();
    switch (run.status()) {
      case "unsupported" -> {
        Map<?, ?> unsupported = (Map<?, ?>) run.manifest().get("unsupported");
        result.put("outcome", "unsupported");
        result.put("feature", String.valueOf(unsupported.get("feature")));
        result.put("input", String.valueOf(unsupported.get("input")));
        return result;
      }
      case "completed" -> {
        JsonNode manifest = MAPPER.valueToTree(run.manifest());
        traceSha[0] = manifest.path("trace_sha256").asText();
        ReferenceComparison.Result compared =
            ReferenceComparison.compare(reference, trace, manifest, run.trace());
        result.put("outcome", compared.outcome());
        if (ReferenceComparison.MISMATCH.equals(compared.outcome())) {
          result.put("first_divergent_tick", compared.firstDivergentTick());
          result.put("path", compared.difference().path("path").asText());
        } else if (ReferenceComparison.INVALID.equals(compared.outcome())) {
          result.put("error", compared.error());
        }
        return result;
      }
      default -> {
        return invalid(String.valueOf(run.manifest().get("error")));
      }
    }
  }

  private static ObjectNode invalid(String error) {
    ObjectNode result = JSON.objectNode();
    result.put("outcome", "invalid");
    result.put("error", error);
    return result;
  }

  /** The expectations file of a content version in the expectations folder. */
  public static Path expectationsFile(Path folder, String version) {
    return folder.resolve(version + ".json");
  }

  /** Reads an expectations file: each case's key and its expected outcome. */
  public static Map<String, JsonNode> readExpectations(Path file) throws IOException {
    JsonNode document = MAPPER.readTree(file.toFile());
    Map<String, JsonNode> expected = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : document.path("cases").properties()) {
      expected.put(entry.getKey(), entry.getValue());
    }
    return expected;
  }

  /**
   * The deviations of results from the expectations, one line per case: a case whose outcome or
   * text differs (a newly matching case included) or that has no expectation.
   */
  public static List<String> deviations(List<CaseResult> results, Map<String, JsonNode> expected) {
    List<String> deviations = new ArrayList<>();
    for (CaseResult result : results) {
      JsonNode expectation = expected.get(result.key());
      if (expectation == null) {
        deviations.add(result.key() + ": no expectation; now " + compact(result.expectation()));
      } else if (!expectation.equals(result.expectation())) {
        deviations.add(
            result.key()
                + ": expected "
                + compact(expectation)
                + ", now "
                + compact(result.expectation()));
      }
    }
    return deviations;
  }

  /** The expectations no case of the references has, one line each. */
  public static List<String> staleExpectations(
      References references, Map<String, JsonNode> expected) {
    List<String> keys = references.cases().stream().map(Case::key).toList();
    return expected.keySet().stream()
        .filter(key -> !keys.contains(key))
        .map(key -> key + ": an expectation for a case the references do not have")
        .toList();
  }

  /**
   * Writes an expectations file: one case per line, in key order, so a review sees one line per
   * changed outcome.
   */
  public static void writeExpectations(Path file, String version, List<CaseResult> results)
      throws IOException {
    Map<String, ObjectNode> sorted = new TreeMap<>();
    results.forEach(r -> sorted.put(r.key(), r.expectation()));
    StringBuilder text = new StringBuilder();
    text.append("{\n \"" + ContentFields.CONTENT_VERSION + "\": ")
        .append(MAPPER.writeValueAsString(version));
    text.append(",\n \"cases\": {\n");
    int i = 0;
    for (Map.Entry<String, ObjectNode> entry : sorted.entrySet()) {
      text.append("  ")
          .append(MAPPER.writeValueAsString(entry.getKey()))
          .append(": ")
          .append(compact(entry.getValue()))
          .append(++i < sorted.size() ? ",\n" : "\n");
    }
    text.append(" }\n}\n");
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
  }

  /**
   * Writes a scorecard: the counts of each outcome and every case's outcome and time.
   *
   * @param shard the shard's index and count, e.g. {@code 0/2}
   */
  public static void writeScorecard(
      Path file, References references, String shard, List<CaseResult> results) throws IOException {
    ObjectNode card = JSON.objectNode();
    card.put(ContentFields.CONTENT_VERSION, references.version());
    card.put(ContentFields.CONTENT_SHA, references.contentSha());
    card.put("shard", shard);
    Map<String, Integer> counts = new TreeMap<>();
    results.forEach(r -> counts.merge(r.outcome(), 1, Integer::sum));
    card.set("counts", MAPPER.valueToTree(counts));
    ObjectNode cases = card.putObject("cases");
    for (CaseResult result : results) {
      ObjectNode entry = result.expectation().deepCopy();
      if (result.traceSha256() != null) {
        entry.put("trace_sha256", result.traceSha256());
      }
      entry.put("millis", result.millis());
      cases.set(result.key(), entry);
    }
    card.put(
        "note",
        "diagnostic_match is agreement with a recorded reference battle on the observation schema;"
            + " it is not a release pass");
    Files.createDirectories(file.toAbsolutePath().getParent());
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), card);
  }

  /** Prints a value on one line, with a space after each colon and comma. */
  private static final class OneLine extends MinimalPrettyPrinter {
    @Override
    public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
      g.writeRaw(": ");
    }

    @Override
    public void writeObjectEntrySeparator(JsonGenerator g) throws IOException {
      g.writeRaw(", ");
    }

    @Override
    public void writeArrayValueSeparator(JsonGenerator g) throws IOException {
      g.writeRaw(", ");
    }
  }

  /** A value on one line, with a space after each colon and comma. */
  static String compact(JsonNode value) {
    try {
      return MAPPER.writer(new OneLine()).writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Rewrites the expectations file of the configured references from a full run of their cases.
   *
   * <p>Arguments: {@code --references DIR --expectations DIR [--threads N]}.
   */
  public static void main(String[] args) throws IOException {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    Path folder =
        Optional.ofNullable(options.get("references"))
            .map(Paths::get)
            .or(ReferenceSuite::configuredDirectory)
            .orElseThrow(
                () -> new IllegalArgumentException("set " + PROPERTY + " or " + ENVIRONMENT));
    Path expectations = Paths.get(options.getOrDefault("expectations", "reference-expectations"));
    int threads =
        Integer.parseInt(
            options.getOrDefault(
                "threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
    References references = load(folder);
    GameTables tables = GameTables.loadConfigured();
    checkContent(references, tables);
    List<CaseResult> results = runAll(references.cases(), tables, threads);
    Path file = expectationsFile(expectations, references.version());
    writeExpectations(file, references.version(), results);
    Map<String, Integer> counts = new TreeMap<>();
    results.forEach(r -> counts.merge(r.outcome(), 1, Integer::sum));
    System.out.println("wrote " + file + ": " + counts);
  }
}
