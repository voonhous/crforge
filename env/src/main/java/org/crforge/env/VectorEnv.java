/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ScenarioWriter;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * Many environments stepped together, each a {@link BattleEnv}, through flat buffers shared with
 * the caller: one call steps every environment and leaves every observation in place, so a Python
 * trainer crosses into the JVM once per decision for all of its battles and reads the results as
 * numpy views of the same memory. The buffers are direct and in the machine's byte order, laid out
 * environment after environment:
 *
 * <ul>
 *   <li>{@link #observations()}: {@code count x 2 x} {@link Schema#SEAT_INTS} 32-bit integers;
 *   <li>{@link #masks()}: {@code count x 2 x} {@link Schema#ACTIONS} bytes;
 *   <li>{@link #actions()}: {@code count x 2} 32-bit integers, which the caller fills before a
 *       step;
 *   <li>{@link #rewards()}: {@code count x 2} 32-bit floats;
 *   <li>{@link #dones()}: {@code count} bytes, 1 for an environment whose episode ended in the last
 *       step (or reset);
 *   <li>{@link #infos()}: {@code count x} {@link Schema#INFO_INTS} 32-bit integers.
 * </ul>
 *
 * <p>An episode that ends is reported with its terminal observation and {@code done} set; the next
 * step resets that environment instead of stepping it, ignores its actions, and reports the new
 * episode's first observation with {@link Schema#I_RESET} set and {@code done} clear. Without
 * autoreset the caller resets the environment itself. A reset episode draws its seed from the
 * environment's own sequence, started by the seed its last reset gave it, so a run is reproduced by
 * its reset seeds and actions alone.
 *
 * <p>Each environment's reset and step run inside a fault boundary of their own: an exception ends
 * that environment's episode alone, as truncated, with the fault's kind in its info record, the
 * fault kept ({@link #fault(int)}) and counted by signature ({@link #faultCounts()}); the other
 * environments are stepped as if nothing happened. With more than one thread the environments are
 * stepped on a pool of daemon threads, each environment whole on one thread, a thread taking a
 * contiguous share; nothing is shared between environments but the read-only tables.
 */
public final class VectorEnv implements AutoCloseable {

  /** The tables loaded so far, by folder: loaded once per process and shared. */
  private static final Map<Path, GameTables> TABLES = new ConcurrentHashMap<>();

  /** The vocabulary of each tables object, built once. */
  private static final Map<GameTables, Vocabulary> VOCABULARIES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final BattleEnv[] envs;
  private final int threads;
  private final ExecutorService pool;
  private final ByteBuffer observations;
  private final ByteBuffer masks;
  private final ByteBuffer actions;
  private final ByteBuffer rewards;
  private final ByteBuffer dones;
  private final ByteBuffer infos;
  private final IntBuffer actionInts;
  private final IntBuffer infoInts;
  private final SplittableRandom[] seeds;
  private final List<List<String>> deck0s = new ArrayList<>();
  private final List<List<String>> deck1s = new ArrayList<>();
  private final List<int[]> slots0s = new ArrayList<>();
  private final List<int[]> slots1s = new ArrayList<>();
  private final EnvFault[] faults;
  private final boolean[] broken;

  /** Whether an environment met a fault in the current call, not yet counted. */
  private final boolean[] uncounted;

  private final Map<String, Integer> faultCounts = new TreeMap<>();

  /** The signatures whose fault bundle the vector has written, or tried to. */
  private final Set<String> bundled = ConcurrentHashMap.newKeySet();

  private final Vocabulary vocabulary;
  private final EnvConfig config;
  private boolean autoreset = true;
  private boolean started;

  /**
   * A vector of environments.
   *
   * @param factory makes one environment
   * @param count how many environments
   * @param threads threads the environments are stepped on, 1 for the caller's own
   */
  public VectorEnv(Supplier<BattleEnv> factory, int count, int threads) {
    this(factory, count, threads, null, null);
  }

  private VectorEnv(
      Supplier<BattleEnv> factory,
      int count,
      int threads,
      Vocabulary vocabulary,
      EnvConfig config) {
    if (count < 1) {
      throw new IllegalArgumentException("a vector of at least one environment, got " + count);
    }
    if (threads < 1) {
      throw new IllegalArgumentException("at least one thread, got " + threads);
    }
    this.vocabulary = vocabulary;
    this.config = config;
    this.threads = Math.min(threads, count);
    this.pool =
        this.threads == 1
            ? null
            : Executors.newFixedThreadPool(
                this.threads,
                runnable -> {
                  Thread thread = new Thread(runnable, "crforge-env");
                  thread.setDaemon(true);
                  return thread;
                });
    observations = direct(count * 2L * Schema.SEAT_INTS * 4);
    masks = direct(count * 2L * Schema.ACTIONS);
    actions = direct(count * 2L * 4);
    rewards = direct(count * 2L * 4);
    dones = direct(count);
    infos = direct(count * (long) Schema.INFO_INTS * 4);
    actionInts = actions.asIntBuffer();
    infoInts = infos.asIntBuffer();
    envs = new BattleEnv[count];
    seeds = new SplittableRandom[count];
    faults = new EnvFault[count];
    broken = new boolean[count];
    uncounted = new boolean[count];
    for (int i = 0; i < count; i++) {
      BattleEnv env = factory.get();
      env.bind(
          observations
              .slice(i * 2 * Schema.SEAT_INTS * 4, 2 * Schema.SEAT_INTS * 4)
              .order(ByteOrder.nativeOrder())
              .asIntBuffer(),
          masks.slice(i * 2 * Schema.ACTIONS, 2 * Schema.ACTIONS),
          rewards.slice(i * 8, 8).order(ByteOrder.nativeOrder()).asFloatBuffer(),
          infos
              .slice(i * Schema.INFO_INTS * 4, Schema.INFO_INTS * 4)
              .order(ByteOrder.nativeOrder())
              .asIntBuffer());
      envs[i] = env;
      seeds[i] = new SplittableRandom(i);
      deck0s.add(List.of());
      deck1s.add(List.of());
      slots0s.add(new int[Schema.DECK_SIZE]);
      slots1s.add(new int[Schema.DECK_SIZE]);
    }
  }

  /**
   * The tables of a folder, loaded once per process: every vector on the same folder shares them,
   * read-only.
   *
   * @param folder a data version's tables folder, as the build makes it
   */
  public static GameTables tables(Path folder) {
    return TABLES.computeIfAbsent(folder.toAbsolutePath().normalize(), GameTables::load);
  }

  /** The vocabulary of a tables object, built once. */
  public static Vocabulary vocabulary(GameTables tables) {
    synchronized (VOCABULARIES) {
      return VOCABULARIES.computeIfAbsent(tables, Vocabulary::new);
    }
  }

  /**
   * A vector of Ladder battles on the tables of a folder, loaded once per process.
   *
   * @param folder a data version's tables folder, as the build makes it
   * @param config how the battles are played
   * @param count how many battles
   * @param threads threads the battles are stepped on
   */
  public static VectorEnv ladder(Path folder, EnvConfig config, int count, int threads) {
    return ladder(tables(folder), config, count, threads);
  }

  /**
   * A vector of Ladder battles on a data version's tables.
   *
   * @param tables the game tables
   * @param config how the battles are played
   * @param count how many battles
   * @param threads threads the battles are stepped on
   */
  public static VectorEnv ladder(GameTables tables, EnvConfig config, int count, int threads) {
    Vocabulary vocabulary = vocabulary(tables);
    return new VectorEnv(
        () -> new LadderEnv(tables, vocabulary, config), count, threads, vocabulary, config);
  }

  /**
   * A vector of synthetic environments, no battle in them: for measuring and testing the bridge
   * without the game tables.
   *
   * @param count how many environments
   * @param threads threads the environments are stepped on
   * @param entities entity rows each environment writes a step
   * @param episodeSteps decisions in each episode
   */
  public static VectorEnv synthetic(int count, int threads, int entities, int episodeSteps) {
    return new VectorEnv(() -> new SyntheticEnv(entities, episodeSteps), count, threads);
  }

  private static ByteBuffer direct(long bytes) {
    if (bytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("a buffer of " + bytes + " bytes: too many environments");
    }
    return ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
  }

  /** How many environments there are. */
  public int count() {
    return envs.length;
  }

  /** Threads the environments are stepped on. */
  public int threads() {
    return threads;
  }

  /** One environment, for a test or a tool. */
  public BattleEnv env(int index) {
    return envs[index];
  }

  public ByteBuffer observations() {
    return observations;
  }

  public ByteBuffer masks() {
    return masks;
  }

  public ByteBuffer actions() {
    return actions;
  }

  public ByteBuffer rewards() {
    return rewards;
  }

  public ByteBuffer dones() {
    return dones;
  }

  public ByteBuffer infos() {
    return infos;
  }

  /** Whether a finished environment is reset by the next step; on by default. */
  public void setAutoreset(boolean autoreset) {
    this.autoreset = autoreset;
  }

  /** The fault that ended an environment's current episode, or null. */
  public EnvFault fault(int index) {
    return faults[index];
  }

  /**
   * How many faults of each signature the vector has met since it was made, by signature (which
   * starts with the kind: {@code refusal}, {@code bug} or {@code env_bug}).
   */
  public Map<String, Integer> faultCounts() {
    return Collections.unmodifiableMap(new TreeMap<>(faultCounts));
  }

  /** How many faults of each kind the vector has met since it was made. */
  public Map<EnvFault.Kind, Integer> faultKindCounts() {
    Map<EnvFault.Kind, Integer> counts = new TreeMap<>();
    for (EnvFault.Kind kind : EnvFault.Kind.values()) {
      counts.put(kind, 0);
    }
    for (Map.Entry<String, Integer> entry : faultCounts.entrySet()) {
      for (EnvFault.Kind kind : EnvFault.Kind.values()) {
        if (entry.getKey().startsWith(kind.name().toLowerCase(Locale.ROOT) + " ")) {
          counts.merge(kind, entry.getValue(), Integer::sum);
        }
      }
    }
    return counts;
  }

  /**
   * Starts an episode in every environment, the same two decks in each, with no evolution or hero
   * slot.
   *
   * @param seeds one seed per environment
   * @param deck0 side 0's deck, by card row name
   * @param deck1 side 1's deck, by card row name
   */
  public void reset(int[] seeds, List<String> deck0, List<String> deck1) {
    reset(seeds, deck0, deck1, null, null);
  }

  /**
   * Starts an episode in every environment, the same two decks and slot flags in each.
   *
   * @param seeds one seed per environment
   * @param deck0 side 0's deck, by card row name
   * @param deck1 side 1's deck, by card row name
   * @param slots0 side 0's slot flags, one per card, or null for none
   * @param slots1 side 1's slot flags, one per card, or null for none
   */
  public void reset(
      int[] seeds, List<String> deck0, List<String> deck1, int[] slots0, int[] slots1) {
    List<String> flat0 = new ArrayList<>();
    List<String> flat1 = new ArrayList<>();
    for (int i = 0; i < envs.length; i++) {
      flat0.addAll(deck0);
      flat1.addAll(deck1);
    }
    reset(
        seeds,
        flat0.toArray(new String[0]),
        flat1.toArray(new String[0]),
        repeated(slots0),
        repeated(slots1));
  }

  /** One deck's slot flags repeated for every environment, or null for none. */
  private int[] repeated(int[] slots) {
    if (slots == null) {
      return null;
    }
    int[] flat = new int[envs.length * slots.length];
    for (int i = 0; i < envs.length; i++) {
      System.arraycopy(slots, 0, flat, i * slots.length, slots.length);
    }
    return flat;
  }

  /**
   * Starts an episode in every environment with no evolution or hero slot.
   *
   * @param seeds one seed per environment
   * @param deck0 side 0's decks, environment after environment, {@link Schema#DECK_SIZE} names each
   * @param deck1 side 1's decks, environment after environment
   */
  public void reset(int[] seeds, String[] deck0, String[] deck1) {
    reset(seeds, deck0, deck1, null, null);
  }

  /**
   * Starts an episode in every environment. Every deck and its slot flags are checked before any
   * environment is touched: a bad one refuses the whole call and changes nothing. Which cards a
   * slot may hold the battle core checks as the match starts: a card it refuses in a slot ends that
   * environment's episode as a refusal fault.
   *
   * @param seeds one seed per environment
   * @param deck0 side 0's decks, environment after environment, {@link Schema#DECK_SIZE} names each
   * @param deck1 side 1's decks, environment after environment
   * @param slots0 side 0's slot flags, environment after environment, one per card ({@link
   *     Schema#SLOT_EVOLUTION}, {@link Schema#SLOT_HERO}, both or neither); null for none
   * @param slots1 side 1's slot flags, as {@code slots0}
   */
  public void reset(int[] seeds, String[] deck0, String[] deck1, int[] slots0, int[] slots1) {
    int count = envs.length;
    if (seeds.length != count) {
      throw new IllegalArgumentException(count + " seeds are needed, got " + seeds.length);
    }
    if (deck0.length != count * Schema.DECK_SIZE || deck1.length != count * Schema.DECK_SIZE) {
      throw new IllegalArgumentException(
          "each side needs "
              + (count * Schema.DECK_SIZE)
              + " card names, one deck per environment");
    }
    for (int[] slots : new int[][] {slots0, slots1}) {
      if (slots != null && slots.length != count * Schema.DECK_SIZE) {
        throw new IllegalArgumentException(
            "each side's slot flags are "
                + (count * Schema.DECK_SIZE)
                + " values, one per card of each deck, got "
                + slots.length);
      }
    }
    List<List<String>> decks0 = new ArrayList<>();
    List<List<String>> decks1 = new ArrayList<>();
    List<int[]> flags0 = new ArrayList<>();
    List<int[]> flags1 = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int from = i * Schema.DECK_SIZE;
      int to = (i + 1) * Schema.DECK_SIZE;
      List<String> d0 = Arrays.asList(Arrays.copyOfRange(deck0, from, to));
      List<String> d1 = Arrays.asList(Arrays.copyOfRange(deck1, from, to));
      int[] f0 = slots0 == null ? new int[Schema.DECK_SIZE] : Arrays.copyOfRange(slots0, from, to);
      int[] f1 = slots1 == null ? new int[Schema.DECK_SIZE] : Arrays.copyOfRange(slots1, from, to);
      checkDeck(i, 0, d0, f0);
      checkDeck(i, 1, d1, f1);
      decks0.add(List.copyOf(d0));
      decks1.add(List.copyOf(d1));
      flags0.add(f0);
      flags1.add(f1);
    }
    for (int i = 0; i < count; i++) {
      this.seeds[i] = new SplittableRandom(seeds[i]);
      deck0s.set(i, decks0.get(i));
      deck1s.set(i, decks1.get(i));
      slots0s.set(i, flags0.get(i));
      slots1s.set(i, flags1.get(i));
    }
    run(i -> resetEnv(i, seeds[i], false));
    countFaults();
    started = true;
  }

  private void checkDeck(int index, int side, List<String> deck, int[] slots) {
    try {
      envs[index].checkDeck(deck, slots);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "environment " + index + ", side " + side + ": " + e.getMessage(), e);
    }
  }

  /**
   * Resets one environment to a seed, keeping its decks and slot flags; its own sequence restarts
   * from it.
   *
   * @param index the environment
   * @param seed the seed
   */
  public void resetEnv(int index, int seed) {
    if (!started) {
      throw new IllegalStateException("reset the whole vector first, which gives the decks");
    }
    seeds[index] = new SplittableRandom(seed);
    resetEnv(index, seed, false);
    countFaults();
  }

  /** Resets one environment inside its fault boundary. */
  private void resetEnv(int index, int seed, boolean auto) {
    BattleEnv env = envs[index];
    broken[index] = false;
    faults[index] = null;
    try {
      env.reset(
          seed,
          deck0s.get(index),
          deck1s.get(index),
          slots0s.get(index).clone(),
          slots1s.get(index).clone());
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      fail(index, e);
    }
    if (auto) {
      infoInts.put(index * Schema.INFO_INTS + Schema.I_RESET, 1);
    }
    dones.put(index, (byte) (broken[index] || env.done() ? 1 : 0));
  }

  /**
   * Steps every environment with the actions in {@link #actions()}, or resets a finished one; the
   * results are in the buffers when it returns. A fault inside an environment ends that
   * environment's episode alone and is never thrown from here.
   */
  public void step() {
    if (!started) {
      throw new IllegalStateException("reset before the first step");
    }
    if (!autoreset) {
      for (int i = 0; i < envs.length; i++) {
        if (dones.get(i) != 0) {
          throw new IllegalStateException(
              "environment " + i + " is done and autoreset is off: reset it before stepping");
        }
      }
    }
    run(this::stepEnv);
    countFaults();
  }

  private void stepEnv(int index) {
    BattleEnv env = envs[index];
    if (broken[index] || env.done()) {
      resetEnv(index, seeds[index].nextInt(), true);
      return;
    }
    try {
      env.step(actionInts.get(2 * index), actionInts.get(2 * index + 1));
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      fail(index, e);
    }
    dones.put(index, (byte) (broken[index] || env.done() ? 1 : 0));
  }

  /**
   * Ends an environment's episode for a fault. Should the environment's own ending throw too, the
   * environment is marked broken, reported done with its second fault, and reset by the next step.
   */
  private void fail(int index, Throwable thrown) {
    uncounted[index] = true;
    try {
      envs[index].fail(thrown);
      faults[index] = envs[index].fault();
    } catch (RuntimeException | AssertionError | StackOverflowError second) {
      second.addSuppressed(thrown);
      broken[index] = true;
      faults[index] = EnvFault.of(second, -1);
      return;
    }
    if (config != null && config.faultBundles() != null) {
      writeBundle(index, thrown);
    }
  }

  /**
   * Writes the bundle of an environment's fault ({@link FaultBundle}), the first of its signature
   * the vector meets: the others of that signature are counted only, so a fault met every episode
   * fills no disk. A bundle that cannot be written is left out, with the reason on the error
   * stream; the environment goes on as for any fault.
   */
  private void writeBundle(int index, Throwable thrown) {
    EnvFault fault = faults[index];
    if (!bundled.add(fault.signature())) {
      return;
    }
    String name =
        fault.kind().name().toLowerCase(Locale.ROOT)
            + "-"
            + String.format("%08x", fault.signature().hashCode())
            + "-env"
            + index
            + "-tick"
            + fault.tick();
    Path root = Path.of(config.faultBundles());
    try {
      Path folder = root.resolve(name);
      for (int n = 2; Files.exists(folder); n++) {
        folder = root.resolve(name + "-" + n);
      }
      if (envs[index].writeFaultBundle(folder, index, thrown)) {
        faults[index] = fault.withBundle(folder.toAbsolutePath().normalize().toString());
      }
    } catch (IOException | RuntimeException e) {
      System.err.println(
          "crforge env: the fault bundle of environment " + index + " was not written: " + e);
    }
  }

  /** Counts the faults of the last call, on the caller's thread, in environment order. */
  private void countFaults() {
    for (int i = 0; i < envs.length; i++) {
      if (uncounted[i]) {
        uncounted[i] = false;
        faultCounts.merge(faults[i].signature(), 1, Integer::sum);
      }
    }
  }

  private interface EnvTask {
    void run(int index);
  }

  /**
   * Runs a task on every environment, on the caller's thread or spread over the pool. The task
   * holds its own fault boundary, so every environment of a share is run whatever another does.
   */
  private void run(EnvTask task) {
    int count = envs.length;
    if (pool == null) {
      for (int i = 0; i < count; i++) {
        task.run(i);
      }
      return;
    }
    CountDownLatch latch = new CountDownLatch(threads);
    // Each thread takes a contiguous share, so a thread's environments stay in its cache from step
    // to step.
    for (int t = 0; t < threads; t++) {
      int from = (int) ((long) count * t / threads);
      int to = (int) ((long) count * (t + 1) / threads);
      pool.execute(
          () -> {
            try {
              for (int i = from; i < to; i++) {
                task.run(i);
              }
            } finally {
              latch.countDown();
            }
          });
    }
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while stepping", e);
    }
  }

  /**
   * The vector's metadata as JSON, to be kept with every run: the schema's version, sizes, field
   * names and codes, the configuration, and for Ladder battles the vocabulary with its data
   * version, content sha and hash ({@link Vocabulary#toJson()}).
   */
  public String metadataJson() {
    return metadataJson(config, vocabulary);
  }

  /**
   * The metadata of a configuration and a vocabulary, as {@link #metadataJson()} writes a vector's:
   * for a tool that writes rows of the same schema without a vector, such as a replay's labeller.
   *
   * @param config the configuration, or null for none
   * @param vocabulary the vocabulary, or null for none
   */
  public static String metadataJson(EnvConfig config, Vocabulary vocabulary) {
    ObjectNode root = JSON.createObjectNode();
    ObjectNode schema = root.putObject("schema");
    schema.put("version", Schema.VERSION);
    schema.put("seat_ints", Schema.SEAT_INTS);
    schema.put("header_ints", Schema.HEADER_INTS);
    schema.put("entity_ints", Schema.ENTITY_INTS);
    schema.put("max_entities", Schema.MAX_ENTITIES);
    schema.put("actions", Schema.ACTIONS);
    schema.put("info_ints", Schema.INFO_INTS);
    schema.put("cols", Schema.COLS);
    schema.put("rows", Schema.ROWS);
    schema.put("tile_units", Schema.TILE_UNITS);
    schema.put("hand_slots", Schema.HAND_SLOTS);
    schema.put("ability_slots", Schema.ABILITY_SLOTS);
    schema.put("first_ability", Schema.FIRST_ABILITY);
    schema.put("ability_ints", Schema.ABILITY_INTS);
    schema.put("pending_rows", Schema.PENDING_ROWS);
    schema.put("enemy_play_rows", Schema.ENEMY_PLAY_ROWS);
    schema.put("enemy_play_ints", Schema.ENEMY_PLAY_INTS);
    schema.put("queue_slots", Schema.QUEUE_SLOTS);
    schema.put("buff_slots", Schema.BUFF_SLOTS);
    schema.put("play_delay_ticks", Schema.PLAY_DELAY_TICKS);
    list(schema, "header_fields", Schema.headerFields());
    list(schema, "entity_fields", Schema.entityFields());
    list(schema, "info_fields", Schema.infoFields());
    list(schema, "flag_names", Schema.flagNames());
    list(schema, "refusal_names", Schema.refusalNames());
    list(schema, "fault_names", Schema.faultNames());
    list(schema, "state_names", Schema.stateNames());
    ObjectNode scales = schema.putObject("scales");
    new TreeMap<>(Schema.scales()).forEach(scales::put);
    if (config != null) {
      // The mask rule stands apart from the schema and the configuration: a model trained under
      // one rule reads the same observations and actions under the other.
      root.put("mask_version", config.maskVersion());
      // So does the id rule: it moves the ids' values, not the layout, so an export of the schema
      // before it still passes the schema's check.
      root.put("id_version", Schema.ID_VERSION);
      ObjectNode node = root.putObject("config");
      node.put("decision_ticks", config.decisionTicks());
      node.put("level", config.level());
      node.put("tower_level", config.towerLevel());
      node.put("max_ticks", config.maxTicks());
    }
    if (vocabulary != null) {
      root.set("vocabulary", vocabulary.toNode());
    }
    try {
      return JSON.writeValueAsString(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void list(ObjectNode node, String name, List<String> values) {
    ArrayNode array = node.putArray(name);
    values.forEach(array::add);
  }

  /**
   * An environment's episode so far as a replay of the tables' data version, as JSON text: its
   * seed, decks, levels, towers and every command its seats gave, with the ticks they were given
   * and run on ({@link ScenarioWriter}), and its {@code endTick} the battle's tick now. Read back,
   * it is the episode's battle; a replay's labeller ({@link ReplayLabeller}) reads it as any
   * replay.
   *
   * @param index the environment, a Ladder one
   * @throws IllegalStateException for an environment that plays no battle
   */
  public String replayJson(int index) {
    if (!(envs[index] instanceof LadderEnv ladder) || ladder.battle() == null) {
      throw new IllegalStateException("environment " + index + " plays no Ladder battle");
    }
    ScenarioWriter writer = new ScenarioWriter(ladder.tables());
    Standard1v1Battle battle = ladder.battle();
    try {
      return JSON.writeValueAsString(
          writer.write(writer.plan(battle, ladder.config().level()), battle.getBattle().getTick()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Writes {@link #metadataJson()} to a file.
   *
   * @param file the file, replaced when it exists
   */
  public void writeMetadata(Path file) throws IOException {
    Files.writeString(file, metadataJson() + "\n", StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  /** Prints the schema's version and sizes: a check that the installed jars load. */
  public static void main(String[] args) {
    System.out.println(
        "crforge env schema v"
            + Schema.VERSION
            + ": "
            + Schema.SEAT_INTS
            + " ints a seat, "
            + Schema.ACTIONS
            + " actions, "
            + Schema.COLS
            + "x"
            + Schema.ROWS
            + " tiles");
  }
}
