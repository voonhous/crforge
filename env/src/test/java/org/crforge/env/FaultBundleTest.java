/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.replay.ReplayBattle;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.Scenarios;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.env.EnvFault.CoreFailure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A faulted Ladder episode written as a bundle, and the bundle's replay meeting the same fault. The
 * fault is one the battle core refuses as it steps: a Knight whose row sets a StartingBuff, which
 * no row played by a card asks for, refused as its play runs. The tables are the configured ones
 * with that row altered, so no lifted refusal of shipped content moves the fault. The decks name
 * rows of the tables; the costs and elixir are read from the observation.
 */
class FaultBundleTest {

  /** Seven troops and the Mirror, so every play the policy makes is a troop's or the Mirror's. */
  private static final List<String> TROOPS_AND_MIRROR =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Goblins", "Skeletons", "Mirror");

  /** The decks of a battle whose setup the battle core refuses: three champion cards in one. */
  private static final List<String> THREE_CHAMPIONS =
      List.of(
          "ArcherQueen",
          "GoldenKnight",
          "SkeletonKing",
          "Knight",
          "Archer",
          "Musketeer",
          "Giant",
          "MiniPekka");

  /** The tile every play is dropped on: the middle of the seat's own half. */
  private static final int COL = Schema.COLS / 2;

  private static final int ROW = 10;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static GameTables tables;

  @TempDir Path folder;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  /** The configured tables with the Knight's row setting a StartingBuff. */
  private static GameTables knightStartingWithRage(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("StartingBuff", "Rage")
                .put("StartingBuffTime", 2000));
  }

  private static int header(VectorEnv env, int index, int seat, int field) {
    return env.observations().asIntBuffer().get((index * 2 + seat) * Schema.SEAT_INTS + field);
  }

  private static int info(VectorEnv env, int index, int field) {
    return env.infos().asIntBuffer().get(index * Schema.INFO_INTS + field);
  }

  private static boolean open(VectorEnv env, int index, int seat, int action) {
    return env.masks().get((index * 2 + seat) * Schema.ACTIONS + action) == 1;
  }

  private static int playAction(int slot) {
    return 1 + slot * Schema.TILES + ROW * Schema.COLS + COL;
  }

  /**
   * Plays seat 0 of environment 0 to the Knight's refusal, deciding every tick: the Knight once it
   * is in the hand and open, and until then the cheapest other troop with the elixir near full, to
   * cycle the hand. The Mirror is never played. Seat 1 and every other environment wait.
   *
   * @return the decisions taken until the fault
   */
  private static int playToTheKnightsFault(VectorEnv env, int knightId, int mirrorId) {
    IntBuffer actions = env.actions().asIntBuffer();
    boolean knightGiven = false;
    for (int decision = 0; decision < 3000; decision++) {
      for (int i = 0; i < 2 * env.count(); i++) {
        actions.put(i, Schema.WAIT);
      }
      int elixir = header(env, 0, 0, Schema.H_ELIXIR) / MatchSide.SCALE;
      int knightSlot = -1;
      int troopSlot = -1;
      int troopCost = Integer.MAX_VALUE;
      for (int slot = 0; slot < 4; slot++) {
        if (!open(env, 0, 0, playAction(slot))) {
          continue;
        }
        int card = header(env, 0, 0, Schema.H_HAND_CARD + slot);
        int cost = header(env, 0, 0, Schema.H_HAND_COST + slot);
        if (card == knightId) {
          knightSlot = slot;
        } else if (card != mirrorId && cost < troopCost) {
          troopSlot = slot;
          troopCost = cost;
        }
      }
      boolean giving = false;
      if (!knightGiven && knightSlot >= 0) {
        actions.put(0, playAction(knightSlot));
        knightGiven = true;
        giving = true;
      } else if (!knightGiven && troopSlot >= 0 && elixir >= 9) {
        actions.put(0, playAction(troopSlot));
      }
      env.step();
      if (giving) {
        assertThat(header(env, 0, 0, Schema.H_LAST_REFUSAL))
            .as("the Knight given")
            .isEqualTo(Schema.REFUSAL_NONE);
      }
      if (info(env, 0, Schema.I_FAULT) != Schema.FAULT_NONE) {
        return decision + 1;
      }
    }
    throw new AssertionError("no fault in 3000 decisions; Knight given " + knightGiven);
  }

  /** One deck for each of a vector's environments, as {@link VectorEnv#reset} takes them. */
  private static String[] each(int count, List<String> deck) {
    return Stream.generate(() -> deck).limit(count).flatMap(List::stream).toArray(String[]::new);
  }

  private static JsonNode read(Path file) throws IOException {
    return MAPPER.readTree(file.toFile());
  }

  @Test
  void aFaultedEpisodesBundleReplaysToTheSameFaultAtTheSameTick(@TempDir Path altered)
      throws IOException {
    EnvConfig config =
        EnvConfig.DEFAULT
            .withDecisionTicks(1)
            .withFaultBundles(folder.resolve("bundles").toString())
            .withArena(Scenarios.arena(tables));
    GameTables tables = knightStartingWithRage(altered);
    Vocabulary vocabulary = VectorEnv.vocabulary(tables);
    try (VectorEnv env = VectorEnv.ladder(tables, config, 2, 1)) {
      env.setAutoreset(false);
      env.reset(new int[] {31, 32}, each(2, TROOPS_AND_MIRROR), each(2, TROOPS_AND_MIRROR));
      int decisions = playToTheKnightsFault(env, vocabulary.id("Knight"), vocabulary.id("Mirror"));

      EnvFault fault = env.fault(0);
      assertThat(fault.kind()).isEqualTo(EnvFault.Kind.REFUSAL);
      assertThat(fault.message()).contains("Knight is played with a StartingBuff");
      // The other battle of the vector goes on.
      assertThat(env.fault(1)).isNull();
      assertThat(env.dones().get(1)).isZero();
      assertThat(info(env, 1, Schema.I_EPISODE_STEP)).isEqualTo(decisions);

      // The bundle: the replay and the fault's record.
      assertThat(fault.bundle()).isNotNull();
      Path bundle = Path.of(fault.bundle());
      assertThat(bundle.getParent()).isEqualTo(folder.resolve("bundles").toAbsolutePath());
      JsonNode record = read(bundle.resolve(FaultBundle.FAULT));
      assertThat(record.path("kind").asText()).isEqualTo("refusal");
      assertThat(record.path("signature").asText()).isEqualTo(fault.signature());
      assertThat(record.path("message").asText()).isEqualTo(fault.message());
      assertThat(record.path("stack").asText()).contains("UnsupportedOperationException");
      assertThat(record.path("data_version").asText()).isEqualTo(tables.version());
      assertThat(record.path("content_sha").asText()).isEqualTo(tables.contentSha());
      assertThat(record.path("schema_version").asInt()).isEqualTo(Schema.VERSION);
      assertThat(record.path("config").path("decision_ticks").asInt()).isEqualTo(1);
      assertThat(record.path("config").path("arena").asText()).isEqualTo(Scenarios.arena(tables));
      assertThat(record.path("seed").asInt()).isEqualTo(31);
      assertThat(record.path("env_index").asInt()).isZero();
      assertThat(record.path("tick").asInt()).isEqualTo(fault.tick());
      assertThat(record.path("episode_step").asInt()).isEqualTo(decisions - 1);
      assertThat(record.path("actions")).hasSize(decisions);
      assertThat(record.path("commands")).isNotEmpty();
      assertThat(record.path("replay").asText()).isEqualTo(FaultBundle.REPLAY);

      // The replay meets the same fault, with the same signature, at the same tick.
      JsonNode replay = read(bundle.resolve(FaultBundle.REPLAY));
      assertThat(replay.path("endTick").asInt()).isEqualTo(fault.tick() + 1);
      assertThat(new ReplayScenario(tables).survey(replay)).isEmpty();
      ScenarioPlan plan = new ReplayScenario(tables).translate(replay);
      assertThat(plan.arenas()).containsOnly(Scenarios.arena(tables));
      Standard1v1Battle battle = ReplayBattle.build(tables, plan);
      EnvFault replayed = null;
      int checked = 0;
      for (int step = 0; step <= fault.tick() && replayed == null; step++) {
        try {
          battle.getBattle().step();
          checked = ReplayBattle.checkItems(battle, plan, checked);
        } catch (UnsupportedOperationException e) {
          replayed = EnvFault.of(new CoreFailure(e), battle.getBattle().getTick());
        }
      }
      assertThat(replayed).as("the replay's fault").isNotNull();
      assertThat(replayed.tick()).isEqualTo(fault.tick());
      assertThat(replayed.signature()).isEqualTo(fault.signature());
      assertThat(replayed.message()).isEqualTo(fault.message());
    }
  }

  @Test
  void aFaultAtTheBattlesSetupIsBundledWithAReplayThatMeetsItAsItIsBuilt() throws IOException {
    EnvConfig config =
        EnvConfig.DEFAULT.withFaultBundles(folder.toString()).withArena(Scenarios.arena(tables));
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.reset(
          new int[] {5},
          THREE_CHAMPIONS.toArray(String[]::new),
          TROOPS_AND_MIRROR.toArray(String[]::new));

      EnvFault fault = env.fault(0);
      assertThat(fault.kind()).isEqualTo(EnvFault.Kind.REFUSAL);
      assertThat(fault.bundle()).isNotNull();
      Path bundle = Path.of(fault.bundle());
      JsonNode record = read(bundle.resolve(FaultBundle.FAULT));
      assertThat(record.path("decks").get(0).get(0).asText()).isEqualTo(THREE_CHAMPIONS.get(0));
      assertThat(record.path("actions")).isEmpty();
      JsonNode replay = read(bundle.resolve(FaultBundle.REPLAY));
      ScenarioPlan plan = new ReplayScenario(tables).translate(replay);
      try {
        ReplayBattle.build(tables, plan);
        throw new AssertionError("the replay's battle was built");
      } catch (UnsupportedOperationException e) {
        assertThat(EnvFault.of(new CoreFailure(e), 0).signature()).isEqualTo(fault.signature());
      }
    }
  }

  @Test
  void aSecondFaultOfTheSameSignatureIsCountedWithNoSecondBundle() throws IOException {
    EnvConfig config = EnvConfig.DEFAULT.withFaultBundles(folder.toString());
    try (VectorEnv env = VectorEnv.ladder(tables, config, 2, 1)) {
      env.reset(new int[] {5, 6}, each(2, THREE_CHAMPIONS), each(2, TROOPS_AND_MIRROR));

      assertThat(env.faultCounts()).containsValue(2);
      assertThat(Stream.of(env.fault(0), env.fault(1)).filter(f -> f.bundle() != null)).hasSize(1);
      try (Stream<Path> bundles = Files.list(folder)) {
        assertThat(bundles).hasSize(1);
      }
    }
  }

  @Test
  void aBundleOfAConfigurationNamingNoArenaRecordsWhyItHasNoReplay(@TempDir Path own)
      throws IOException {
    // A replay's avatar names its player's arena, and the battle holds none: with no arena named,
    // the bundle is written without its replay, naming the setting.
    EnvConfig config = EnvConfig.DEFAULT.withFaultBundles(own.toString());
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.reset(
          new int[] {5},
          THREE_CHAMPIONS.toArray(String[]::new),
          TROOPS_AND_MIRROR.toArray(String[]::new));

      JsonNode record = read(Path.of(env.fault(0).bundle()).resolve(FaultBundle.FAULT));
      assertThat(record.path("replay").isNull()).isTrue();
      assertThat(record.path("replay_refusal").asText()).contains("EnvConfig.arena");
    }
  }

  @Test
  void noBundleIsWrittenByDefault() {
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      env.reset(
          new int[] {5},
          THREE_CHAMPIONS.toArray(String[]::new),
          TROOPS_AND_MIRROR.toArray(String[]::new));

      assertThat(env.fault(0).kind()).isEqualTo(EnvFault.Kind.REFUSAL);
      assertThat(env.fault(0).bundle()).isNull();
    }
  }
}
