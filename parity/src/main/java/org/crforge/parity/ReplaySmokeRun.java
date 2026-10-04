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
import java.util.HashMap;
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
 * <p>The identity names the schema ({@link SmokeSchema}). In the exact-horizon schema the run steps
 * exactly {@code ticks} times. In the terminal-aware schema {@code ticks} is the requested horizon:
 * the run steps until the battle's own stop predicate holds or the horizon is reached, and the
 * manifest records {@code executed_ticks} and {@code termination} ({@code battle_stopped} or {@code
 * horizon}, with the tick). Nothing from a reference decides where the run stops.
 *
 * <p>Arguments: {@code --scenario FILE --tables DIR --identity FILE --ticks N --out NEW_DIR
 * [--provenance FILE]}. The identity file gives the run's identity fields, copied into the
 * manifest; its schema and observation scope must be a supported pair, and its content version and
 * content hash must be those of the tables. The provenance file is the caller's record of the
 * source tree that was built, copied into the manifest as given.
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
    int exit = attempt(() -> execute(options, out, manifest), manifest);
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

  /** A run's body: answers its exit code, or throws what makes the run unsupported or invalid. */
  @FunctionalInterface
  private interface Body {
    int run() throws IOException;
  }

  /**
   * Runs a body and records how it ended in the manifest: unsupported (an input with no production
   * mapping, or a behaviour the simulator refuses) or invalid (any other fault). A completed body
   * records its own status.
   *
   * @return the exit code
   */
  private static int attempt(Body body, Map<String, Object> manifest) {
    try {
      return body.run();
    } catch (UnsupportedScenarioException e) {
      manifest.put("status", "unsupported");
      manifest.put("unsupported", Map.of("feature", e.feature(), "input", e.input()));
      return UNSUPPORTED;
    } catch (UnsupportedOperationException e) {
      // The simulator refuses what it does not model with this exception.
      manifest.put("status", "unsupported");
      manifest.put(
          "unsupported", Map.of("feature", String.valueOf(e.getMessage()), "input", "the run"));
      manifest.put("stack", stack(e));
      return UNSUPPORTED;
    } catch (RuntimeException | IOException e) {
      manifest.put("status", "invalid");
      manifest.put("error", e.toString());
      manifest.put("stack", stack(e));
      return INVALID;
    }
  }

  /**
   * A run held in memory: the manifest fields the run wrote (its {@code status}, and for a
   * completed run {@code observations}, {@code trace_sha256}, the terminal-aware fields and the
   * plays that ran; for an unsupported run {@code unsupported}; for an invalid one {@code error})
   * and the trace, empty unless the run completed.
   *
   * @param manifest the run's manifest fields
   * @param trace the observations, one JSON line each
   */
  public record InProcessRun(Map<String, Object> manifest, byte[] trace) {

    /** The run's status: {@code completed}, {@code unsupported} or {@code invalid}. */
    public String status() {
      return String.valueOf(manifest.get("status"));
    }
  }

  /**
   * Runs a scenario in this process, through the same steps as a run from the command line, and
   * keeps its artifacts in memory. Nothing is shared between two runs but the tables, which are
   * only read.
   *
   * @param schema the observation schema
   * @param ticks the horizon: exact under the exact-horizon schema, the most under the
   *     terminal-aware one
   * @param scenarioBytes the scenario file's bytes
   * @param tables the game tables
   * @return the run
   */
  public static InProcessRun runInProcess(
      SmokeSchema schema, int ticks, byte[] scenarioBytes, GameTables tables) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("engine", "java");
    manifest.put("status", "invalid");
    manifest.put("schema", schema.id());
    manifest.put("ticks", ticks);
    manifest.put("scenario_sha256", sha256(scenarioBytes));
    byte[][] trace = {new byte[0]};
    attempt(
        () -> {
          trace[0] = simulate(schema, ticks, MAPPER.readTree(scenarioBytes), tables, manifest);
          return COMPLETED;
        },
        manifest);
    return new InProcessRun(manifest, trace[0]);
  }

  private static int execute(Map<String, String> options, Path out, Map<String, Object> manifest)
      throws IOException {
    JsonNode identity = MAPPER.readTree(Paths.get(required(options, "identity")).toFile());
    identity.fields().forEachRemaining(f -> manifest.put(f.getKey(), f.getValue()));
    // The contract the run is made in: a supported schema with its own observation scope.
    SmokeSchema schema =
        SmokeSchema.of(
            identity.path("schema").asText(), identity.path("observation_scope").asText());
    int ticks = Integer.parseInt(required(options, "ticks"));
    if (ticks < 0) {
      throw new IllegalArgumentException("a negative horizon: " + ticks);
    }
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

    byte[] bytes = simulate(schema, ticks, scenario, tables, manifest);
    Files.write(out.resolve("observations.jsonl"), bytes);
    Files.writeString(out.resolve("COMPLETE"), manifest.get("trace_sha256") + "\n");
    return COMPLETED;
  }

  /**
   * Builds the battle a scenario gives, steps it and observes it, recording the run's fields in the
   * manifest (the adapter, the steps executed and why they ended, the observation count, the trace
   * digest, the plays and ability commands that ran, and the completed status).
   *
   * @return the trace: one JSON line per observation
   */
  private static byte[] simulate(
      SmokeSchema schema,
      int ticks,
      JsonNode scenario,
      GameTables tables,
      Map<String, Object> manifest)
      throws IOException {
    ReplayScenario translator = new ReplayScenario(tables);
    Map<String, Object> adapter = new LinkedHashMap<>();
    adapter.put("class", ReplaySmokeRun.class.getName());
    adapter.put("simulator", Standard1v1Battle.class.getName());
    adapter.put("scenario_mapping", translator.mapping());
    manifest.put("adapter", adapter);
    ScenarioPlan plan = translator.translate(scenario);

    Standard1v1Battle battle = new Standard1v1Battle(tables, plan.towers(), true);
    battle.getWorld().seed(plan.seed());
    // Each player's data is handed over before the decks are dealt, in the scenario's order.
    for (int choices : plan.playerDataChoices()) {
      battle.addPlayerData(choices);
    }
    // A player's word, which joins its deck shuffle's draw, is the low word of its account id. Each
    // deck card comes with its slot flags.
    battle.startLadderMatch(
        plan.decks().get(0),
        plan.decks().get(1),
        plan.accounts().get(0)[1],
        plan.accounts().get(1)[1],
        plan.slotFlags().get(0),
        plan.slotFlags().get(1));
    // The commands are queued in the scenario's order, plays and ability commands alike: within a
    // tick they run in the order they were queued.
    Map<String, ScenarioPlan.Play> planned = new LinkedHashMap<>();
    Map<Integer, ScenarioPlan.Play> playsByIndex = new HashMap<>();
    Map<Integer, ScenarioPlan.Ability> abilitiesByIndex = new HashMap<>();
    plan.plays().forEach(play -> playsByIndex.put(play.index(), play));
    plan.abilities().forEach(ability -> abilitiesByIndex.put(ability.index(), ability));
    int commands = plan.plays().size() + plan.abilities().size();
    for (int index = 0; index < commands; index++) {
      ScenarioPlan.Play play = playsByIndex.get(index);
      ScenarioPlan.Ability ability = abilitiesByIndex.get(index);
      if (play != null) {
        planned.put("cmd" + index, play);
        if (play.repeats() != null) {
          // A Mirror's play repeats its side's last card, as the battle's Mirror builds it.
          battle.playMirror(
              play.runTick(),
              play.card(),
              play.level(),
              play.side(),
              play.x(),
              play.y(),
              "cmd" + index);
        } else if (play.option() != null) {
          // A variant card's play runs as the option the battle's player picks as it gives it.
          battle.playVariant(
              play.runTick(),
              play.card(),
              play.level(),
              play.side(),
              play.x(),
              play.y(),
              "cmd" + index);
        } else {
          battle.play(
              play.runTick(),
              battle.getWorld().getRecords().card(play.card()),
              play.level(),
              play.side(),
              play.x(),
              play.y(),
              "cmd" + index);
        }
      } else {
        battle.useAbility(ability.runTick(), ability.side(), ability.objectId(), "cmd" + index);
      }
    }

    ByteArrayOutputStream trace = new ByteArrayOutputStream();
    int observations = 0;
    observations += write(trace, SmokeObserver.observe(battle, schema), observations);
    // Exact horizon: every requested step. Terminal-aware: until the battle's own stop predicate
    // holds after a step, or the horizon, whichever is first; a stopped battle is not stepped.
    int executed = 0;
    int checked = 0;
    while (executed < ticks && !(schema.terminal() && SmokeObserver.stopped(battle))) {
      battle.getBattle().step();
      executed++;
      // Each play that ran in the step carries the item the simulator built for it, which the
      // scenario's packed item must be.
      checked = checkItems(battle, plan, planned, checked);
      observations += write(trace, SmokeObserver.observe(battle, schema), observations);
    }
    byte[] bytes = trace.toByteArray();
    String digest = sha256(bytes);
    if (schema.terminal()) {
      manifest.put("executed_ticks", executed);
      Map<String, Object> termination = new LinkedHashMap<>();
      termination.put("reason", SmokeObserver.stopped(battle) ? "battle_stopped" : "horizon");
      termination.put("tick", executed);
      manifest.put("termination", termination);
    }
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
    // Each ability command that ran and what it came to: 0 when it paid, else its refusal code.
    List<Map<String, Object>> abilities = new ArrayList<>();
    for (Standard1v1Battle.AbilityUse use : battle.getAbilityUses()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", use.name());
      entry.put("tick", use.tick());
      entry.put("side", use.side());
      entry.put("unit", use.unit());
      entry.put("code", use.outcome().code());
      entry.put("elixir_before", use.outcome().elixirBefore());
      entry.put("elixir_after", use.outcome().elixirAfter());
      abilities.add(entry);
    }
    if (!abilities.isEmpty()) {
      manifest.put("abilities_run", abilities);
    }
    // A play that never ran had no item built: when its item depends on the battle (a Mirror's or a
    // variant card's play, an evolution slot's card, or an evolved or hero field), the parts the
    // run checks are listed as unchecked.
    List<String> unchecked = new ArrayList<>();
    for (ScenarioPlan.Play play : plan.plays()) {
      boolean ran = battle.getPlays().stream().anyMatch(p -> p.name().equals("cmd" + play.index()));
      int deckIndex = plan.decks().get(play.side()).indexOf(play.card());
      if (!ran
          && ReplayScenario.dependsOnBattle(play, plan.slotFlags().get(play.side())[deckIndex])) {
        unchecked.add("cmd[" + play.index() + "]");
      }
    }
    if (!unchecked.isEmpty()) {
      manifest.put("items_not_built", unchecked);
    }
    manifest.put("status", "completed");
    return bytes;
  }

  /**
   * Checks the items of the plays that ran since the last check against the scenario's.
   *
   * @param checked how many of the battle's plays have been checked
   * @return how many have been checked now
   */
  private static int checkItems(
      Standard1v1Battle battle,
      ScenarioPlan plan,
      Map<String, ScenarioPlan.Play> planned,
      int checked) {
    List<Standard1v1Battle.Play> run = battle.getPlays();
    for (int i = checked; i < run.size(); i++) {
      Standard1v1Battle.Play play = run.get(i);
      ScenarioPlan.Play given = planned.get(play.name());
      if (given == null) {
        throw new IllegalStateException(
            "the battle ran a play the scenario does not give: " + play);
      }
      if (given.repeats() != null) {
        ReplayScenario.checkMirrorItem(given, play.mirror());
        continue;
      }
      if (given.option() != null) {
        ReplayScenario.checkVariantItem(given, play.variant());
        continue;
      }
      int deckIndex = plan.decks().get(given.side()).indexOf(given.card());
      ReplayScenario.checkItem(
          given, plan.slotFlags().get(given.side())[deckIndex], play.evolution());
    }
    return run.size();
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
