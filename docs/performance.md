# Performance

How fast the battle core runs, headless: the benchmark, how to profile it, and the baseline the next change is measured against.

**TL;DR:** read `replays` first. It steps real Ladder battles from start to end, so its steps a second are the closest figure to a real game, or to an agent playing one: the production workload. `allCases` runs about 1.7 times as many steps a second only because most of its cases are short, sparse setups of one mechanic each (see [Why the replays step slower](#why-the-replays-step-slower)); it is a wide check that a change holds up across mechanics, not a speed to expect.

## The benchmark

`./gradlew :conformance:tickBenchmark -Pcrforge.references=<references folder>` runs `TickBenchmark` (`conformance/src/jmh`) with [JMH](https://github.com/openjdk/jmh). It reads the cases of a references folder as the reference tests do (the tables are the ones the build makes for `-Pcrforge.dataVersion`, which must be the references' version) and translates each case's scenario once. One operation builds the battle of the next case and steps it until it stops or reaches the case's horizon. Nothing is observed or compared, and nothing outside `core` and `conformance` is loaded: no window, no rendering. A case the battle core refuses is left out and counted.

Three workloads:

- `replays`: the replays of real battles alone (the cases whose scenario is a replay), on one thread. A whole battle with its commands, the closest to a real game.
- `allCases`: every case, replays and generated cases, on one thread. The generated cases are mostly short, targeted battles. They run in a fixed shuffled order, since an iteration covers only part of the list and the listing is grouped by corpus.
- `allCasesFourThreads`: every case on four threads: battles side by side. Four threads leave a shared machine room for its other work; more threads give at most proportionally more, as each thread shares the memory bandwidth and the garbage collector with the others (each step allocates).

Each workload runs in 3 fresh JVMs (forks), each with 3 warm-up and 5 measured iterations of 2 seconds, with JMH's allocation profiler (`-prof gc`). A full run takes about 4 minutes; the results are also written to `conformance/build/jmh/results.json`. `--args` replaces the default options with JMH's own, for example `--args="TickBenchmark.replays -f 1 -prof gc"`.

How to read the output:

| Result | Meaning |
|--------|---------|
| score (`ops/s`) | battles a second |
| `:ticks` | steps a second: the figure to compare, since battles differ in length |
| `:gc.alloc.rate.norm` | bytes allocated per battle; divided by the steps per battle, the bytes per step |
| `:gc.alloc.rate` | megabytes allocated a second |
| error | JMH's 99.9% confidence interval over the 15 measured iterations |

### Why the replays step slower

Steps a second measure the cost of one step, and a step costs more the more there is on the field. The two kinds of case have very different fields (the references of 16.402.19 on 2026-10-11, 599 cases):

| | Replays (25) | Generated cases (574) |
|---|---|---|
| What it is | a real Ladder battle, both players playing cards from the start to the end | a short setup of one mechanic: a unit walking to a tower, an ability, two units meeting |
| Commands (the plays) | median 52, mean 65 | median 3, mean 7 |
| Length | a whole battle: about 4,500 steps a battle on average in the benchmark (3 minutes 45 seconds) | median horizon 1,200 steps (60 seconds); about 1,400 steps a battle on average across `allCases` |

The expensive parts of a step are done per unit and look at the units around it: choosing targets (about a third of the step time) runs a spatial query per unit over its neighbours, preparing routes runs per moving unit, and the push and avoidance passes look at nearby pairs. A step's cost therefore grows with the units on the field, and faster than in proportion where they crowd together. A replay keeps several troops, their projectiles and spells on both sides most of the time, while a generated case is mostly the six towers and a unit or two, whose steps are cheap. The bytes allocated show the same: about 17 KB a step for the replays against about 12 KB on all the cases, as every live unit allocates its own small lists and queries each step.

So `allCases` averages many cheap steps with the replays' costly ones, and its steps a second come out higher than any real battle runs. A change that speeds up the per-unit work moves `replays` the most.

What it does not measure: the commands of a reference case are all known before the battle starts, so an agent choosing commands as the battle runs (and reading the battle to choose them) costs more than this.

Run it on a quiet machine: other processes on the same cores (a build, recordings) move the numbers far beyond the error JMH reports, the four-thread workload most of all. Compare runs on the same machine only.

## Profiling

Profile before changing anything for speed, and benchmark after: a flame graph shows where the steps spend their time and what they allocate, and the benchmark shows whether a change moved the steps a second. The benchmark runs with [async-profiler](https://github.com/async-profiler/async-profiler) through JMH (`brew install async-profiler` on macOS; on Linux the library is `libasyncProfiler.so`). One run records the CPU samples, one every millisecond, and the allocations of the replays into a JFR file:

```bash
./gradlew :conformance:tickBenchmark -Pcrforge.references=<references folder> \
  --args="TickBenchmark.replays -f 1 -prof async:libPath=$(brew --prefix async-profiler)/lib/libasyncProfiler.dylib;output=jfr;event=cpu;interval=1000000;alloc;dir=$PWD/conformance/build/profile"
```

`jfrconv`, which comes with async-profiler, turns the recording into the two flame graphs, interactive HTML pages:

```bash
J=$(find conformance/build/profile -name '*.jfr')
jfrconv --cpu "$J" conformance/build/profile/cpu.html
jfrconv --alloc "$J" conformance/build/profile/alloc.html
```
## Baseline (2026-10-11)

The baseline to measure the next change against. Source `battle_core` a606c90c with the crowns counted without walking every entity and each unit row built once per battle (the fourth optimisations in [the history](performance-history.md#the-fourth-optimisations-2026-10-11)), data version 16.402.19 with the references at the commit `crforge-data.lock` names (599 of 599 cases run, 25 of them replays), Java 17.0.14 (Temurin), Apple M4 Pro (10 performance and 4 efficiency cores), 48 GB. JMH defaults as above; score and error as JMH prints them. Load average 3.4 to 5.8 during the run, the four-thread workload's own threads included.

| Workload | Threads | ticks/s | battles/s | Allocated per battle | Allocated per tick |
|----------|---------|---------|-----------|----------------------|--------------------|
| `replays` | 1 | 104,290 +- 1,193 | 23.14 +- 0.36 | 67.2 MB | about 15 KB |
| `allCases` | 1 | 182,162 +- 5,507 | 128.66 +- 3.87 | 14.0 MB | about 10 KB |
| `allCasesFourThreads` | 4 | 704,085 +- 6,456 | 490.22 +- 5.08 | 14.5 MB | about 10 KB |

Read as:

- A real battle runs at about 104,000 steps a second on one thread, about 5,200 times the game's own 20 steps a second: a battle of about 4,500 steps in about 43 ms. The replays alone, run again after the full benchmark, gave 104,509 +- 953.
- Four threads give 3.9 times one thread.

What the profile of the replays shows at this baseline (async-profiler, as above, about 10,000 CPU samples inside the steps, load average about 4.5):

| Share of step time | Where |
|--------------------|-------|
| 28% | choosing targets (`TargetingVisit.reselect`, `SelectionChain`) |
| 16% | `SpatialIndex.query` (most of it inside the above, the rest in the push and avoidance passes): its own walk over the buckets 9% |
| 20% | preparing routes (`RoutePreparation`): the route search 13%, the scan for the cell to walk to (`ReferenceEndpoint`) 6% |
| 11% | following routes |
| 11% | the world's pre-pass (`BattleWorld.prePass`): every entity's candidates registered 5%, the spatial index rebuilt 3%, the building footprints drawn 1.5% |
| 5% | the commands, nearly all card plays (each new unit's lane 1%) |
| 4% | the holder's cleanup: the removal rounds 1.6%, and the live list sorted again by id every step, about 1% |
| 2% | the champion controller's list of champion views, built every step from a walk over every entity |

The crowns (0.2%) and the unit rows (0.2%) have left the profile. The bytes allocated are spread as before, no site above 9%: `MovementChain.mark` 8%, `BuffComponent.visit` 8%, `CharacterEntity.stateQueries` 8%, `BuffComponent.speedPercents` 7% (streams), `SpatialIndex.query` 7%, boxed integers in `ReferenceValidator.validate` 4%.

Earlier baselines and each round of optimisations, with the numbers before and after, are in [Performance history](performance-history.md). What a coverage score costs, and how to profile it, is in the conformance module's [README](../conformance/README.md#what-a-score-costs).
