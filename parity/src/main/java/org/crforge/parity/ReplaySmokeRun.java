package org.crforge.parity;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import java.util.TreeMap;
import java.util.stream.Stream;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * Runs one replay scenario on the production simulator and writes the run's artifacts: {@code
 * observations.jsonl}, {@code manifest.json} and, for a completed run only, {@code COMPLETE}.
 *
 * <p>The run is the simulator's own: the scenario is translated into its inputs ({@link
 * ReplayScenario}), the battle is built and stepped by {@link Standard1v1Battle}, and each
 * observation is read from its objects ({@link SmokeObserver}). Observation 0 is the battle as it
 * was set up; observation {@code n} is read after the {@code n}-th completed step.
 *
 * <p>The run ends one of three ways, and the process exits accordingly: completed (0), invalid (2:
 * a fault, a truncated run, a scenario that cannot be read, tables of another content version) or
 * unsupported (3: an input the production simulator has no mapping for, or a behaviour it refuses).
 * Only a completed run has a {@code COMPLETE} marker.
 *
 * <p>Arguments: {@code --scenario FILE --tables DIR --identity FILE --ticks N --out NEW_DIR
 * [--provenance FILE]}. The identity file gives the run's identity fields, copied into the
 * manifest; its content version and content hash must be those of the tables. The provenance file
 * is the caller's record of the source tree that was built, copied into the manifest as given.
 */
public final class ReplaySmokeRun {

  /** The exit code of a completed run. */
  public static final int COMPLETED = 0;

  /** The exit code of an invalid run. */
  public static final int INVALID = 2;

  /** The exit code of an unsupported run. */
  public static final int UNSUPPORTED = 3;

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
          .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);

  private ReplaySmokeRun() {
    // Entry point
  }

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /**
   * Runs the scenario the arguments name.
   *
   * @return the exit code
   */
  static int run(String[] args) {
    Map<String, String> options = options(args);
    Path out = Paths.get(required(options, "out"));
    try {
      // A run directory is written once: an existing one is never added to.
      Files.createDirectories(out.toAbsolutePath().getParent());
      Files.createDirectory(out);
    } catch (IOException e) {
      System.err.println("cannot create the run directory " + out + ": " + e);
      return INVALID;
    }
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("engine", "java");
    manifest.put("status", "invalid");
    int exit = INVALID;
    try {
      exit = execute(options, out, manifest);
    } catch (UnsupportedScenarioException e) {
      manifest.put("status", "unsupported");
      manifest.put("unsupported", Map.of("feature", e.feature(), "input", e.input()));
      exit = UNSUPPORTED;
    } catch (UnsupportedOperationException e) {
      // The simulator refuses what it does not model with this exception.
      manifest.put("status", "unsupported");
      manifest.put(
          "unsupported", Map.of("feature", String.valueOf(e.getMessage()), "input", "the run"));
      manifest.put("stack", stack(e));
      exit = UNSUPPORTED;
    } catch (RuntimeException | IOException e) {
      manifest.put("status", "invalid");
      manifest.put("error", e.toString());
      manifest.put("stack", stack(e));
      exit = INVALID;
    }
    try {
      MAPPER
          .writerWithDefaultPrettyPrinter()
          .writeValue(out.resolve("manifest.json").toFile(), manifest);
    } catch (IOException e) {
      System.err.println("cannot write the manifest: " + e);
      return INVALID;
    }
    System.out.println(
        "status=" + manifest.get("status") + " observations=" + manifest.get("observations"));
    return exit;
  }

  private static int execute(Map<String, String> options, Path out, Map<String, Object> manifest)
      throws IOException {
    JsonNode identity = MAPPER.readTree(Paths.get(required(options, "identity")).toFile());
    identity.fields().forEachRemaining(f -> manifest.put(f.getKey(), f.getValue()));
    int ticks = Integer.parseInt(required(options, "ticks"));
    manifest.put("ticks", ticks);
    if (options.containsKey("provenance")) {
      manifest.put("java", MAPPER.readTree(Paths.get(options.get("provenance")).toFile()));
    }

    byte[] scenarioBytes = Files.readAllBytes(Paths.get(required(options, "scenario")));
    manifest.put("scenario_sha256", sha256(scenarioBytes));
    Files.write(out.resolve("scenario.json"), scenarioBytes);
    JsonNode scenario = MAPPER.readTree(scenarioBytes);

    Path tablesFolder = Paths.get(required(options, "tables"));
    GameTables tables = GameTables.load(tablesFolder);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tables", tablesFolder.toAbsolutePath().toString());
    data.put("version", tables.version());
    data.put("content_sha", tables.contentSha());
    data.put("files", fileDigests(tablesFolder));
    manifest.put("data", data);
    // The run is of the content the identity names, or it is of nothing.
    if (!tables.version().equals(identity.path("content_version").asText())
        || !tables.contentSha().equals(identity.path("content_sha").asText())) {
      throw new IllegalStateException(
          "the game tables are of content "
              + tables.version()
              + " / "
              + tables.contentSha()
              + ", not the identity's");
    }

    ReplayScenario translator = new ReplayScenario(tables);
    Map<String, Object> adapter = new LinkedHashMap<>();
    adapter.put("class", ReplaySmokeRun.class.getName());
    adapter.put("simulator", Standard1v1Battle.class.getName());
    adapter.put("scenario_mapping", translator.mapping());
    manifest.put("adapter", adapter);
    ScenarioPlan plan = translator.translate(scenario);

    Standard1v1Battle battle = new Standard1v1Battle(tables, plan.towerLevel());
    battle.getWorld().seed(plan.seed());
    // Each player's data is handed over before the decks are dealt, in the scenario's order.
    for (int choices : plan.playerDataChoices()) {
      battle.addPlayerData(choices);
    }
    // A player's word, which joins its deck shuffle's draw, is the low word of its account id.
    battle.startLadderMatch(
        plan.decks().get(0),
        plan.decks().get(1),
        plan.accounts().get(0)[1],
        plan.accounts().get(1)[1]);
    for (ScenarioPlan.Play play : plan.plays()) {
      battle.play(
          play.runTick(),
          battle.getWorld().getRecords().card(play.card()),
          play.level(),
          play.side(),
          play.x(),
          play.y(),
          "cmd" + play.index());
    }

    ByteArrayOutputStream trace = new ByteArrayOutputStream();
    int observations = 0;
    observations += write(trace, SmokeObserver.observe(battle), observations);
    for (int step = 0; step < ticks; step++) {
      battle.getBattle().step();
      observations += write(trace, SmokeObserver.observe(battle), observations);
    }
    byte[] bytes = trace.toByteArray();
    Files.write(out.resolve("observations.jsonl"), bytes);
    String digest = sha256(bytes);
    manifest.put("observations", observations);
    manifest.put("trace_sha256", digest);
    List<Map<String, Object>> plays = new ArrayList<>();
    for (Standard1v1Battle.Play play : battle.getPlays()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", play.name());
      entry.put("tick", play.tick());
      entry.put("match_code", play.matchCode());
      entry.put("placed", play.result() != null && play.result().placed());
      entry.put("units", play.units().size());
      plays.add(entry);
    }
    manifest.put("plays_run", plays);
    manifest.put("status", "completed");
    Files.writeString(out.resolve("COMPLETE"), digest + "\n");
    return COMPLETED;
  }

  /**
   * Appends one observation as a line. The battle's tick must be the observation's number: a battle
   * that stops stepping would otherwise repeat a tick unnoticed.
   */
  private static int write(ByteArrayOutputStream trace, JsonNode observation, int number)
      throws IOException {
    if (observation.path("tick").asInt() != number) {
      throw new IllegalStateException(
          "observation "
              + number
              + " is of tick "
              + observation.path("tick").asInt()
              + ": the battle has stopped stepping");
    }
    // Keys sorted and no spaces, so two runs of one battle are the same bytes.
    Object sorted = MAPPER.treeToValue(observation, Object.class);
    trace.write(MAPPER.writeValueAsBytes(sort(sorted)));
    trace.write('\n');
    return 1;
  }

  /** A JSON value with every object's keys in order. */
  private static Object sort(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((k, v) -> sorted.put((String) k, sort(v)));
      return sorted;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ReplaySmokeRun::sort).toList();
    }
    return value;
  }

  /** The SHA-256 of every table file the run could read, by file name. */
  private static Map<String, String> fileDigests(Path folder) throws IOException {
    Map<String, String> digests = new TreeMap<>();
    try (Stream<Path> listing = Files.list(folder)) {
      for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
        digests.put(file.getFileName().toString(), sha256(Files.readAllBytes(file)));
      }
    }
    return digests;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String stack(Throwable e) {
    StringWriter text = new StringWriter();
    e.printStackTrace(new PrintWriter(text));
    return text.toString();
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("not an option: " + args[i]);
      }
      options.put(args[i].substring(2), args[i + 1]);
    }
    return options;
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) {
      throw new IllegalArgumentException("missing --" + name);
    }
    return value;
  }
}
