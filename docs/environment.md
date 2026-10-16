# The Environment

The reinforcement learning environment on the battle core: a vector of Ladder battles stepped in the JVM behind flat buffers, which a Python trainer reads as arrays of the same memory. The `env` module is the Java side; the Python package that wraps it is a separate change built on this module's API.

**TL;DR:** one `VectorEnv.step()` takes both seats' actions in every battle, gives each play through the battle's own `PlayerCommands` (the client's path), steps each battle one decision (5 ticks by default) and leaves every observation, mask, reward and info record in place. A seat sees what its player sees; a fault in one battle ends that battle's episode alone.

---

## What it is for

Self-play and imitation both need the same thing from the simulator: many battles stepped in lockstep with a policy, every observation a flat array a model can read and every action an integer the battle can take. This module provides that and nothing else: no model, no training loop, no reward shaping beyond the match's result. Those belong to the trainer.

The battle core holds no code of the environment. The environment reads the observation off the core's own objects after each decision and gives plays through `PlayerCommands`, so a play is tried, moved, promised, delayed and paid for exactly as a player's is (see [When a player's command runs](battle-core.md#when-a-players-command-runs)).

## The pieces

```
env/                     org.crforge.env
  Schema                 the flat layout: header, entity rows, mask, action codec, seat frames, codes (version 1)
  Vocabulary             every card, character, building, projectile and area effect row named by number
  EnvConfig              decision interval, card and tower level, tick limit
  BattleEnv              one battle between two seats, writing into its share of the vector's buffers
  BufferedEnv            what every environment shares: the buffer views, the scratch arrays, the end by a fault
  LadderEnv              the environment on the battle core: plays, the mask, the observation, the result
  SyntheticEnv           an environment that is no battle: full-size observations from a pseudo-random source
  EnvFault               a fault's kind, message and signature
  VectorEnv              N environments stepped together, on a thread pool, through direct buffers
```

## A decision

One step of the vector is one decision in every battle:

1. The trainer writes each seat's action into `actions` (`count x 2` int32) and calls `step()`.
2. Each environment gives its two actions, one seat after the other. Commands given on the same tick run in the order they were given, so the side given first alternates: side 0 on even decisions (counted from 0 in the episode), side 1 on odd ones; `I_FIRST_SIDE` says which.
3. A wait does nothing. A play of an action the seat's mask left open is given with `PlayerCommands.playCard(side, slot, level, x, y, name)` at the tile's centre, at `EnvConfig.level`. The player's screen runs the placement search at the giving and the command carries the tile it finds, so a drop anywhere is moved to a legal tile rather than refused; the command is stamped with the tick counter and runs `PLAY_DELAY_TICKS` (20) ticks later against the battle as it stands then. An action the mask closed, out of range, or refused at its giving is taken as a wait and counted as illegal (`I_ILLEGAL_0`, `I_ILLEGAL_1`, the seat's `illegal`), with its refusal code in the seat's `last_refusal`. A giving the battle core refuses as not modelled (an `UnsupportedOperationException` from `playCard`) is refused the same way, with the code `NOT_MODELLED` and counted in `I_NOT_MODELLED`; it is not a fault.
4. The battle steps `EnvConfig.decisionTicks` times (5 by default, four decisions a second of game time; 1 decides every tick), or fewer when the match ends.
5. The environment writes both seats' observations and masks, the rewards and the info record. The step returns; everything is in the buffers.

An episode ends when the match ends: `dones[i]` is 1, the observation is the terminal one, the rewards are +1 and -1, or 0 and 0 for a draw, `H_RESULT` says which and `I_WINNER` names the winning side (2 for a draw). With autoreset on (the default) the next step resets that environment instead of stepping it, ignores its actions and reports the new episode's first observation with `I_RESET` set. `EnvConfig.maxTicks` cuts an episode short with `I_TRUNCATED` and `I_WINNER` -1.

Reproducibility: an environment's episodes draw their seeds from a sequence its reset seed starts, so a run is reproduced by its reset seeds and actions alone; the same seeds and actions give byte-identical buffers on one thread or four (`LadderEnvTest`, `SyntheticVectorEnvTest`).

## Faults

Each environment's reset and step run inside a fault boundary of their own in `VectorEnv`. An exception there ends that environment's episode alone: done, `I_TRUNCATED` 1, `I_WINNER` -1, rewards 0, both masks the wait alone, the observations cleared, and the fault's kind in `I_FAULT`. The other environments, on the same thread or another, are stepped as if nothing happened; nothing is rethrown from `step()`. The next step resets the faulted environment as it resets a finished one.

| Kind | `I_FAULT` | Source | Meaning |
|---|---|---|---|
| `refusal` | 1 | an `UnsupportedOperationException` from the battle core outside a giving (while it steps, or as the match starts) | a behaviour the core does not model: it refuses rather than guesses. A coverage gap, not a bug |
| `bug` | 2 | any other exception from the battle core | a broken invariant inside the core |
| `env_bug` | 3 | an exception in the environment's own code | the environment's mistake |

The environment wraps every call into the core in its own marker (`EnvFault.CoreFailure`), so the boundary tells the core's exceptions from its own without reading stack traces. `VectorEnv.fault(i)` gives the kind, the exception's class and message, the tick, and a signature: the kind, the exception's class, the message with its numbers replaced by `#`, and the frame that threw it. `VectorEnv.faultCounts()` counts faults by signature since the vector was made, `faultKindCounts()` by kind. A play refused at its giving or at its run is not a fault: it is a legal outcome shown to the seat.

Decks are checked before any environment is touched: eight cards, each a card row of the tables, none twice. A bad deck refuses the whole `reset` call with the environment and side named, and changes nothing.

## The schema (version 1)

Everything is a 32-bit integer in the machine's byte order, so Python reads the buffers as arrays without a copy; only the rewards are floats. The constants and their documentation are in `org.crforge.env.Schema`, and `VectorEnv.metadataJson()` exports them (below).

### The seat's frame

A seat is one player's view, in the game's own view of that player's side, as the debug visualizer draws them (`ViewOrientation`): the player's king at the bottom. The battle's coordinates have y grow from side 0 towards side 1. The game draws side 1's view with the positions as they are along the width and mirrored along the length, and side 0's view as that turned half a turn: mirrored along the width and kept along the length. So in seat 0's frame `x' = 18000 - x, y' = y`, and in seat 1's frame `x' = x, y' = 32000 - y`. In both, x grows to the right of the player's screen and y from the player's own back line towards the enemy's, and the two frames are half a turn apart, so a policy that plays either side sees the same thing. Left and right (the princess towers' order in the header) are as the seat's player sees them.

The action grid is 18 tiles across and 32 along in the seat's frame, a tile being 1000 game units, two of the routing grid's cells; a play is dropped at the tile's centre.

### The observation

`SEAT_INTS` = 2496 integers a seat: a header of 96, then 200 entity rows of 12, the first `entity_count` written.

| Header field | What it is |
|---|---|
| `tick`, `episode_step`, `seat`, `decision_ticks`, `play_delay_ticks` | the battle's tick counter, the decisions taken, the game side the seat plays, the two intervals |
| `result` | running 0, won 1, lost 2, draw 3 |
| `elixir`, `elixir_reserved` | the king's elixir in ten-thousandths; the elixir the seat's client holds back for its commands (`PlayerCommands.promised`, in ten-thousandths): each waiting command's cost, and a play refused at its run for 60 ticks from its giving |
| `crowns`, `crowns_enemy` | the crowns each side has taken |
| `hand_card` x4, `hand_cost` x4, `hand_pending` x4, `next_card`, `hand_refill_ms` | the hand by vocabulary id (-1 for an empty slot) and listed whole-elixir cost, 1 where a slot's card has a play waiting (`PlayerCommands.isPending`), the card that refills next, the refill cooldown |
| `deck` x8 | the seat's deck by vocabulary id |
| `enemy_seen` x8 | the enemy's cards in the order their plays ran and were placed, -1 beyond: what a player learns of the other deck. A Mirror's play lists the Mirror and the card it repeated |
| towers x12 | hit points and maximum of the seat's king, left and right princess tower, then the enemy's king, left and right; a fallen tower reads 0 |
| `entity_count`, `entity_overflow` | rows written, entities left out for want of rows |
| `time_ms`, `section`, `section_type`, `section_left_ms`, `full_bar_ms`, `overtime` | the match's clock: the battle time, the timeline's section and its type, the time left in it (the battle clock), the time the current elixir rate fills one bar in, 1 in overtime |
| `play_open_in_ticks` | ticks until the match takes a card play (the game mode's start cooldown); 0 from then on |
| `last_refusal` | why the seat's action of the last decision was refused (code below), 0 for a wait or a play given |
| `last_run_refusal` | the code of the seat's play refused at its run in the last decision: the match gate's code, 1000 for a placement that found no place, 0 for none |
| `illegal` | the seat's actions refused as illegal in the episode |
| `pending_count`, pending rows 4 x (`pending_card`, `pending_x`, `pending_y`, `pending_ticks`) | the seat's own commands given and not yet run, oldest first: the card, the point the command carries in the seat's frame, the ticks until it runs; unused rows are -1, 0, 0, 0. A hand slot's card has at most one play waiting, so four rows hold them all |

| Entity field | What it is |
|---|---|
| `id` | the battle's id, stable through the entity's life, the same for both seats |
| `kind` | character 1, tower 2, projectile 3, area effect 4 |
| `side` | 0 the seat's own, 1 the enemy's, 2 neither |
| `row` | the vocabulary id of the entity's row (a morphed character's current row) |
| `x`, `y` | game units in the seat's frame |
| `hp`, `max_hp`, `shield` | 0 for an entity without hit points |
| `state` | a character's state number as the battle core holds it |
| `flags` | alive, hidden, invisible, underground, air, building, king (bits 0 to 6) |

**Row order and overflow.** Towers and characters are listed first, in the battle's holder order (oldest first), then area effects and projectiles, newest first. When the 200 rows are full the oldest projectiles and area effects are left out before any character, and the newest characters only once those are all out; `entity_overflow` counts what was left out.

**What a seat is shown of the other side** is what its player sees on the screen: the entities on the field, the enemy's crowns and towers, the enemy's cards once their plays ran. Enemy characters that are hidden, invisible or underground are left out; their own side sees them. The enemy's hand, cycle, elixir and commands not yet run are not shown, and nothing of them feeds the mask. A test holds this: a seat's buffers stay byte for byte the same when the enemy holds another deck and loses its elixir every decision.

### The mask

`ACTIONS` = 2305 bytes a seat, 1 for an action the seat may take now; it is what the player's screen offers:

- the wait is always open;
- before the match's start cooldown (`LadderMatch.getStartCooldownTicks()`, from the game mode's row) nothing else is;
- after it, a hand slot is open when the client's try of its card passes (`PlayerCommands.tryCard(side, slot, level)`: the match's gates, the elixir less the promised costs, no play of the card waiting, and a Mirror with a card to repeat), and then every one of its 576 tiles is open, as a drop anywhere is moved to the tile the placement search finds. A try the core refuses as not modelled closes the slot.

The mask reads no entity, so it shows nothing of the field the seat does not see. A play the mask let through is given (the try and the giving are the same check), and runs 20 ticks later against the battle as it stands then, so it may still be refused at its run, as a player's can: `last_run_refusal` shows it, and its cost stays held back for its promise.

### The action

One integer: `0` the wait, else `1 + slot * 576 + row * 18 + col` for a play of hand slot `slot` dropped at the centre of tile `(col, row)` of the seat's frame, `col` 0 at the seat's left and `row` 0 at its own back line.

### The info record

`INFO_INTS` = 12 integers an environment: `tick`, `episode_step`, `winner` (0 or 1, 2 for a draw, -1 while running and for an episode cut short), `crowns_0`, `crowns_1`, `fault` (the kind above, 0 for none), `reset`, `illegal_0`, `illegal_1`, `truncated`, `first_side`, `not_modelled`.

### Refusal codes

`last_refusal` holds the giving's refusal as `PlayerCommands.Refusal` numbers it: 0 `NONE`, 1 `MATCH_OVER`, 2 `KING_DEAD`, 3 `NOT_IN_HAND`, 4 `START_COOLDOWN`, 5 `CARD_PENDING`, 6 `NOT_ENOUGH_ELIXIR`, 7 `NO_TILE`, then the ability refusals (8 to 15, unused while abilities are not in the action space); and two of the environment's own: 100 `MASKED` (an action the mask closed, never given) and 101 `NOT_MODELLED`. A change of the core's refusals moves the schema's version (`SchemaTest` pins the list).

### The vocabulary and the metadata

The vocabulary numbers every row name of the tables the battle reads its cards, characters, buildings, projectiles and area effects from, sorted, so a card and the character it places share the id of their one name. An id is stable within a data version only: a row another version adds moves every later id. So `VectorEnv.metadataJson()` (or `writeMetadata(path)`) is to be kept with every run and checkpoint: the schema's version, sizes, field names, flag, refusal and fault names, the configuration, and the vocabulary keyed by name (`names`, the name of each id) with the tables' `data_version`, `content_sha` and a `hash` of the list. A model trained on one export is remapped by name to another; `Vocabulary.checkSame(export)` refuses an export whose ids these tables would read differently, naming the first moved name.

## Using it

```java
GameTables tables = VectorEnv.tables(Path.of("<tables folder of a data version>")); // loaded once per process
try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 256, 8)) {
  env.writeMetadata(Path.of("run/metadata.json"));
  env.reset(seeds, deck0, deck1);           // one seed per battle; decks by card row name
  IntBuffer actions = env.actions().asIntBuffer();
  while (training) {
    // read env.observations() and env.masks(), write each seat's action
    env.step();
    // env.rewards(), env.dones(), env.infos(); env.fault(i) when infos[i * 12 + 5] != 0
  }
}
```

The buffers are direct `ByteBuffer`s in the machine's byte order, laid out environment after environment and seat after seat: `observations()` is `count x 2 x 2496` int32, `masks()` `count x 2 x 2305` bytes, `actions()` `count x 2` int32, `rewards()` `count x 2` float32, `dones()` `count` bytes, `infos()` `count x 12` int32. They are made once and valid for the vector's life, so a Python bridge maps them once and moves no bytes in a step. `reset(seeds, String[] deck0, String[] deck1)` gives each battle its own decks (eight names each, battle after battle); `resetEnv(i, seed)` resets one battle on its decks; `setAutoreset(false)` leaves finished battles for the caller. `VectorEnv.ladder(Path, ...)` takes a tables folder directly; the folder is one explicit argument, with no default.

With more than one thread the battles are stepped on a fixed pool of daemon threads, each thread taking a contiguous share; nothing is shared between battles but the read-only tables and vocabulary.

## What is not offered yet

- **Champion abilities**: a seat cannot tap an ability; the action space has none (refusal codes 8 to 15 are reserved for them).
- **Evolution and hero slots**: every deck plays with no slot flags, so no card is played evolved or in its hero form.
- **Levels**: every card is played at one level (`EnvConfig.level`, 11 by default) and every tower at `EnvConfig.towerLevel`; entity rows carry no level.
- **Inferred knowledge of the enemy**: what a player can count (the enemy's cycle, an estimate of its elixir) is not in the observation; it will come as separate fields.
- **Per-entity detail** beyond the table above (targets, deploy countdowns, buffs, velocities, projectile aims, area lifetimes), static per-row features and normalization scales.
- **A replayable fault record**: a fault is kept as its kind, message and signature, not yet as a scenario that replays it.

## Running it

```bash
./gradlew :env:test -Pcrforge.assetSource=file://$DD/cdn/            # the Ladder tests read the tables like every core test
./gradlew :env:envBenchmark -Pcrforge.assetSource=file://$DD/cdn/    # JMH, about 10 minutes
./gradlew :env:envBenchmark -Pcrforge.assetSource=file://$DD/cdn/ --args="VectorEnvBenchmark.ladder -f 1 -wi 2 -i 3"
```

`VectorEnvBenchmark` has `ladder` (Ladder battles on the tables, a mirror match of an eight-card deck) and `synthetic` (no battle), over 1, 64 and 256 environments on 1 and 4 threads. One operation is one `step()` of the vector; `:envSteps` counts environment steps and `:ticks` battle ticks. The actions are a random open play 3 decisions in 10 a seat, else the wait.

## Performance

Measured on 2026-10-16 on one Mac (Apple M4 Pro, 14 cores, JDK 17.0.14), with other work running: the 1-minute load average was 4.4 at the start and 3.9 at the end. A short run, so read the numbers as magnitudes: `./gradlew :env:envBenchmark --args="VectorEnvBenchmark -f 1 -wi 2 -i 3 -w 2 -r 2"` (one fork, 2 warm-up and 3 measured iterations of 2 s; the error is JMH's 99.9% interval). Data version 16.402.19. "env steps/s" is decisions a second over all battles; a decision is 5 ticks.

| engine | battles | threads | vector steps/s | env steps/s | ticks/s |
|---|---:|---:|---:|---:|---:|
| `ladder` | 1 | 1 | 24,148 +/- 4,827 | 24,148 | 120,539 |
| `ladder` | 64 | 1 | 337 +/- 53 | 21,547 | 107,557 |
| `ladder` | 64 | 4 | 1,045 +/- 207 | 66,885 | 333,882 |
| `ladder` | 256 | 1 | 76 +/- 206 | 19,485 | 97,244 |
| `ladder` | 256 | 4 | 264 +/- 41 | 67,661 | 337,776 |
| `synthetic` | 1 | 1 | 342,500 +/- 11,011 | 342,500 | - |
| `synthetic` | 64 | 1 | 5,231 +/- 331 | 334,753 | - |
| `synthetic` | 64 | 4 | 12,510 +/- 412 | 800,620 | - |
| `synthetic` | 256 | 1 | 1,301 +/- 26 | 333,112 | - |
| `synthetic` | 256 | 4 | 4,334 +/- 111 | 1,109,385 | - |

Read it as: one thread steps about 20,000 to 24,000 Ladder decisions a second, about 100,000 to 120,000 battle ticks, about the battle core's own rate on the recorded replays ([performance.md](performance.md)); the synthetic engine, which writes observations and masks of the same size with no battle behind them, costs about 3 us a decision, so the encoding, the masks and the tries are a small part of a Ladder decision and the battle is the rest. Four threads give about 3.1 times one. The 256-battle single-thread row's interval is wide (a slow iteration); the others are within about 20 percent.
