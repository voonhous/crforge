package org.crforge.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayBattle;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.ScenarioShape;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Measures how many battle steps a second the battle core runs, headless, on the scenarios of a
 * references folder.
 *
 * <p>Each case's scenario is translated once ({@link ReplayScenario}) and run once before anything
 * is measured: a case the battle core refuses, at translation or in a step, is left out. One
 * operation builds the battle of the next case ({@link ReplayBattle#build}) and steps it until it
 * stops or reaches the case's horizon, as a reference run steps it; nothing is observed or
 * compared. Each thread runs the cases in turn, from its own place in the list; every case is in a
 * fixed shuffled order, as an iteration covers only part of it.
 *
 * <p>The score is battles a second; the {@code ticks} counter beside it is steps a second, the
 * figure to compare, since battles differ in length. Three workloads: the replays of real battles
 * alone on one thread ({@link #replays}), every case on one thread ({@link #allCases}), and every
 * case on four threads ({@link #allCasesFourThreads}).
 *
 * <p>The references folder ({@value ReferenceSuite#PROPERTY}) and the game tables are configured as
 * for the reference tests; {@code ./gradlew :conformance:tickBenchmark} sets both.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class TickBenchmark {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A case ready to run: its translated scenario and its horizon.
   *
   * @param plan the translated scenario
   * @param horizon the most steps the case runs
   */
  record Prepared(ScenarioPlan plan, int horizon) {}

  /** The game tables and the cases, read once per fork and shared by its threads. */
  @State(Scope.Benchmark)
  public static class Cases {
    GameTables tables;
    List<Prepared> all;
    List<Prepared> replays;

    @Setup(Level.Trial)
    public void load() throws IOException {
      Path folder =
          ReferenceSuite.configuredDirectory()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "no references folder (" + ReferenceSuite.PROPERTY + ")"));
      tables = GameTables.loadConfigured();
      ReferenceSuite.References references = ReferenceSuite.load(folder);
      // The scenarios name the content they were made for: the steps are only those of the game
      // when the tables are that content's.
      if (!tables.version().equals(references.version())) {
        throw new IllegalStateException(
            "the game tables are of "
                + tables.version()
                + ", the references of "
                + references.version());
      }
      all = new ArrayList<>();
      replays = new ArrayList<>();
      int refused = 0;
      for (ReferenceSuite.Case c : references.cases()) {
        try {
          Prepared prepared = prepare(c, tables);
          all.add(prepared);
          if (c.shape() == ScenarioShape.REPLAY) {
            replays.add(prepared);
          }
        } catch (RuntimeException e) {
          refused++;
        }
      }
      // The listing is grouped by corpus, and an iteration runs only part of it: in a fixed
      // shuffled order any stretch of cases is a fair sample of them all, so iterations agree.
      Collections.shuffle(all, new Random(1));
      System.out.printf(
          "%ndata version %s: %d of %d cases run (%d refused), %d of them replays%n",
          tables.version(), all.size(), references.cases().size(), refused, replays.size());
    }
  }

  /** A thread's place in the list of cases. */
  @State(Scope.Thread)
  public static class Cursor {
    int next;

    @Setup(Level.Trial)
    public void start(ThreadParams thread) {
      // Threads start apart, so they do not all run the same battle at once.
      next = thread.getThreadIndex() * 7919;
    }

    Prepared take(List<Prepared> cases) {
      return cases.get(Math.floorMod(next++, cases.size()));
    }
  }

  /** The steps run, reported as steps a second beside the score. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Ticks {
    public long ticks;

    @Setup(Level.Iteration)
    public void reset() {
      ticks = 0;
    }
  }

  /** The replays of real battles, on one thread. */
  @Benchmark
  @Threads(1)
  public Standard1v1Battle replays(Cases cases, Cursor cursor, Ticks ticks) {
    return run(cursor.take(cases.replays), cases.tables, ticks);
  }

  /** Every case, on one thread. */
  @Benchmark
  @Threads(1)
  public Standard1v1Battle allCases(Cases cases, Cursor cursor, Ticks ticks) {
    return run(cursor.take(cases.all), cases.tables, ticks);
  }

  /**
   * Every case, on four threads: battles side by side, on few enough threads to leave a shared
   * machine's other work its cores.
   */
  @Benchmark
  @Threads(4)
  public Standard1v1Battle allCasesFourThreads(Cases cases, Cursor cursor, Ticks ticks) {
    return run(cursor.take(cases.all), cases.tables, ticks);
  }

  /**
   * Translates a case's scenario and runs it once, so a case the battle core refuses in a step is
   * known before anything is measured.
   *
   * @throws RuntimeException when the battle core refuses the case
   */
  private static Prepared prepare(ReferenceSuite.Case c, GameTables tables) {
    ScenarioPlan plan;
    try {
      byte[] bytes = Files.readAllBytes(c.reference().resolve("scenario.json"));
      plan = new ReplayScenario(tables, c.shape()).translate(MAPPER.readTree(bytes));
    } catch (IOException e) {
      throw new IllegalStateException("cannot read the scenario of " + c.key(), e);
    }
    Prepared prepared = new Prepared(plan, c.ticks());
    step(ReplayBattle.build(tables, plan), prepared.horizon());
    return prepared;
  }

  /** Builds a case's battle and steps it, counting the steps. */
  private static Standard1v1Battle run(Prepared prepared, GameTables tables, Ticks ticks) {
    Standard1v1Battle battle = ReplayBattle.build(tables, prepared.plan());
    ticks.ticks += step(battle, prepared.horizon());
    // The battle is returned, so JMH consumes it and none of its steps can be left out.
    return battle;
  }

  /**
   * Steps a battle until it stops or reaches the horizon.
   *
   * @return the steps run
   */
  private static int step(Standard1v1Battle battle, int horizon) {
    int steps = 0;
    while (steps < horizon && !battle.getBattle().getMode().isOver()) {
      battle.getBattle().step();
      steps++;
    }
    return steps;
  }
}
