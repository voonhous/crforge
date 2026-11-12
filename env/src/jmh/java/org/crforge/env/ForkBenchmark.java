/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.nio.IntBuffer;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.crforge.core.battle.data.GameTables;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What one fork of a Ladder battle costs ({@link LadderEnv#fork}), headless, in the JVM alone: one
 * operation is one fork of a battle that stands still, so the score is the microseconds a fork
 * takes on one thread, and {@code gc.alloc.rate.norm} the bytes it allocates.
 *
 * <p>The battle is played once, before the measurement, to the tick {@code tick} names, with the
 * actions of {@link VectorEnvBenchmark} (a random open play with probability 0.3 a seat a decision,
 * else the wait) from a fixed seed, so every run forks the same battles. An older battle has had
 * more plays, and a fork copies what is on the field.
 *
 * <p>{@code seat} is the seat whose knowledge the fork keeps: -1 is the battle as it is, the copy
 * alone; 0 also withdraws the enemy's commands still waiting to run.
 *
 * <p>The tables are the configured ones (the system property {@code crforge.gameTables}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ForkBenchmark {

  @State(Scope.Benchmark)
  public static class Paused {
    /** The battle's age at the fork, in ticks: 20 a second, so 25, 75 and 150 seconds. */
    @Param({"500", "1500", "3000"})
    public int tick;

    @Param({"-1", "0"})
    public int seat;

    VectorEnv vector;
    LadderEnv env;

    @Setup(Level.Trial)
    public void setUp() {
      vector = playedTo(GameTables.loadConfigured(), tick);
      env = (LadderEnv) vector.env(0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      vector.close();
    }
  }

  /**
   * A vector of one Ladder battle played to a tick with random plays from a fixed seed, and left
   * there, not done.
   *
   * @param tables the game tables
   * @param untilTick the tick to stop at or just after, at a decision
   * @return the vector, to close
   * @throws IllegalStateException when the battle ends before the tick
   */
  static VectorEnv playedTo(GameTables tables, int untilTick) {
    VectorEnv vector = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1);
    vector.setAutoreset(false);
    vector.reset(new int[] {1000}, VectorEnvBenchmark.DECK, VectorEnvBenchmark.DECK);
    IntBuffer actions = vector.actions().asIntBuffer();
    SplittableRandom random = new SplittableRandom(1);
    LadderEnv env = (LadderEnv) vector.env(0);
    while (env.tick() < untilTick) {
      if (env.done()) {
        vector.close();
        throw new IllegalStateException(
            "the battle ended at tick " + env.tick() + ", before tick " + untilTick);
      }
      VectorEnvBenchmark.drawActions(vector, actions, random);
      vector.step();
    }
    return vector;
  }

  @Benchmark
  public LadderEnv fork(Paused state) {
    return state.env.fork(state.seat);
  }
}
