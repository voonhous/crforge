/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle.Command;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A Ladder environment's fork and the vector's look ahead from it. */
class LookaheadTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  private static VectorEnv ladder(int count, int threads) {
    VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, count, threads);
    int[] seeds = new int[count];
    for (int i = 0; i < count; i++) {
      seeds[i] = 300 + i;
    }
    env.reset(seeds, BASIC, OTHER);
    return env;
  }

  private static LadderEnv ladderEnv(VectorEnv env, int index) {
    return (LadderEnv) env.env(index);
  }

  /** A random open play of a seat of an environment, three decisions in ten, else the wait. */
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

  /** Steps an environment of a vector some decisions with random plays of both seats. */
  private static void play(VectorEnv env, int index, int decisions, SplittableRandom random) {
    LadderEnv ladder = ladderEnv(env, index);
    for (int d = 0; d < decisions && !ladder.done(); d++) {
      ladder.step(randomAction(ladder, 0, random), randomAction(ladder, 1, random));
    }
  }

  /** Steps an environment with waits until a seat has a play open, and returns its first. */
  private static int openPlay(LadderEnv env, int seat) {
    while (!env.done()) {
      for (int a = 1; a < Schema.ACTIONS; a++) {
        if (!Schema.isAbility(a) && env.open(seat, a)) {
          return a;
        }
      }
      env.step(Schema.WAIT, Schema.WAIT);
    }
    throw new AssertionError("the battle ended before seat " + seat + " could play");
  }

  /** Both seats' observations and masks and the info record of an environment, as written. */
  private static int[] written(LadderEnv env) {
    int[] all = new int[2 * Schema.SEAT_INTS + 2 * Schema.ACTIONS + Schema.INFO_INTS];
    env.copyObservation(0, all, 0);
    env.copyObservation(1, all, Schema.SEAT_INTS);
    for (int seat = 0; seat < 2; seat++) {
      for (int a = 0; a < Schema.ACTIONS; a++) {
        all[2 * Schema.SEAT_INTS + seat * Schema.ACTIONS + a] = env.open(seat, a) ? 1 : 0;
      }
    }
    env.copyInfo(all, 2 * Schema.SEAT_INTS + 2 * Schema.ACTIONS);
    return all;
  }

  private static byte[] all(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.capacity()];
    buffer.get(0, bytes);
    return bytes;
  }

  @Test
  void aForkOfTheBattleAsItIsPlaysTheSameBattleToItsEnd() {
    try (VectorEnv env = ladder(1, 1)) {
      SplittableRandom random = new SplittableRandom(7);
      play(env, 0, 400, random);
      LadderEnv original = ladderEnv(env, 0);
      assertThat(original.done()).isFalse();
      LadderEnv fork = original.fork(-1);
      assertThat(fork.tick()).isEqualTo(original.tick());
      assertThat(written(fork)).isEqualTo(written(original));
      while (!original.done()) {
        int action0 = randomAction(original, 0, random);
        int action1 = randomAction(original, 1, random);
        original.step(action0, action1);
        fork.step(action0, action1);
        assertThat(written(fork)).isEqualTo(written(original));
      }
      assertThat(fork.done()).isTrue();
    }
  }

  @Test
  void aSeatsForkLeavesOutTheEnemyCommandsTheSeatHasNotSeenRun() {
    try (VectorEnv env = ladder(1, 1)) {
      LadderEnv original = ladderEnv(env, 0);
      SplittableRandom random = new SplittableRandom(11);
      play(env, 0, 200, random);
      // Seat 1 plays, seat 0 waits: the play is given and waits its delay, unseen by seat 0.
      int play = openPlay(original, 1);
      int before = original.battle().getCommands().size();
      original.step(Schema.WAIT, play);
      List<Command> given = original.battle().getCommands();
      assertThat(given).hasSize(before + 1);
      assertThat(given.get(before).tick()).isGreaterThan(original.tick());

      LadderEnv mine = original.fork(0);
      assertThat(mine.battle().getCommands()).hasSize(before);
      LadderEnv theirs = original.fork(1);
      assertThat(theirs.battle().getCommands()).hasSize(before + 1);

      // Once it has run, seat 0 has seen it, and its fork gives it too.
      while (original.tick() <= given.get(before).tick()) {
        original.step(Schema.WAIT, Schema.WAIT);
      }
      assertThat(original.fork(0).battle().getCommands()).hasSize(before + 1);
    }
  }

  @Test
  void theLookAheadStepsForksAndLeavesTheVectorAsItWas() {
    try (VectorEnv env = ladder(2, 2)) {
      for (int i = 0; i < 2; i++) {
        play(env, i, 300, new SplittableRandom(20 + i));
      }
      LadderEnv first = ladderEnv(env, 0);
      int open = openPlay(first, 0);
      byte[] observations = all(env.observations());
      byte[] masks = all(env.masks());
      byte[] infos = all(env.infos());
      int[] indices = {0, 0, 1, 1};
      int[] seats = {0, 0, 1, 0};
      int[] actions = {Schema.WAIT, open, Schema.WAIT, Schema.WAIT};
      int horizon = 160;
      int[] out = env.lookahead(indices, seats, actions, horizon);
      assertThat(env.lookahead(indices, seats, actions, horizon)).isEqualTo(out);
      assertThat(all(env.observations())).isEqualTo(observations);
      assertThat(all(env.masks())).isEqualTo(masks);
      assertThat(all(env.infos())).isEqualTo(infos);

      int stride = Schema.SEAT_INTS + Schema.INFO_INTS;
      // The wait's request is its seat's fork stepped with waits over the horizon.
      LadderEnv fork = first.fork(0);
      int until = fork.tick() + horizon;
      while (!fork.done() && fork.tick() < until) {
        fork.step(Schema.WAIT, Schema.WAIT);
      }
      int[] seat0 = new int[Schema.SEAT_INTS];
      fork.copyObservation(0, seat0, 0);
      assertThat(Arrays.copyOfRange(out, 0, Schema.SEAT_INTS)).isEqualTo(seat0);
      assertThat(out[Schema.SEAT_INTS + Schema.I_TICK]).isGreaterThanOrEqualTo(until);
      assertThat(out[Schema.SEAT_INTS + Schema.I_FAULT]).isEqualTo(Schema.FAULT_NONE);
      // A play looks ahead to another battle than the wait.
      assertThat(Arrays.copyOfRange(out, stride, stride + Schema.SEAT_INTS))
          .isNotEqualTo(Arrays.copyOfRange(out, 0, Schema.SEAT_INTS));
      // Each seat's request is written in its own frame.
      assertThat(out[2 * stride + Schema.H_SEAT]).isEqualTo(1);
      assertThat(out[3 * stride + Schema.H_SEAT]).isZero();
    }
  }

  @Test
  void onlyARunningLadderEpisodeIsForked() {
    try (VectorEnv env = ladder(1, 1)) {
      LadderEnv ladder = ladderEnv(env, 0);
      assertThatThrownBy(() -> ladder.fork(2)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> env.lookahead(new int[] {0}, new int[] {0}, new int[0], 160))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> env.lookahead(new int[] {1}, new int[] {0}, new int[] {0}, 160))
          .isInstanceOf(IllegalArgumentException.class);
      while (!ladder.done()) {
        ladder.step(Schema.WAIT, Schema.WAIT);
      }
      assertThatThrownBy(() -> ladder.fork(0)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> env.lookahead(new int[] {0}, new int[] {0}, new int[] {0}, 160))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
