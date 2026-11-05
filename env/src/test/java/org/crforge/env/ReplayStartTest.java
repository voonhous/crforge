/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayBattle;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.Scenarios;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Episodes started on a replay's battle at a decision's tick. The replays are episodes of the
 * Ladder environment written as replays; each start is held to the replay's own battle ({@link
 * ReplayBattle#build}) and to the rows the replay labeller writes, never to typed values.
 */
class ReplayStartTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  private static GameTables tables;
  private static EnvConfig config;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
    config = EnvConfig.DEFAULT.withArena(Scenarios.arena(tables));
  }

  /** A random open play of a seat, three decisions in ten, else the wait. */
  private static int randomAction(LadderEnv env, int seat, SplittableRandom random) {
    if (random.nextInt(10) >= 3) {
      return Schema.WAIT;
    }
    List<Integer> open = new ArrayList<>();
    for (int a = 1; a < Schema.ACTIONS; a++) {
      if (env.open(seat, a)) {
        open.add(a);
      }
    }
    return open.isEmpty() ? Schema.WAIT : open.get(random.nextInt(open.size()));
  }

  /** A replay: a fresh episode of random plays, written after a number of decisions. */
  private static String replay(int seed, int decisions) {
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.reset(new int[] {seed}, BASIC.toArray(String[]::new), OTHER.toArray(String[]::new));
      LadderEnv ladder = (LadderEnv) env.env(0);
      SplittableRandom random = new SplittableRandom(seed);
      for (int d = 0; d < decisions && !ladder.done(); d++) {
        ladder.step(randomAction(ladder, 0, random), randomAction(ladder, 1, random));
      }
      return env.replayJson(0);
    }
  }

  private static ScenarioPlan plan(String replay) throws Exception {
    return new ReplayScenario(tables).translate(MAPPER.readTree(replay));
  }

  /**
   * A decision's tick with a play given before it still to run after it, the first after a given
   * tick: a start there holds a play in flight.
   */
  private static int tickWithPlayInFlight(ScenarioPlan plan, int after) {
    for (ScenarioPlan.Play play : plan.plays()) {
      int tick = (play.givenTick() / config.decisionTicks() + 1) * config.decisionTicks();
      if (play.givenTick() >= after && tick < play.runTick()) {
        return tick;
      }
    }
    throw new AssertionError("no play in flight after tick " + after);
  }

  /** The replay's plan with only the commands given before a tick. */
  private static ScenarioPlan givenBefore(ScenarioPlan plan, int tick) {
    return new ScenarioPlan(
        plan.seed(),
        plan.towers(),
        plan.decks(),
        plan.deckLevels(),
        plan.slotFlags(),
        plan.accounts(),
        plan.arenas(),
        plan.playerDataChoices(),
        plan.plays().stream().filter(p -> p.givenTick() < tick).toList(),
        plan.abilities().stream().filter(a -> a.givenTick() < tick).toList(),
        plan.cardLevelMinimum());
  }

  private static Standard1v1Battle steppedTo(ScenarioPlan plan, int tick) {
    Standard1v1Battle battle = ReplayBattle.build(tables, plan);
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
    return battle;
  }

  private static int[] seatObservation(VectorEnv env, int index, int seat) {
    int[] out = new int[Schema.SEAT_INTS];
    env.observations()
        .asIntBuffer()
        .get((index * 2 + seat) * Schema.SEAT_INTS, out, 0, Schema.SEAT_INTS);
    return out;
  }

  private static byte[] seatMask(VectorEnv env, int index, int seat) {
    byte[] out = new byte[Schema.ACTIONS];
    env.masks().get((index * 2 + seat) * Schema.ACTIONS, out, 0, Schema.ACTIONS);
    return out;
  }

  private static int info(VectorEnv env, int index, int field) {
    return env.infos().asIntBuffer().get(index * Schema.INFO_INTS + field);
  }

  @Test
  void aStartIsTheReplaysBattleAtItsTickAsEachSeatSawIt() throws Exception {
    String replay = replay(7, 300);
    ScenarioPlan plan = plan(replay);
    int tick = tickWithPlayInFlight(plan, 400);
    ReplayLabeller.Labels labels = new ReplayLabeller(tables, config.decisionTicks()).label(replay);
    try (VectorEnv env = VectorEnv.ladder(tables, config, 2, 1)) {
      env.startFromReplay(1, replay, tick);

      LadderEnv started = (LadderEnv) env.env(1);
      assertThat(started.tick()).isEqualTo(tick);
      assertThat(info(env, 1, Schema.I_TICK)).isEqualTo(tick);
      assertThat(env.dones().get(1)).isZero();
      assertThat(BattleState.of(started.battle())).isEqualTo(BattleState.of(steppedTo(plan, tick)));
      // Each seat's view is the labeller's row at the tick: the observation and mask a seat that
      // had played the replay's commands was shown.
      int row = -1;
      for (int r = 0; r < labels.rows(); r++) {
        if (labels.ticks()[r] == tick) {
          row = r;
          break;
        }
      }
      assertThat(row).as("the labels' row at tick %d", tick).isNotNegative();
      for (int seat = 0; seat < 2; seat++) {
        assertThat(seatObservation(env, 1, seat))
            .as("seat %d's observation", seat)
            .isEqualTo(
                Arrays.copyOfRange(
                    labels.observations(),
                    (row + seat) * Schema.SEAT_INTS,
                    (row + seat + 1) * Schema.SEAT_INTS));
        assertThat(seatMask(env, 1, seat))
            .as("seat %d's mask", seat)
            .isEqualTo(
                Arrays.copyOfRange(
                    labels.masks(),
                    (row + seat) * Schema.ACTIONS,
                    (row + seat + 1) * Schema.ACTIONS));
      }
    }
  }

  @Test
  void aPlayGivenBeforeTheStartRunsAfterItAndNoLaterCommandIsGiven() throws Exception {
    String replay = replay(11, 300);
    ScenarioPlan plan = plan(replay);
    int tick = tickWithPlayInFlight(plan, 300);
    ScenarioPlan before = givenBefore(plan, tick);
    assertThat(before.plays().size()).isLessThan(plan.plays().size());
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.startFromReplay(0, replay, tick);
      LadderEnv started = (LadderEnv) env.env(0);
      Standard1v1Battle truth = ReplayBattle.build(tables, before);

      for (int d = 0; d < 80 && !started.done(); d++) {
        started.step(Schema.WAIT, Schema.WAIT);
        while (truth.getBattle().getTick() < started.tick()) {
          truth.getBattle().step();
        }
        assertThat(BattleState.of(started.battle()))
            .as("after tick %d", started.tick())
            .isEqualTo(BattleState.of(truth));
      }
      assertThat(started.battle().getPlays()).hasSameSizeAs(truth.getPlays());
      assertThat(started.battle().getCommands()).hasSize(before.plays().size());
    }
  }

  @Test
  void theSeatsPlayOnFromTheStartAndAForkOfItStepsTheSame() throws Exception {
    String replay = replay(13, 250);
    int tick = tickWithPlayInFlight(plan(replay), 200);
    try (VectorEnv env = VectorEnv.ladder(tables, config, 2, 1)) {
      env.startFromReplay(0, replay, tick);
      env.forkFrom(env, 0, -1, new int[] {1});
      LadderEnv started = (LadderEnv) env.env(0);
      LadderEnv fork = (LadderEnv) env.env(1);
      SplittableRandom random = new SplittableRandom(2);
      int given = started.battle().getCommands().size();
      for (int d = 0; d < 200 && !started.done(); d++) {
        int action0 = randomAction(started, 0, random);
        int action1 = randomAction(started, 1, random);
        started.step(action0, action1);
        fork.step(action0, action1);
        assertThat(BattleState.of(fork.battle())).isEqualTo(BattleState.of(started.battle()));
      }
      assertThat(started.battle().getCommands().size()).isGreaterThan(given);
    }
  }

  @Test
  void anEnvironmentStartedOnAReplayResetsToItsMatchUpFromTickZero() throws Exception {
    String replay = replay(17, 200);
    ScenarioPlan plan = plan(replay);
    int tick = tickWithPlayInFlight(plan, 100);
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.startFromReplay(0, replay, tick);

      env.resetEnv(0, 99);

      LadderEnv again = (LadderEnv) env.env(0);
      assertThat(again.tick()).isZero();
      assertThat(again.decks()).isEqualTo(plan.decks());
      assertThat(again.battle().getTowers()).isEqualTo(plan.raisedTowers());
      List<SideSetup> setups = again.setups();
      for (int side = 0; side < 2; side++) {
        assertThat(setups.get(side).cardLevels())
            .containsExactly(plan.raisedDeckLevels().get(side));
      }
    }
  }

  @Test
  void startsOnSeveralThreadsAreEachTheStartOnItsOwn() throws Exception {
    String first = replay(19, 250);
    String second = replay(23, 250);
    int tick0 = tickWithPlayInFlight(plan(first), 150);
    int tick1 = tickWithPlayInFlight(plan(second), 300);
    try (VectorEnv together = VectorEnv.ladder(tables, config, 3, 3);
        VectorEnv alone = VectorEnv.ladder(tables, config, 1, 1)) {
      together.startFromReplays(
          new int[] {2, 0}, new String[] {first, second}, new int[] {tick0, tick1});

      alone.startFromReplay(0, first, tick0);
      assertThat(BattleState.of(((LadderEnv) together.env(2)).battle()))
          .isEqualTo(BattleState.of(((LadderEnv) alone.env(0)).battle()));
      alone.startFromReplay(0, second, tick1);
      assertThat(BattleState.of(((LadderEnv) together.env(0)).battle()))
          .isEqualTo(BattleState.of(((LadderEnv) alone.env(0)).battle()));
      assertThat(seatObservation(together, 0, 1)).isEqualTo(seatObservation(alone, 0, 1));
    }
  }

  @Test
  void aStartThatCannotBeMadeIsRefusedAndChangesNothing() throws Exception {
    String replay = replay(29, 200);
    int end = MAPPER.readTree(replay).path("endTick").asInt();
    try (VectorEnv env = VectorEnv.ladder(tables, config, 2, 1)) {
      env.reset(new int[] {1, 2}, BASIC, OTHER);
      LadderEnv before = (LadderEnv) env.env(1);
      int[] observation = seatObservation(env, 1, 0);

      assertThatThrownBy(() -> env.startFromReplay(1, replay, config.decisionTicks() + 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("environment 1")
          .hasMessageContaining("multiple of " + config.decisionTicks());
      int past = (end / config.decisionTicks() + 1) * config.decisionTicks();
      assertThatThrownBy(() -> env.startFromReplay(1, replay, past))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("end tick");
      assertThatThrownBy(() -> env.startFromReplay(1, "{not json", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unreadable");
      // One bad start of two refuses both.
      assertThatThrownBy(
              () ->
                  env.startFromReplays(
                      new int[] {0, 1}, new String[] {replay, replay}, new int[] {0, 3}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("environment 1");
      assertThatThrownBy(
              () ->
                  env.startFromReplays(
                      new int[] {1, 1}, new String[] {replay, replay}, new int[] {0, 0}))
          .hasMessageContaining("given twice");

      assertThat(env.env(1)).isSameAs(before);
      assertThat(((LadderEnv) env.env(0)).seed()).isEqualTo(1);
      assertThat(seatObservation(env, 1, 0)).isEqualTo(observation);
    }
  }

  @Test
  void aSyntheticVectorStartsNoReplay() {
    try (VectorEnv env = VectorEnv.synthetic(1, 1, 4, 10)) {
      assertThatThrownBy(() -> env.startFromReplay(0, "{}", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no battles");
    }
  }
}
