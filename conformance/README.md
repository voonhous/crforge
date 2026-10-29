# conformance

Checks the battle core against recorded reference battles. Each reference battle is a scenario (a replay's decks, seed and commands) together with the game's own trace of it: one observation per tick of the RNG state, every live entity's id, side and position, each character's state and hit points, and each side's elixir and hand. This module plays the same scenario on the battle core, writes the same observations, and compares the two traces tick by tick with no tolerance.

Nothing here simulates the game itself or produces a reference. The references are fixed data in the game data repository, at the commit `crforge-data.lock` pins, under `references/<version>/`.

## How a case is checked

```mermaid
flowchart LR
  S["Scenario<br/>decks, levels, seed,<br/>commands and their ticks"]
  T["Game tables<br/>of one data version"]
  G["The game"]
  R["Reference trace<br/>one observation per tick<br/>(references/VERSION/)"]
  B["Battle core<br/>ReplaySmokeRun"]
  J["Battle core trace<br/>observations.jsonl"]
  C["ReferenceComparison<br/>tick by tick,<br/>no tolerance"]
  O["Outcome<br/>diagnostic_match, mismatch,<br/>unsupported or invalid"]
  E["reference-expectations/<br/>VERSION.json"]
  S --> G
  T --> G
  S --> B
  T --> B
  G -- "recorded once<br/>per data version" --> R
  B --> J
  R --> C
  J --> C
  C --> O
  O -- "ReferenceSuite holds it to" --> E
```

The scenario is the input and the trace is the output. Both sides play the same scenario on the same data version's tables: the reference trace is what the game did, the battle core trace is what crforge does. The first tick on which they differ names the field that differs, for example (illustrative values):

```text
tick  path                 reference  battle core
 765  $.entities[12].x       3717       3717       equal
 766  $.entities[12].x       3715       3715       equal
 767  $.entities[12].x       3713       3715       first divergence: mismatch at 767, $.entities[12].x
```

A trace belongs to the tables it was made with. When a new data version changes a column, a unit's damage for example, the game's trace of the same scenario changes too, so each data version has its own references and its own expectations file. The scenarios are reused, and the references are recorded again for the new version.

## What belongs here

- `ReplaySmokeRun`: runs one scenario on the battle core and writes `observations.jsonl`, `manifest.json` and, for a completed run, `COMPLETE`. Also the module's command line (`build/install/conformance/bin/conformance` after `installDist`).
- `SmokeObserver`, `SmokeSchema`: what one observation holds and the schemas a run can be made in.
- `ReferenceComparison`: the first-divergence comparison of a run's trace with a reference trace.
- `ReferenceSuite`: runs every case of a references folder and holds each outcome to `reference-expectations/<version>.json`.
- `TraceReader`: reads a reference battle's trace in the format it is published in, one `TraceCompression` strategy per format (today `ZstdCompression`, for `observations.jsonl.zst`; another format is one more strategy). A folder with no trace, two traces, or a file not in its named format is refused, so the case is invalid with the reason.
- `CoverageScore`: plays a coverage set (below) and sums up how much of it the battle core reproduces.

## What does not belong here

- **Reading replays.** The replay mapping and building a replay's battle are battle core features (`org.crforge.core.battle.replay`), used by this module and by the desktop replay viewer alike.
- **Mechanic tests.** A test that builds a battle and asserts one behaviour belongs in `core/src/test`, beside the tests of the code it exercises. The shared test helpers `Scenarios`, `GameData` and `Shipped` are in `core/src/testFixtures`. No unit test hard-codes game data: a mechanic test writes its own rows, a test of a shipped row reads the raw column and does its own arithmetic, and a census asserts an invariant. The rule and its helpers are under [Tests and game data](../docs/battle-core.md#tests-and-game-data).
- **Recordings of the game.** References and any other recording live in the game data repository, never in crforge.
- **Anything the battle core depends on.** Dependencies run one way: this module depends on `core`, never the reverse.
- **Tolerances or special cases.** A comparison that forgives a difference hides it. Fix the battle core, or record the case's real outcome in the expectations file.

## Outcomes and expectations

Each case ends in one of four outcomes:

- `diagnostic_match`: every observation is equal.
- `mismatch`: the first observation and field that differ, with both values.
- `unsupported`: the battle core refused an input it has no mapping for.
- `invalid`: the run faulted, or a trace or a reference failed its checks.

The expectations file records the outcome every case has today, mismatches included. The test fails when any case's outcome changes, a case that newly matches included, so every gain and every regression shows up as a reviewed diff of that file. `diagnostic_match` is agreement with a recorded battle on the observation schema, not a release pass.

## Running

```bash
# Every reference battle of one data version (skipped without a references folder)
./gradlew :conformance:referenceTest \
  -Pcrforge.dataVersion=<version> \
  -Pcrforge.references=<data checkout>/references/<version>

# Rewrite the expectations from a full run, then review the diff with the change that moved them
./gradlew :conformance:updateReferenceExpectations \
  -Pcrforge.dataVersion=<version> \
  -Pcrforge.references=<data checkout>/references/<version>
```

The tables are the ones the build makes from the asset source (the Gradle property `crforge.assetSource`, for example `file://<data checkout>/cdn/`; see [Building the game tables](../docs/game-tables.md#building-the-game-tables)), for the data version `-Pcrforge.dataVersion` names.

`./gradlew test` runs this module's own unit tests but never the reference battles. The tables and the references must be of the same data version. CI runs the reference battles of every data version the lock lists in `compatible` that has them, each version split into shards and held to its own expectations file.

## Coverage

The reference battles are chosen cases, each held to its expected outcome. Coverage asks a wider question: of the game's real battles of a data version, how many does the battle core reproduce?

A coverage set is a large sample of them, up to about 20,000 battles of one data version, each with the game's own trace, every kind of battle the battle core plays (Ladder, Path of Legends and challenges) in the one set, in the layout of a references folder (a corpus listing under `corpora/` and its `battles/`). The sets are not in this repository or the game data repository. They live in the coverage dataset, and `coverage.lock` pins its revision and lists the data versions that have a set.

`CoverageScore` runs every battle of a set as `ReferenceSuite` runs a case and writes only totals, never a battle:

- `to_the_end`: the share of battles the battle core matches on every observation;
- `ticks_agreed`: the share of all the battles' ticks it matches before each battle's first divergence (all of a match, the ticks before the first divergent observation of a mismatch, none of an unsupported or invalid battle);
- `outcomes`: the battles in each outcome;
- `first_failures`: the most frequent first failures, a refusal by its kind (the battle's own object ids and command numbers left out) and a mismatch by the field that differed first, list indices left out (`$.entities[].hp`).

```bash
# The score of one data version's coverage set, unpacked into <set folder> (corpora/ and battles/)
./gradlew :conformance:coverageScore \
  -Pcrforge.dataVersion=<version> \
  -Pcrforge.coverage=<set folder>
# -> conformance/build/coverage/score-<version>.json and badge-<version>.json

# The same, part by part: each part file's battles give a tally, and the tallies sum up to the score
./gradlew :conformance:coverageScore ... -Pcrforge.coverage.part=<K>   # -> tally-<version>-<K>.json
./gradlew :conformance:coverageMerge -Pcrforge.coverage.tallies=<folder of the tallies>
```

A set is packed in part files of up to 1,000 battles. A tally holds the counts behind a score (battles, matches, agreed and recorded ticks, outcomes and every first failure), so the tallies of all parts sum to exactly the score one run over the whole set gives.

### Scoring in CI

Coverage is measured after a merge, never on a pull request. On each push to `main` CI scores every version `coverage.lock` lists:

- one job per part file, each downloading only its part
- then one job per version that sums the tallies (and gives no score when a part is missing)

It then records each score of that commit on the `badges` branch (`history/<commit>/`). It checks the score against the scored commits next to it in `main`'s history: the nearest older one and, when the run finishes after a newer commit's, the nearest newer one. So every pair of neighbouring scored commits is compared whichever run finishes last.

A commit left unscored (a failed run) is named in a notice on the pair across it. Rerunning it closes the gap and checks both of its sides.

A run fails when:

- a version's share of battles reproduced to the end dropped between two scored commits of the same set (the error names the commit)
- a listed version got no score

So a regression or a set that cannot be scored shows as a failed run on `main`.

The scores and shields.io badges at the branch's root are always the newest scored commit's, so a late run or a rerun of an older commit never takes them back. Records are kept for `main`'s last 100 commits only. Older scores stay in the branch's history. The coverage badge in the repository's README links to its version's score at the branch's root (`score-<version>.json`), whose fields are those listed above.

A pull request only checks the lock: its revision and each listed version's set must exist. A set is known by its parts' sha256, so a lock move that keeps a version's battles keeps the comparison, and a new set starts a new one.

A first failure worth porting becomes a reference case, recorded and held to its outcome like any other.

### What a score costs

Measured on 2026-10-14. `:conformance:coverageScore` plays every battle of a coverage set and compares each with the game's own trace, tick by tick. CI scores a set in parts of 1,000 battles, one job each on a four-core CI machine.

Profile it as the battle core's benchmark is profiled (see [Profiling](../docs/performance.md#profiling)), with async-profiler as an agent of the scoring JVM instead of through JMH:

- Run `org.crforge.conformance.CoverageScore` on the runtime classpath (`:conformance:installDist`) on a small set of a few hundred battles, with:
  - `-agentpath:<libasyncProfiler>=start,event=cpu,interval=1ms,alloc,jfr,file=<file>.jfr`
  - the system properties `crforge.gameTables` and `crforge.coverage`
  - `--out-dir <dir> --threads 4`
- Then run `jfrconv` as there.

What the profile showed at 317facd4 (200 battles of 16.402.21, four threads, 3 GB heap):

| Part of the run | CPU samples |
|-----------------|-------------|
| The battle steps | 15% |
| The comparison's reading of both traces | 16% |
| The comparison's field walk | 8% |
| Writing the Java trace | 7% |
| Garbage collection | 26% |
| The compiler | 21% |

Of the 100 GB allocated, about half was the comparison (each line of both traces read as a value and all of them kept until the end, a path string per field visited) and a fifth writing the Java trace (each observation copied into maps first).

Since then:

- A reference line with the same bytes as the run's line of the same number, which is every agreeing observation as both sides are written the same way, is read once and is equal without a field walk.
- No observation is kept as a value once the checks have what they need.
- The Java trace is written from the observation directly, its keys sorted, the same bytes as before.

Every outcome is unchanged: the first divergent observation and its path of each case, and the trace of each run. Measured back to back, the same source before and after, 14 cores, load average 8.5 to 14:

| Battles of 16.402.21 | Before: wall, CPU (user + sys), peak memory | After: wall, CPU, peak memory |
|----------------------|---------------------------------------------|-------------------------------|
| 200 (two runs each) | 17.7 s, 129 s; 16.6 s, 120 s; 3.4 GB | 9.9 s, 59 s; 9.8 s, 59 s; 1.0 GB |
| 1,000 (part 0 of the set) | 81.3 s, 561 s, 3.4 GB | 41.4 s, 190 s, 1.0 GB |

What was left at 200 battles:

| Part of the run | CPU samples |
|-----------------|-------------|
| The compiler (a short run; less of a part of 1,000) | 35% |
| The battle steps | 29% |
| The SHA-256 digests of the traces (the reference's once, the Java trace's twice: when the run writes it and when the comparison checks it) | 17% |
| Reading the lines | 6% |
| Writing the Java trace | 5% |
| Garbage collection | 1% |

The tables a coverage job reads are of one data version, so it builds that one alone (`:tables:dataVersionTables`).

Since 2026-10-15:

- The comparison takes the digest the run computed when it wrote its trace instead of hashing the trace again. A reference trace of the same bytes as the run's, which is every agreeing battle, is checked against that same digest, so an agreeing battle is hashed once in all.
- The checks of a line read only its `tick` and `stopped` from the parser's tokens, through the same calls a read of the whole line makes, instead of building the line's value.

Every outcome is unchanged (each case of the 16.402.19 and 16.402.21 sets, and the references of every compatible version). Measured back to back at 552280ca, the same machine, load average 5 to 7:

| Battles of 16.402.21 | Before: wall, CPU (user + sys) | After: wall, CPU |
|----------------------|--------------------------------|------------------|
| 200 (three runs each) | 9.4 s, 56 s; 9.4 s, 57 s; 9.4 s, 55 s | 7.6 s, 49 s; 10.9 s, 56 s; 8.1 s, 48 s |
| 1,000 (part 0 of the set, two runs each) | 39.9 s, 185 s; 39.7 s, 184 s | 29.1 s, 141 s; 31.3 s, 146 s |

Of the 200 battles' CPU samples:

- the digests in the comparison fell from 10% to none
- the line reading fell from 6% to 4%
- the compiler is now 42% of a run that short, the battle steps 35%, the run's own digest of its trace 5%

The JDK 17 build on this machine does not use the processor's SHA-256 instructions (`UseSHA` is off). On a machine whose JDK uses them the digests cost less, and so does what this saves.

Limiting the compiler to its first tier (`-XX:TieredStopAtLevel=1`) cuts its share but slows the steps: part 0 then took 72 s and 288 s of CPU.

## Moving to a new data version

crforge works on one data version at a time, the lock's `version`, and tests every data version of the client range it follows, the lock's `compatible`.

1. Add the new version's tables to the game data repository and record the new version's references there (the scenarios are reused). Move `crforge-data.lock` to that commit and version, adding the version to `compatible`, and let the battle core play the new version (`GameVersions`). The unit tests read the lock's tables and hard-code no game data, so they need no edit unless the new version changes a behaviour the battle core must port, which is a change of its own.

2. If the new version's replays differ, list them in `CommandTypes` and `ReplayFormat` (in `core`). Until then its replays are refused as unsupported, never read by another version's rules.

3. Run `updateReferenceExpectations` to write `reference-expectations/<new version>.json` and review it with the change. The older versions of the same client range stay in `compatible` with their files. When a required client update starts a new range, the old range's versions leave `compatible` and their files are removed.

4. Run `./gradlew :desktop:exampleReplay` to write the example replay ([examples](../examples/README.md)) on the new version's tables, and commit it with the change: `ExampleReplayTest` fails while the shipped file is not the one the script plays on the lock's version.

See [Game tables and reference battles](../docs/game-tables.md) for the folder layout and the settings.
