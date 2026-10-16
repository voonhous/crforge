/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.nio.IntBuffer;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.crforge.core.battle.data.GameTables;
import org.openjdk.jmh.annotations.AuxCounters;
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
 * How many decisions a second the vector environment runs, headless, in the JVM alone: one
 * operation is one {@link VectorEnv#step()} over {@code count} environments on {@code threads}
 * threads, so the environment steps a second are the score times {@code count}; {@code :envSteps}
 * and {@code :ticks} count them directly. The actions are a random open play with probability 0.3 a
 * seat a decision, else the wait, drawn in the benchmark thread between steps as a trainer would
 * draw them.
 *
 * <p>{@code ladder} plays Ladder battles on the configured tables (the system property {@code
 * crforge.gameTables}). {@code synthetic} writes observations of the same size from no battle: the
 * cost of the encoding and the buffers alone, which needs no tables.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class VectorEnvBenchmark {

  /** One deck for both sides, a mirror match: every card a row of the tables. */
  private static final List<String> DECK =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  /** The counters beside the score: environment steps and battle ticks run. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Counters {
    public long envSteps;
    public long ticks;
  }

  @State(Scope.Benchmark)
  public static class Ladder {
    @Param({"1", "64", "256"})
    public int count;

    @Param({"1", "4"})
    public int threads;

    VectorEnv env;
    IntBuffer actions;
    SplittableRandom random;
    int lastTick;

    @Setup(Level.Trial)
    public void setUp() {
      GameTables tables = GameTables.loadConfigured();
      env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, count, threads);
      int[] seeds = new int[count];
      for (int i = 0; i < count; i++) {
        seeds[i] = 1000 + i;
      }
      env.reset(seeds, DECK, DECK);
      actions = env.actions().asIntBuffer();
      random = new SplittableRandom(1);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      env.close();
    }
  }

  @State(Scope.Benchmark)
  public static class Synthetic {
    @Param({"1", "64", "256"})
    public int count;

    @Param({"1", "4"})
    public int threads;

    VectorEnv env;
    IntBuffer actions;
    SplittableRandom random;

    @Setup(Level.Trial)
    public void setUp() {
      env = VectorEnv.synthetic(count, threads, 60, 900);
      int[] seeds = new int[count];
      for (int i = 0; i < count; i++) {
        seeds[i] = 1000 + i;
      }
      env.reset(seeds, DECK, DECK);
      actions = env.actions().asIntBuffer();
      random = new SplittableRandom(1);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      env.close();
    }
  }

  /** Fills the actions: a random open play with probability 0.3 a seat, else the wait. */
  static void drawActions(VectorEnv env, IntBuffer actions, SplittableRandom random) {
    int count = env.count();
    for (int i = 0; i < count * 2; i++) {
      int action = Schema.WAIT;
      if (random.nextInt(10) < 3) {
        int base = i * Schema.ACTIONS;
        // A random start, then the first open action from it: cheap and spread over the mask.
        int start = 1 + random.nextInt(Schema.ACTIONS - 1);
        for (int k = 0; k < Schema.ACTIONS - 1; k++) {
          int a = 1 + (start - 1 + k) % (Schema.ACTIONS - 1);
          if (env.masks().get(base + a) == 1) {
            action = a;
            break;
          }
        }
      }
      actions.put(i, action);
    }
  }

  @Benchmark
  public void ladder(Ladder state, Counters counters) {
    drawActions(state.env, state.actions, state.random);
    state.env.step();
    counters.envSteps += state.count;
    IntBuffer infos = state.env.infos().asIntBuffer();
    for (int i = 0; i < state.count; i++) {
      int tick = infos.get(i * Schema.INFO_INTS + Schema.I_TICK);
      int step = infos.get(i * Schema.INFO_INTS + Schema.I_EPISODE_STEP);
      // Ticks this decision ran: the interval, or fewer at an episode's end, none at a reset.
      counters.ticks += step == 0 ? 0 : Math.min(EnvConfig.DEFAULT.decisionTicks(), tick);
    }
  }

  @Benchmark
  public void synthetic(Synthetic state, Counters counters) {
    drawActions(state.env, state.actions, state.random);
    state.env.step();
    counters.envSteps += state.count;
  }
}
