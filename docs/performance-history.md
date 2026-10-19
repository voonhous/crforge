# Performance history

The battle core's earlier baselines and each round of optimisations, newest first, kept with their numbers and profiles. The benchmark, its workloads and the profiler commands are described in [Performance](performance.md), which also holds the current baseline.

## The fourth optimisations (2026-10-11)

Two more from the ranked list of the profile at a606c90c, measured back to back against `battle_core` a606c90c (599 cases of 16.402.19, 25 of them replays): the full benchmark of each source in turn, then the replays alone again in the same order. Load average 1.9 to 4.7 during the full runs, the four-thread workload's own threads included, and 2.4 to 9.2 during the replays alone (the machine was busier then).

| Source | `replays` ticks/s | `replays` again | `allCases` ticks/s | `allCasesFourThreads` ticks/s | Allocated per battle, `replays` | `allCases` |
|--------|-------------------|-----------------|--------------------|-------------------------------|---------------------------------|------------|
| before (a606c90c) | 97,498 +- 4,776 | 102,320 +- 1,156 | 175,925 +- 3,787 | 676,067 +- 5,943 | 71.7 MB | 15.4 MB |
| the crowns counted without walking every entity | 102,384 +- 1,343 (+5.0%) | 102,900 +- 1,302 (+0.6%) | 177,355 +- 4,957 (+0.8%) | 695,031 +- 7,976 (+2.8%) | 70.6 MB | 14.8 MB |
| and each unit row built once per battle | 104,290 +- 1,193 (+7.0%) | 104,509 +- 953 (+2.1%) | 182,162 +- 5,507 (+3.5%) | 704,085 +- 6,456 (+4.1%) | 67.2 MB | 14.0 MB |

- The crowns counted without walking every entity: the Ladder match remembers each side's princess towers from its first count and asks the holder whether each is still listed, a binary search by id, instead of walking the whole live list several times a step. The world remembers each side's king tower likewise. The base's first `replays` round has a wide error. Against its second the gain is within the error.
- Each unit row built once per battle: the battle's records built a unit from its row on every ask, for every card play (through the card and its champion) and every spawn. A unit is immutable, so the first build per name now stands for every later ask. Both together run above 100,000 steps a second on the replays in both rounds.

## Baseline at a606c90c (2026-10-11)

The baseline after the third optimisations, kept with its profile:

- Source `battle_core` 594d47d6, with the endpoint scan's tests skipped for a cell that cannot win and each side's king tower remembered (the third optimisations, below).
- Data version 16.402.19, with the references at the commit `crforge-data.lock` names (599 of 599 cases run, 25 of them replays).
- Java 17.0.14 (Temurin), Apple M4 Pro (10 performance and 4 efficiency cores), 48 GB.
- JMH defaults as in [The benchmark](performance.md#the-benchmark). Score and error as JMH prints them.
- Load average 3.2 to 4.6 during the run, the four-thread workload's own threads included.

| Workload | Threads | ticks/s | battles/s | Allocated per battle | Allocated per tick |
|----------|---------|---------|-----------|----------------------|--------------------|
| `replays` | 1 | 98,469 +- 4,963 | 21.86 +- 1.06 | 71.5 MB | about 16 KB |
| `allCases` | 1 | 169,260 +- 4,739 | 119.33 +- 4.70 | 15.7 MB | about 11 KB |
| `allCasesFourThreads` | 4 | 677,451 +- 6,836 | 469.51 +- 4.17 | 15.3 MB | about 11 KB |

Read as:

- A real battle runs at about 98,000 steps a second on one thread, about 4,900 times the game's own 20 steps a second: a battle of about 4,500 steps in about 46 ms.
- Four threads give 4.0 times one thread.

What the profile of the replays shows at this baseline (async-profiler, as in [Profiling](performance.md#profiling), about 10,000 CPU samples inside the steps, load average about 3.5):

| Share of step time | Where |
|--------------------|-------|
| 26% | choosing targets (`TargetingVisit.reselect`, `SelectionChain`) |
| 16% | `SpatialIndex.query` (most of it inside the above, the rest in the push and avoidance passes): its own walk over the buckets 9% |
| 19% | preparing routes (`RoutePreparation`): the route search 12%, the scan for the cell to walk to (`ReferenceEndpoint`) 6% |
| 10% | following routes |
| 10% | the world's pre-pass (`BattleWorld.prePass`): every entity's candidates registered 4%, the spatial index rebuilt 2%, the building footprints drawn 1.5% |
| 7% | the commands, nearly all card plays: a play builds its card's unit rows again (1%) and finds each new unit's lane (1%) |
| 2% | `LadderMatch.crowns`, a walk over every entity for the princess towers, several times a step |
| 2% | the champion controller's list of champion views, built every step from a walk over every entity |

The bytes allocated are spread as before, no site above 9%: `MovementChain.mark` 8%, `BuffComponent.visit` 8%, `CharacterEntity.stateQueries` 7%, `SpatialIndex.query` 7%, `BuffComponent.speedPercents` 7% (streams), boxed integers in `ReferenceValidator.validate` 4%.

## The third optimisations (2026-10-11)

Two smaller changes from the ranked list of the profile at 8c51ccfb, measured back to back against `battle_core` 594d47d6 (the baseline's code, 599 cases of 16.402.19, 25 of them replays): the full benchmark of each source in turn, then the replays alone again in the same order. Load average 3.2 to 6.2 during the full runs, the four-thread workload's own threads included, and 1.5 to 2.8 during the replays alone.

| Source | `replays` ticks/s | `replays` again | `allCases` ticks/s | `allCasesFourThreads` ticks/s | Allocated per battle, `replays` | `allCases` |
|--------|-------------------|-----------------|--------------------|-------------------------------|---------------------------------|------------|
| before (594d47d6) | 92,102 +- 1,049 | 93,504 +- 1,150 | 156,449 +- 5,440 | 615,151 +- 12,756 | 76.5 MB | 16.5 MB |
| the endpoint scan's tests skipped for a cell that cannot win | 95,417 +- 1,799 (+3.6%) | 95,060 +- 1,189 (+1.7%) | 163,173 +- 9,357 (+4.3%) | 640,381 +- 7,874 (+4.1%) | 76.1 MB | 16.4 MB |
| and each side's king tower remembered | 98,469 +- 4,963 (+6.9%) | 97,793 +- 3,579 (+4.6%) | 169,260 +- 4,739 (+8.2%) | 677,451 +- 6,836 (+10.1%) | 71.5 MB | 15.7 MB |

- The endpoint scan's tests skipped for a cell that cannot win: once the best cell of the scan for the cell a unit walks to is of the preferred rank, a cell no nearer to the unit cannot beat it. So its distance to the target and its rank are not asked. The acceptance test is still asked of every cell.
- Each side's king tower remembered: the Ladder match looked its kings up in the holder's live list several times a step (the crowns, whether a king is alive, the tiebreaker). A king tower never leaves the holder, so the first lookup is kept. It also saves each lookup's iterator: the replays allocated 76.1 MB a battle before it and 71.5 MB after, in the first round (80.7 and 73.2 MB in the second). The spread from run to run is about 4%.

## Baseline at 287b0c7d (2026-10-11)

The baseline after the second optimisations, kept with its profile:

- Source `battle_core` 8c51ccfb, with the slot marks of the spatial index and the route search's cells priced as it reaches them (the second optimisations, below).
- Data version 16.402.19, with the references at the commit `crforge-data.lock` names (544 of 544 cases run, 24 of them replays).
- Java 17.0.14 (Temurin), Apple M4 Pro (10 performance and 4 efficiency cores), 48 GB.
- JMH defaults as in [The benchmark](performance.md#the-benchmark). Score and error as JMH prints them.
- Load average 4.1 to 6.3 during the run, the four-thread workload's own threads included.

| Workload | Threads | ticks/s | battles/s | Allocated per battle | Allocated per tick |
|----------|---------|---------|-----------|----------------------|--------------------|
| `replays` | 1 | 92,834 +- 1,086 | 20.40 +- 0.30 | 77.9 MB | about 17 KB |
| `allCases` | 1 | 158,199 +- 4,812 | 108.33 +- 4.15 | 17.0 MB | about 12 KB |
| `allCasesFourThreads` | 4 | 624,528 +- 11,867 | 422.89 +- 10.50 | 17.2 MB | about 12 KB |

Read as:

- A real battle runs at about 93,000 steps a second on one thread, about 4,600 times the game's own 20 steps a second: a battle of about 4,550 steps in about 49 ms.
- Four threads give 3.9 times one thread.

What the profile of the replays showed at 8c51ccfb, just before the second optimisations (async-profiler, as in [Profiling](performance.md#profiling), about 20,000 CPU samples inside the steps, on a busy machine: the shares hold, the speed does not):

| Share of step time | Where |
|--------------------|-------|
| 31% | choosing targets (`TargetingVisit.reselect`, `SelectionChain`) |
| 17% | `SpatialIndex.query` (inside the above, and the push and avoidance passes): 7% of it the look through its result for an entity already accepted, the walk over the buckets cheap since #482 |
| 22% | preparing routes (`RoutePreparation`): the route search 9%, the cost field priced for the whole arena before each search 5%, the scan for the cell to walk to (`ReferenceEndpoint`) 7% |
| 10% | following routes |

The bytes allocated are spread as at 2afbb93d: no site above 8%.

## The second optimisations (2026-10-11)

The changes the profile at 8c51ccfb pointed to, measured back to back against 8c51ccfb on the same references (544 cases): the full benchmark of each source in turn, then the replays alone again in the same order. Load average 2.4 to 8.0, the four-thread workload's own threads included.

| Source | `replays` ticks/s | `replays` again | `allCases` ticks/s | `allCasesFourThreads` ticks/s | Allocated per battle, `replays` | `allCases` |
|--------|-------------------|-----------------|--------------------|-------------------------------|---------------------------------|------------|
| before (8c51ccfb) | 81,692 +- 1,362 | 83,219 +- 1,069 | 145,533 +- 8,347 | 559,708 +- 15,915 | 78.3 MB | 17.0 MB |
| slot marks in the spatial index | 88,286 +- 1,451 (+8.1%) | 90,217 +- 923 (+8.4%) | 155,654 +- 4,408 (+7.0%) | 606,776 +- 9,557 (+8.4%) | 81.9 MB | 16.9 MB |
| and the cells priced as the search reaches them | 92,834 +- 1,086 (+13.6%) | 93,712 +- 1,060 (+12.6%) | 158,199 +- 4,812 (+8.7%) | 624,528 +- 11,867 (+11.6%) | 77.9 MB | 17.0 MB |

- Slot marks in the spatial index: each entity put in the index takes a slot and the buckets hold slots. A query marks the slots it has accepted in a word per 64 slots instead of looking through its result for every entry it meets.
- The cells priced as the search reaches them: the route search prices a cell from the cost lookup the first time it reads it, instead of all 2304 cells of the arena before every search. On top of the slot marks it adds about 4 to 5% on the replays. On all the cases, whose battles are mostly short, it adds less than its error.

The bytes allocated did not move beyond the spread from run to run (about 4%): the changes save time, not objects.

## Baseline at 2afbb93d (2026-10-10)

The baseline after the first optimisations, kept with its profile:

- Source `battle_core` 2afbb93d.
- Data version 16.402.19, with the references at the commit `crforge-data.lock` names (459 of 459 cases run, 24 of them replays).
- Java 17.0.14 (Temurin), Apple M4 Pro (10 performance and 4 efficiency cores), 48 GB.
- JMH defaults as in [The benchmark](performance.md#the-benchmark). Score and error as JMH prints them.
- Load average 2.3 to 4.1 during the run.

| Workload | Threads | ticks/s | battles/s | Allocated per battle | Allocated per tick |
|----------|---------|---------|-----------|----------------------|--------------------|
| `replays` | 1 | 74,079 +- 1,058 | 16.30 +- 0.34 | 80.4 MB | about 18 KB |
| `allCases` | 1 | 123,433 +- 7,653 | 81.46 +- 8.86 | 18.1 MB | about 12 KB |
| `allCasesFourThreads` | 4 | 494,770 +- 10,458 | 320.26 +- 6.24 | 17.8 MB | about 12 KB |

Read as:

- A real battle runs at about 74,000 steps a second on one thread, about 3,700 times the game's own 20 steps a second: a battle of about 4,550 steps in about 61 ms.
- Four threads give 4.0 times one thread: linear.
- The collector costs 48 ms over the 30 measured seconds of `replays` (0.16%).

What the profile of the replays shows (async-profiler, as in [Profiling](performance.md#profiling), about 10,000 CPU samples inside the steps):

| Share of step time | Where |
|--------------------|-------|
| 34% | choosing targets (`TargetingVisit.reselect`, `SelectionChain`), most of it in `SpatialIndex.query` |
| 26% | `SpatialIndex.query` (inside the above): mostly its own walk over the buckets (14%) and the look through its result for an entity already accepted (5%) |
| 21% | preparing routes (`RoutePreparation`), 8% of it the route search |
| 8% | following routes |

Of the bytes allocated, 98% are allocated in the steps, and no one site holds more than 8% of them: small lists, their iterators, boxed integers, lambdas and streams across the step (`MovementChain.mark` 7.5%, `CharacterEntity`'s lambdas 5.2%, `SpatialIndex.query`'s result lists 4.5%, boxed integers in `ReferenceValidator.validate` 4.3%, `BuffComponent.visit`'s lists 4.3%).

The flame graphs of that profile, cut to `Battle.step` (the benchmark's own frames left out, classes without their packages) and drawn root first: the width of a frame is its share of the step time or of the bytes allocated. The interactive pages come from the commands in [Profiling](performance.md#profiling).

CPU:

![CPU flame graph of Battle.step at 2afbb93d](assets/performance-2afbb93d-cpu.png)

Allocated bytes:

![Allocation flame graph of Battle.step at 2afbb93d](assets/performance-2afbb93d-alloc.png)

## The first optimisations (#478, #479, #480)

The three changes the first profile pointed to, each measured on its own and all three together, back to back against the source before them (81565a2e) on the same references (459 cases), single-thread workloads, load average 2.2 to 7.3:

| Source | `replays` ticks/s | `allCases` ticks/s | Allocated per battle, `replays` | `allCases` |
|--------|-------------------|--------------------|---------------------------------|------------|
| before (81565a2e) | 60,360 +- 1,956 | 95,087 +- 6,428 | 181.2 MB | 40.5 MB |
| #478: the routing overlay's two arrays rotated | 60,959 +- 2,095 | 93,659 +- 6,088 | 140.8 MB (-22%) | 26.3 MB (-35%) |
| #479: spatial queries without a set or iterators | 72,538 +- 1,004 (+20%) | 120,746 +- 8,139 (+27%) | 158.7 MB (-12%) | 36.5 MB (-10%) |
| #480: the route search's arrays kept | 59,814 +- 1,220 | 94,183 +- 6,045 | 144.1 MB (-20%) | 35.7 MB (-12%) |
| all three (2afbb93d) | 72,699 +- 1,133 (+20%) | 124,019 +- 6,959 (+30%) | 79.7 MB (-56%) | 17.8 MB (-56%) |

The speed came from the spatial queries. The other two cut the bytes allocated but not the time on one thread, where the collector was already cheap. Together they more than halved the bytes. On four threads the baseline above runs 494,770 steps a second against 370,143 in the first baseline below (+34%, on a slightly different set of references).

## First baseline (2026-10-10, 71b09ba6)

The baseline before the first optimisations, kept with its profile as the picture they started from.

- Source `battle_core` 71b09ba6 (the battle core as it was; the benchmark itself came after).
- Data version 16.402.19, on an earlier set of its references (450 of 451 cases run, 1 refused, 23 replays).
- Java 17.0.14 (Temurin), Apple M4 Pro (10 performance and 4 efficiency cores), 48 GB.
- JMH defaults as in [The benchmark](performance.md#the-benchmark). Score and error as JMH prints them.

The four-thread workload was run again on its own, as the machine was busy during its first run.

| Workload | Threads | ticks/s | battles/s | Allocated per battle | Allocated per tick |
|----------|---------|---------|-----------|----------------------|--------------------|
| `replays` | 1 | 58,622 +- 2,695 | 12.85 +- 0.60 | 185.8 MB | about 41 KB |
| `allCases` | 1 | 88,772 +- 3,770 | 59.90 +- 2.74 | 40.2 MB | about 27 KB |
| `allCasesFourThreads` | 4 | 370,143 +- 9,901 | 241.44 +- 8.49 | 40.8 MB | about 27 KB |

Read as:

- A real battle runs at about 58,600 steps a second on one thread, about 2,900 times the game's own 20 steps a second: a battle of about 4,560 steps in about 78 ms.
- Four threads give about 4.2 times one thread: linear within the error, so at four threads the threads do not yet hold each other back (9.2 GB allocated a second between them).
- Each step allocates tens of kilobytes, about 2.3 GB a second on one thread. The collector itself costs little (106 ms of collection over the 30 measured seconds of `replays`, under 0.5%): the cost is in making and filling the objects.

What the profile of the replays showed then (async-profiler, as in [Profiling](performance.md#profiling), about 9,600 CPU samples inside the steps):

| Share of step time | Where |
|--------------------|-------|
| 35% | choosing targets (`TargetingVisit.reselect`, `SelectionChain`), most of it in `SpatialIndex.query` |
| 28% | `SpatialIndex.query` (inside the above): a new result list and identity set per query, and the iteration over the buckets |
| 18% | preparing routes (`RoutePreparation`), 12% of it the route search |
| 8% | following routes |

Of the bytes allocated, 99% are allocated in the steps (building the battle is under 1%). The largest sites:

- the routing overlay's fresh, zeroed array at the end of every step (`CellGrid.swap`, from `BattleWorld.postPass`: about 23%)
- the route search's arrays, new for every search (`RouteSearch$Run`, `CellCostField.costField`: about 22%)
- `SpatialIndex.query`'s result lists and identity sets (about 15%)

Its flame graphs, cut to `Battle.step` (the benchmark's own frames left out, classes without their packages) and drawn root first: the width of a frame is its share of the step time or of the bytes allocated. The interactive pages come from the commands in [Profiling](performance.md#profiling).

CPU:

![CPU flame graph of Battle.step at the baseline](assets/performance-71b09ba6-cpu.png)

Allocated bytes:

![Allocation flame graph of Battle.step at the baseline](assets/performance-71b09ba6-alloc.png)
