# The Environment

The reinforcement learning environment on the battle core: a vector of Ladder battles stepped in the JVM behind flat buffers, which a Python trainer reads as arrays of the same memory. The `env` module is the Java side; the `crforge-env` package under `python/` is the Python side ([Python](#python)).

**TL;DR:** one `VectorEnv.step()` takes both seats' actions in every battle, gives each play through the battle's own `PlayerCommands` (the client's path), steps each battle one decision (5 ticks by default) and leaves every observation, mask, reward and info record in place. A seat sees what its player sees; a fault in one battle ends that battle's episode alone.

---

## What it is for

Self-play and imitation both need the same thing from the simulator: many battles stepped in lockstep with a policy, every observation a flat array a model can read and every action an integer the battle can take. This module provides that and nothing else: no model, no training loop, no reward shaping beyond the match's result. Those belong to the trainer.

The battle core holds no code of the environment. The environment reads the observation off the core's own objects after each decision and gives plays through `PlayerCommands`, so a play is tried, moved, promised, delayed and paid for exactly as a player's is (see [When a player's command runs](battle-core.md#when-a-players-command-runs)).

## The pieces

```
env/                     org.crforge.env
  Schema                 the flat layout: header, entity rows, mask, action codec, seat frames, codes (version 2)
  Vocabulary             every card, character, building, projectile and area effect row named by number
  EnvConfig              decision interval, card and tower level, tick limit, fault bundle folder
  BattleEnv              one battle between two seats, writing into its share of the vector's buffers
  BufferedEnv            what every environment shares: the buffer views, the scratch arrays, the end by a fault
  LadderEnv              the environment on the battle core: plays, the mask, the observation, the result
  SyntheticEnv           an environment that is no battle: full-size observations from a pseudo-random source
  EnvFault               a fault's kind, message, signature and bundle folder
  FaultBundle            a faulted episode written as a replay and fault.json
  VectorEnv              N environments stepped together, on a thread pool, through direct buffers
  ReplayLabeller         a replay labelled for behavioural training: each seat's rows and the action of its command
  ReplayFiles            the replays of a file: a replay document, or a crawl's JSON Lines output (plain, zstd, gzip)

python/                  crforge_env (the crforge-env package)
  VectorBattle           the vector from Python: its buffers as arrays of the same memory
  ClashParallelEnv       one battle, both seats as the agents of a parallel multi-agent environment
  ClashGymEnv            one seat of a battle as a single-agent environment, the other seat a given policy
  Schema                 the layout, read from the vector's metadata at start
  ScriptedBot            a scripted opponent of a few readable rules on what its player sees
  evaluate               a policy against an opponent over many episodes, as wins, losses and draws
  label_replays          replays labelled for behavioural training, as numpy arrays per replay
  bench                  the Python-side benchmark (crforge-env-bench)
```

## A decision

One step of the vector is one decision in every battle:

1. The trainer writes each seat's action into `actions` (`count x 2` int32) and calls `step()`.
2. Each environment gives its two actions, one seat after the other. Commands given on the same tick run in the order they were given, so the side given first alternates: side 0 on even decisions (counted from 0 in the episode), side 1 on odd ones; `I_FIRST_SIDE` says which.
3. A wait does nothing. A play of an action the seat's mask left open is given with `PlayerCommands.playCard(side, slot, level, x, y, name)` at the tile's centre, at `EnvConfig.level`. The player's screen runs the placement search at the giving and the command carries the tile it finds, so a drop anywhere is moved to a legal tile rather than refused; the command is stamped with the tick counter and runs `PLAY_DELAY_TICKS` (20) ticks later against the battle as it stands then. An action the mask closed, out of range, or refused at its giving is taken as a wait and counted as illegal (`I_ILLEGAL_0`, `I_ILLEGAL_1`, the seat's `illegal`), with its refusal code in the seat's `last_refusal`. An ability action the mask left open is given with `PlayerCommands.useAbility(side, unit, name)`, naming the unit of the play the champion slot follows by the name the battle gave it (below); it is stamped and runs 20 ticks later the same way. A giving the battle core refuses as not modelled (an `UnsupportedOperationException` from `playCard` or `useAbility`) is refused the same way, with the code `NOT_MODELLED` and counted in `I_NOT_MODELLED`; it is not a fault.
4. The battle steps `EnvConfig.decisionTicks` times (5 by default, four decisions a second of game time; 1 decides every tick), or fewer when the match ends.
5. The environment writes both seats' observations and masks, the rewards and the info record. The step returns; everything is in the buffers.

An episode ends when the match ends: `dones[i]` is 1, the observation is the terminal one, the rewards are +1 and -1, or 0 and 0 for a draw, `H_RESULT` says which and `I_WINNER` names the winning side (2 for a draw). With autoreset on (the default) the next step resets that environment instead of stepping it, ignores its actions and reports the new episode's first observation with `I_RESET` set. `EnvConfig.maxTicks` cuts an episode short with `I_TRUNCATED` and `I_WINNER` -1.

Reproducibility: an environment's episodes draw their seeds from a sequence its reset seed starts, so a run is reproduced by its reset seeds and actions alone; the same seeds and actions give byte-identical buffers on one thread or four (`LadderEnvTest`, `SyntheticVectorEnvTest`). A battle is set up as the game sets one up: each player's data is handed over (one choice each, `Standard1v1Battle.addPlayerData`) before the decks are dealt with both players' words 0, so an episode is the battle a replay of it gives ([Fault bundles](#fault-bundles)). Each play's command is named `cmd<n>` after its place among the battle's commands, as a replay names it.

## Faults

Each environment's reset and step run inside a fault boundary of their own in `VectorEnv`. An exception there ends that environment's episode alone: done, `I_TRUNCATED` 1, `I_WINNER` -1, rewards 0, both masks the wait alone, the observations cleared, and the fault's kind in `I_FAULT`. The other environments, on the same thread or another, are stepped as if nothing happened; nothing is rethrown from `step()`. The next step resets the faulted environment as it resets a finished one.

| Kind | `I_FAULT` | Source | Meaning |
|---|---|---|---|
| `refusal` | 1 | an `UnsupportedOperationException` from the battle core outside a giving (while it steps, or as the match starts) | a behaviour the core does not model: it refuses rather than guesses. A coverage gap, not a bug |
| `bug` | 2 | any other exception from the battle core | a broken invariant inside the core |
| `env_bug` | 3 | an exception in the environment's own code | the environment's mistake |

The environment wraps every call into the core in its own marker (`EnvFault.CoreFailure`), so the boundary tells the core's exceptions from its own without reading stack traces. `VectorEnv.fault(i)` gives the kind, the exception's class and message, the tick, and a signature: the kind, the exception's class, the message with its numbers replaced by `#`, and the frame that threw it. `VectorEnv.faultCounts()` counts faults by signature since the vector was made, `faultKindCounts()` by kind. A play refused at its giving or at its run is not a fault: it is a legal outcome shown to the seat.

Decks are checked before any environment is touched: eight cards, each a card row of the tables, none twice. A bad deck refuses the whole `reset` call with the environment and side named, and changes nothing.

### Fault bundles

With `EnvConfig.faultBundles` naming a folder (`EnvConfig.DEFAULT.withFaultBundles("<folder>")`; off by default), the vector writes a faulted Ladder episode to a folder of its own under it, `<kind>-<signature hash>-env<i>-tick<t>`, and `VectorEnv.fault(i).bundle()` names that folder. Only the first fault of each signature the vector meets is written: the others are counted (`faultCounts()`), so a fault met every episode does not fill the disk. The bundle is written inside the fault boundary, after the episode is ended; a bundle that cannot be written is left out with the reason on the error stream, and the vector goes on as for any fault. A synthetic environment plays no battle and writes none.

| File | What it holds |
|---|---|
| `replay.json` | the episode's battle as a replay of the tables' data version (`org.crforge.core.battle.replay.ScenarioWriter`): the seed, the decks with their levels and slot flags, the towers, each player's data, every command the seats gave with the tick it was given on and the tick it runs on and the item its client built, and a capture block naming the tables' client version, data version and content sha. Its `endTick` is the tick after the faulting step. Left out (`replay` null, `replay_refusal` the reason) when the battle cannot be written, for one when no battle was built |
| `fault.json` | `kind`, `signature`, `exception`, `message`, `stack` (the full stack trace), `data_version`, `content_sha`, `schema_version`, `config` (decision ticks, levels, tick limit), `env_index`, `seed` (the episode's battle seed), `decks`, `slots` (each deck's slot flags), `tick`, `episode_step`, `actions` (both seats' actions of every decision of the episode, the faulting one last), `commands` (every command given to the battle, plays and ability commands: name, kind, side, card, level, given and run tick, point, option, and an ability command's unit and object id), `replay` |

Read back and stepped, the replay meets the same fault at the same tick, with the same message and signature: `ReplayScenario` translates it and `ReplayBattle.build` builds its battle, which a step past the fault's tick refuses as the episode did (`FaultBundleTest`). The debug visualizer opens it as any replay (`./gradlew :desktop:run --args="--replay <bundle>/replay.json"`) and stops at its `endTick`. A play given and not yet run when the battle faulted carries the item its side builds at the fault, which the replay checks only if it runs that far.

Bundles are local files of the machine that runs the environment, a run's own data: they are never committed. Write them outside the repository, or under `fault-bundles/`, which `.gitignore` lists.

## The schema (version 2)

Everything is a 32-bit integer in the machine's byte order, so Python reads the buffers as arrays without a copy; only the rewards are floats. The constants and their documentation are in `org.crforge.env.Schema`, and `VectorEnv.metadataJson()` exports them (below).

### The seat's frame

A seat is one player's view, in the game's own view of that player's side, as the debug visualizer draws them (`ViewOrientation`): the player's king at the bottom. The battle's coordinates have y grow from side 0 towards side 1. The game draws side 1's view with the positions as they are along the width and mirrored along the length, and side 0's view as that turned half a turn: mirrored along the width and kept along the length. So in seat 0's frame `x' = 18000 - x, y' = y`, and in seat 1's frame `x' = x, y' = 32000 - y`. In both, x grows to the right of the player's screen and y from the player's own back line towards the enemy's, and the two frames are half a turn apart, so a policy that plays either side sees the same thing. Left and right (the princess towers' order in the header) are as the seat's player sees them.

The action grid is 18 tiles across and 32 along in the seat's frame, a tile being 1000 game units, two of the routing grid's cells; a play is dropped at the tile's centre.

### The observation

`SEAT_INTS` = 2528 integers a seat: a header of 128, then 200 entity rows of 12, the first `entity_count` written.

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
| `deck_slot` x8, `deck_evolution` x8 | the seat's own deck's slot flags by deck index (evolution 1, hero 2, both 3, none 0), and each card's evolution count as the king counts it (plays of an evolution slot's card since its last evolved play), -1 where the count is not established (a play of the card refused at its placement) |
| `hand_form` x4 | the form a play of each hand slot's card would take now: 0 plain, 1 evolved, 2 hero (`EvolutionItem`'s field); 0 for an empty slot |
| champion rows 2 x (`ability_champion`, `ability_unit`, `ability_state`, `ability_cooldown_ms`, `ability_charges`, `ability_command_ticks`) | the seat's king's two champion slots, row `k` for ability action `k`: the vocabulary id of the champion row the slot follows (-1 for none), the battle id of the unit its action names while that unit lives (-1 otherwise), the button state as `ChampionController` works it out, the cooldown left in ms, the charges left (-1 uncounted), and the ticks until the seat's latest ability command for the slot runs (0 for none waiting) |
| `last_ability_run_refusal` | the ability gate's code of the seat's ability command refused at its run in the last decision, 0 for none |

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

`ACTIONS` = 2307 bytes a seat, 1 for an action the seat may take now; it is what the player's screen offers:

- the wait is always open;
- before the match's start cooldown (`LadderMatch.getStartCooldownTicks()`, from the game mode's row) nothing else is;
- after it, a hand slot is open when the client's try of its card passes (`PlayerCommands.tryCard(side, slot, level)`: the match's gates, the elixir less the promised costs, no play of the card waiting, and a Mirror with a card to repeat), and then every one of its 576 tiles is open, as a drop anywhere is moved to the tile the placement search finds. A try the core refuses as not modelled closes the slot;
- a champion slot's ability action is open when the client's try of an ability command naming the slot's unit passes (`PlayerCommands.tryAbility(side, unit)`: the same gates `useAbility` applies, in order, with the elixir less the promised costs, and nothing given). The unit is the one of the play the slot follows (its deploy count) whose row names the champion's ability, not a clone, by the name `Standard1v1Battle` gave it; a slot that follows no play, or a champion no play of the side made, keeps its action closed. The match does not gate an ability command on its start cooldown; no champion stands before it anyway.

The mask reads no entity, so it shows nothing of the field the seat does not see. A play the mask let through is given (the try and the giving are the same check), and runs 20 ticks later against the battle as it stands then, so it may still be refused at its run, as a player's can: `last_run_refusal` shows it, and its cost stays held back for its promise.

### The action

One integer: `0` the wait; `1 + slot * 576 + row * 18 + col` for a play of hand slot `slot` dropped at the centre of tile `(col, row)` of the seat's frame, `col` 0 at the seat's left and `row` 0 at its own back line; and `FIRST_ABILITY + k` (2305 and 2306) for the tap on the ability of the king's champion slot `k` (`Schema.ability(k)`). The ability actions come after every play, so the play actions keep their numbers from version 1.

### The info record

`INFO_INTS` = 12 integers an environment: `tick`, `episode_step`, `winner` (0 or 1, 2 for a draw, -1 while running and for an episode cut short), `crowns_0`, `crowns_1`, `fault` (the kind above, 0 for none), `reset`, `illegal_0`, `illegal_1`, `truncated`, `first_side`, `not_modelled`.

### Refusal codes

`last_refusal` holds the giving's refusal as `PlayerCommands.Refusal` numbers it: 0 `NONE`, 1 `MATCH_OVER`, 2 `KING_DEAD`, 3 `NOT_IN_HAND`, 4 `START_COOLDOWN`, 5 `CARD_PENDING`, 6 `NOT_ENOUGH_ELIXIR`, 7 `NO_TILE`, then the ability refusals, 8 `NO_CHAMPION`, 9 `COOLDOWN`, 10 `NO_CHARGES`, 11 `NOT_CHAMPION`, 12 `ABILITY_PENDING`, 13 `CLONE`, 14 `FROZEN`, 15 `ABILITY_DISABLED` (an ability command's elixir gate and dead king are reported as 6 and 2); and two of the environment's own: 100 `MASKED` (an action the mask closed, never given) and 101 `NOT_MODELLED`. A change of the core's refusals moves the schema's version (`SchemaTest` pins the list).

### The vocabulary and the metadata

The vocabulary numbers every row name of the tables the battle reads its cards, characters, buildings, projectiles and area effects from, sorted, so a card and the character it places share the id of their one name. An id is stable within a data version only: a row another version adds moves every later id. So `VectorEnv.metadataJson()` (or `writeMetadata(path)`) is to be kept with every run and checkpoint: the schema's version, sizes, field names, flag, refusal and fault names, the configuration, and the vocabulary keyed by name (`names`, the name of each id) with the tables' `data_version`, `content_sha` and a `hash` of the list. A model trained on one export is remapped by name to another; `Vocabulary.checkSame(export)` refuses an export whose ids these tables would read differently, naming the first moved name.

## Using it

```java
GameTables tables = VectorEnv.tables(Path.of("<tables folder of a data version>")); // loaded once per process
try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 256, 8)) {
  env.writeMetadata(Path.of("run/metadata.json"));
  env.reset(seeds, deck0, deck1);           // one seed per battle; decks by card row name
  // or env.reset(seeds, deck0, deck1, slots0, slots1) with each deck's slot flags
  IntBuffer actions = env.actions().asIntBuffer();
  while (training) {
    // read env.observations() and env.masks(), write each seat's action
    env.step();
    // env.rewards(), env.dones(), env.infos(); env.fault(i) when infos[i * 12 + 5] != 0
  }
}
```

The buffers are direct `ByteBuffer`s in the machine's byte order, laid out environment after environment and seat after seat: `observations()` is `count x 2 x 2528` int32, `masks()` `count x 2 x 2307` bytes, `actions()` `count x 2` int32, `rewards()` `count x 2` float32, `dones()` `count` bytes, `infos()` `count x 12` int32. They are made once and valid for the vector's life, so a Python bridge maps them once and moves no bytes in a step. `reset(seeds, String[] deck0, String[] deck1)` gives each battle its own decks (eight names each, battle after battle); `reset(seeds, deck0, deck1, int[] slots0, int[] slots1)` adds each deck's slot flags (eight a deck, battle after battle, `Schema.SLOT_EVOLUTION` 1 and `Schema.SLOT_HERO` 2; null for none, every card played plain, as the resets without them do): the shape is checked before any battle is touched, and a card the battle core does not take in a slot ends that battle's episode as a refusal fault as its match starts; `resetEnv(i, seed)` resets one battle on its decks and slot flags; `setAutoreset(false)` leaves finished battles for the caller. `VectorEnv.ladder(Path, ...)` takes a tables folder directly; the folder is one explicit argument, with no default.

With more than one thread the battles are stepped on a fixed pool of daemon threads, each thread taking a contiguous share; nothing is shared between battles but the read-only tables and vocabulary.

`VectorEnv.replayJson(i)` writes battle `i`'s episode so far as a replay of the tables' data version (`ScenarioWriter`, as a fault bundle's `replay.json`): its seed, decks, levels, towers and every command its seats gave with the ticks they were given and run on, its `endTick` the battle's tick now. The debug visualizer's replay viewer opens it, and the replay labeller below reads it as any replay.

## Labelling replays

Behavioural training (imitation) learns a policy from games people played: at each decision, what the player saw and what the player did. `ReplayLabeller` turns a replay into exactly that, in the environment's own schema: it steps the replay's battle and writes, at every decision of each seat, the observation and mask `LadderEnv` would show that seat, and as the label the action that gives the command the player gave. A model trained on the rows plays the environment as it is. The tool holds no data: it labels any replay of a data version the battle core plays (`docs/compatibility.md`), on the tables of that version, and the rows stay on the machine that makes them.

### A row

A replay gives two rows a decision, seat 0's then seat 1's: the decision's tick, the seat (the game side it plays), its observation (`SEAT_INTS` integers, in the seat's own frame, player-visible only) and its mask (`ACTIONS` bytes), the label (an action of the schema, 0 the wait) and the row's flags. Every decision is a row, the wait-only ones included; a trainer that wants only real choices keeps the rows whose mask opens more than the wait.

### The battle

The replay's battle is built as `ReplayBattle.build` builds it (seed, towers, each player's data, decks, levels and slot flags), but with none of its commands queued. Each command is given on its given tick, the tick counter it was stamped with (the replay's `t`, never inferred), through `PlayerCommands`, from the point the replay records and at the level its deck lists, by the same code `LadderEnv` gives its own plays with. So what each seat is shown is what the environment shows a policy: the promised elixir, the plays waiting, the champion buttons, the mask from the client's try. A command the client's try refuses at its giving, whose recorded point the screen's placement search would move, or whose giving the battle core does not model is queued as the replay states it (as `ReplayBattle` does), so the battle stays the replay's; it is counted, and flagged. Each play's item is checked as it runs against the replay's (`ReplayBattle.checkItems`); a replay whose items or reading the battle core refuses stops there, with its rows so far and the reason.

### The alignment

Decisions fall every `decisionTicks` ticks from tick 0, as the environment takes them: 0, 5, 10, ... by default, every tick at 1. A command belongs to the decision whose window, from its tick up to the next decision's, holds its **given** tick; the run tick, 20 later, plays no part. The row of a decision holds the observation and mask written at its tick, before any command of its window is given, as a policy's decision is taken.

### The label

- **A card play**: the hand slot its card holds in the row's observation, and a tile of the seat's frame. A replay records the tile the screen's search found, a tile centre (less 1 on a coordinate the symmetrical snap shifts) or a building's tile corner (see [When a player's command runs](battle-core.md#when-a-players-command-runs)), so the label's tile is the first of the tiles touching the recorded point, the one holding it first, whose centre the screen's search, run on the battle at the giving, sends to the recorded point. Driven from the label, the play is then sent on the replay's point. When no tile's centre does, the tile holding the point is named and the row flagged.
- **An ability command**: the ability action of the champion slot whose unit is the one the command names (by its object id).
- **Two commands of one seat in a window**: the first, in the replay's order, is the label; the others are counted as collisions and the row flagged. At 1-tick decisions only two commands given on one tick collide; at 5 ticks any two within five ticks do.
- **A command the action space cannot say at the row** is labelled the wait and the row flagged: its card is not in the row's hand (at 5 ticks, a card that came into the hand within the window), no champion slot follows the unit an ability command names (a champion an action spawned, or an object id that names no slot's unit), or a variant card's recorded option is not the one the client would pick now (the action names no option).
- **A label the mask closes** is kept, and the row flagged: the client sent a command the environment's try would have refused (for example a play the battle core finds short of elixir at the giving, or given after the match was decided).

Nothing is dropped: every command of the replay is a label, a collision, inexpressible, or after the labelling's end.

| Flag | Bit | Meaning |
|---|---:|---|
| `label_masked` | 1 | the label's action is closed in the row's mask |
| `collision` | 2 | the seat gave another command in the window, which the label does not say |
| `inexpressible` | 4 | the seat gave a command the action space cannot say at the row; the label is the wait |
| `point_unmatched` | 8 | no tile centre's search sends to the recorded point; the tile holding it is named |
| `given_as_recorded` | 16 | a command of the seat's window was queued as the replay states it, not given through the player's commands |
| `unpromised` | 32 | a command of the seat queued as recorded waits to run, so the row's promised elixir, pending marks and mask leave it out |

The counts of a replay (`ReplayLabeller.Counts`, by name in `COUNT_NAMES`): `decisions`, `rows`, `commands`, `plays`, `abilities`, `labelled`, `label_masked`, `collisions`, `inexpressible` (`not_in_hand`, `ability_unit_unknown`, `option_differs`), `point_unmatched`, `given_as_recorded` (`refused_at_giving`: the client's try refused it, its card was not in the hand, or no champion slot names an ability command's unit; `point_moved`; `not_modelled_at_giving`), `after_end` (commands given on or after the tick the labelling stopped at: the match's end, the replay's `endTick`, or a refusal), `order_differs` (windows where both seats are labelled and the replay gave the two in the other order than the environment gives two seats' actions, side 0 first on even decisions) and `rows_unpromised`.

### The proof

`ReplayLabeller.drive(replay, labels)` takes the labels alone and plays them as the environment plays actions: a `LadderEnv` reset on the replay's setup, each decision's two labels given by `step`. It checks the driven battle against the replay's own (`ReplayBattle.build`) at every decision: the two rows before it against the labels' rows, the commands given in it against the replay's (kind, side, card, level, given and run tick, point, option, the unit an ability command names), each play's item as it runs, and the battle's state after it (tick, the battle's random state, each side's elixir, hand, queue and crowns, and every entity's id, kind, side, row, position, state and hit points). It reports the first difference of any kind and goes on past a row or a command's point that differ, stopping at the first command that differs otherwise, the first item and the first state: `reproduced` when everything matched exactly, `stateReproduced` when every command matched but perhaps for its point and the state matched at every decision to the end (a play sent from the tile's centre on a point next to the replay's, which its run places where the replay's was).

At 1-tick decisions a replay whose commands are all expressible, with no collision and no order the alternation reverses, reproduces exactly: an episode of the environment written as a replay is labelled with its own rows and actions, and driven back to the same battle (`ReplayLabellerTest`, `test_labels.py`). At 5 ticks a command given between two decisions is driven at the decision before it, so it is stamped and run up to 4 ticks early, and the driven battle leaves the replay's at the first such command; the rows remain the replay's, which is what training reads.

### Using it

```java
ReplayLabeller labeller = new ReplayLabeller(tables, 5);           // replays the game client records
ReplayLabeller labeller = ReplayLabeller.of(tables, 1, "generated"); // generated test cases, every tick
for (ReplayFiles.Source source : ReplayFiles.read(Path.of("<file>"))) {
  ReplayLabeller.Labels labels = labeller.label(source.document()); // rows, counts, stop tick, refusal
  ReplayLabeller.Drive drive = labeller.drive(source.document(), labels);
}
```

`ReplayFiles.read` reads a replay document (`.json`) as it is, or a crawl's JSON Lines output (`.jsonl`, plain, zstd or gzip, found by its first bytes) as the desktop replay viewer reads it: format 1 records, the replay as a string, each given the capture block its record names (the battle's data version and content sha), so a record is read only on the tables of its own data version and refused on another's; a record that names candidate versions, or cannot be read, is listed with why. `labeller.metadataJson()` is the environment's metadata export with the labeller's decision interval, to be kept with the rows.

### Measured on the reference battles

Measured on 2026-10-16 on one Mac (Apple M4 Pro, JDK 17.0.14, Python 3.12.7) with other work running (1-minute load average 4.6 at the start and 9.3 at the end of the 16.402.19 run, 5.6 and 8.2 of the 16.402.21 run): `label_replays` on 4 threads over every reference battle of the data repository of each version, labelling alone for the rate, then again with `check`. The reference battles are of two kinds: replays the game client recorded (25 of 16.402.19, 33 of 16.402.21), and generated test cases (967 each), whose plays are dropped on arbitrary points and some before the start cooldown, as no player's screen sends them; only the first kind is players' games.

| version | kind | decision | replays | rows | rows/s | rows labelled with a command | driven exactly | state reproduced |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 16.402.19 | recorded | 5 ticks | 25 | 43,092 | 81,435 | 3.5% | 0 | 0 |
| 16.402.19 | recorded | 1 tick | 25 | 215,330 | 190,786 | 0.70% | 9 | 13 |
| 16.402.19 | generated | 5 ticks | 967 | 1,654,574 | 133,638 | 0.33% | 20 | 279 |
| 16.402.19 | generated | 1 tick | 967 | 8,267,832 | 260,921 | 0.07% | 20 | 341 |
| 16.402.21 | recorded | 5 ticks | 33 | 58,860 | 70,328 | 3.7% | 0 | 0 |
| 16.402.21 | recorded | 1 tick | 33 | 294,136 | 181,098 | 0.74% | 10 | 14 |
| 16.402.21 | generated | 5 ticks | 967 | 1,654,574 | 141,210 | 0.33% | 20 | 279 |
| 16.402.21 | generated | 1 tick | 967 | 8,267,832 | 241,648 | 0.07% | 20 | 341 |

One thread labels about 22,000 rows a second at 5-tick decisions; the threads scale, as each replay is labelled whole in the JVM, which releases Python's lock (4 threads 89,000, 8 threads 171,000, on 18 reference battles).

The recorded replays of 16.402.19 at 1-tick decisions: 1,629 commands (1,538 plays, 91 ability commands), 1,499 labelled; 16 labels closed by the mask, 1 collision, 16 inexpressible (2 cards not in the hand, 14 ability commands whose object id names no champion slot's unit), 43 points no tile centre's search sends, 73 commands given as recorded, 113 after the end, 5 order flips. At 5 ticks the same replays have 76 labels closed by the mask (a command's window opening before the elixir for it was there), 22 order flips and still 1 collision. Those of 16.402.21 are alike (2,298 commands, 2,163 labelled at 1 tick).

What the drive found, at 1-tick decisions: every recorded replay whose labels are all expressible, open in their masks, with no collision and no order flip reproduces the replay's battle to its end (13 of 13 of 16.402.19, 14 of 14 of 16.402.21), and exactly, every command's point included, when no point is unmatched (9 of 9, 10 of 10). Every other replay stops at a flagged row or an order flip: an ability command whose unit no slot names, a label the mask closed (the client sent a play the battle core finds short of elixir at the giving, or one given after the match was decided), a variant card's option, or two seats' commands on one tick in the other order than the environment's alternation. The 43 unmatched points of 16.402.19 lie at the two middle tile columns, sent at 8500 and 9499 where the battle core's screen search gives 8499 and 9500 (mostly in an open pocket), and three into a pocket the battle core finds closed at the giving; the run places them where the replay's were, so the state still matches. Both, and the client's try at the giving, are filed for tracing on 16.402.17 rather than guessed. At 5-tick decisions no recorded replay is driven exactly: its first command given between two decisions is given up to 4 ticks early.

## What is not offered yet

- **Champion abilities of spawned champions**: an ability action names a unit a card play of the side made (`PlayerCommands.useAbility` names units by the play's name); a champion slot that follows a champion an action spawned keeps its action closed.
- **The enemy's champion slots and slot flags**: a seat sees only its own; the enemy's champion shows on the field as an entity.
- **Deck slot rules**: the environment checks the slot flags' shape only; which cards a slot may hold, and how many slots a deck has, are the battle core's to refuse as the match starts.
- **Levels**: every card is played at one level (`EnvConfig.level`, 11 by default) and every tower at `EnvConfig.towerLevel`; entity rows carry no level.
- **Inferred knowledge of the enemy**: what a player can count (the enemy's cycle, an estimate of its elixir) is not in the observation; it will come as separate fields.
- **Per-entity detail** beyond the table above (targets, deploy countdowns, buffs, velocities, projectile aims, area lifetimes), static per-row features and normalization scales.

## Running it

```bash
./gradlew :env:test -Pcrforge.assetSource=file://$DD/cdn/            # the Ladder tests read the tables like every core test
./gradlew :env:envBenchmark -Pcrforge.assetSource=file://$DD/cdn/    # JMH, about 10 minutes
./gradlew :env:envBenchmark -Pcrforge.assetSource=file://$DD/cdn/ --args="VectorEnvBenchmark.ladder -f 1 -wi 2 -i 3"
```

`VectorEnvBenchmark` has `ladder` (Ladder battles on the tables, a mirror match of an eight-card deck) and `synthetic` (no battle), over 1, 64 and 256 environments on 1 and 4 threads. One operation is one `step()` of the vector; `:envSteps` counts environment steps and `:ticks` battle ticks. The actions are a random open play 3 decisions in 10 a seat, else the wait.

## Python

The `crforge-env` package (`python/`, a uv project) runs the module's jars in a JVM inside the Python process and reads the vector's buffers as arrays of the same memory: a step is one call into the JVM for every battle, and no observation is copied across.

### Install

```bash
./gradlew :env:installDist                                   # the jars, under env/build/install/env
./gradlew :tables:dataVersionTables -Pcrforge.assetSource=file://$DD/cdn/   # the tables (docs/game-tables.md)
cd python && uv sync --extra dev                             # python/.venv, from uv.lock
```

Two inputs, each one explicit argument with no default and no environment variable: `install`, the module's installed distribution (`env/build/install/env`, whose `lib` folder holds the env module, the battle core and their libraries), and for Ladder battles `tables`, the tables folder of a data version as the build makes it (`<cache>/tables/<data version>`, `~/.crforge/tables/<data version>` by default). The JVM starts once per process with the jars of the first `install`; another folder later in the same process is refused. JVM options (heap, collector) go in `jvm_args` of the first vector.

### The vector

`VectorBattle(count, threads, install=..., tables=..., config=LadderConfig(...))` wraps `VectorEnv.ladder`; `engine="synthetic"` wraps synthetic environments instead and needs no tables. Its arrays are views of the buffers, made once: `observations` int32 `(count, 2, 2528)`, `masks` uint8 `(count, 2, 2307)`, `actions` int32 `(count, 2)` (written before `step()`), `rewards` float32 `(count, 2)`, `dones` uint8 `(count,)`, `infos` int32 `(count, 12)`. `reset(seeds, deck0, deck1, slots0=None, slots1=None)` takes one deck for all battles or one per battle, by card row name (`"Archer"`, as the tables name it), and each deck's slot flags the same way (eight values, `schema.slot_evolution` and `schema.slot_hero`; None for none); `reset_env(i, seed)`, `set_autoreset(flag)`, `info(i)` (the info record by name), `seat_info(i, seat)` (below), `fault(i)`, `fault_counts()`, `fault_kind_counts()`, `replay_json(i)` (battle `i`'s episode so far as a replay document, `VectorEnv.replayJson`). `LadderConfig` fields left unset take `EnvConfig.DEFAULT`'s; `LadderConfig(fault_bundles=<folder>)` writes fault bundles ([Fault bundles](#fault-bundles)), and `fault(i).bundle` names a written bundle's folder.

A synthetic vector takes `synthetic_entities`, `synthetic_episode_steps`, and `synthetic_fault_at` with `synthetic_fault_kind` (`refusal`, `bug`, `env_bug`): every episode faults at that decision, so the fault path can be tested without a battle.

### The schema from the metadata

At start the package reads `VectorEnv.metadataJson()`: the schema's version, sizes and field names give every offset (`schema.header[name]`, the indices of a header field; `schema.entity[name]`, `schema.info[name]`, `schema.flags[name]`, `schema.refusals[code]`), and the few codes the metadata does not list (results, entity kinds, the winner codes, the deck size, the arena's size) are read from the `Schema` class. No offset is typed twice in Python. The package reads schema version 2; a module that writes another version is refused at start. The action count is `schema.actions` (the metadata's `actions`), the first ability action `schema.first_ability`; `schema.ability(k)`, `schema.is_ability(action)` and `schema.ability_slot(action)` encode and read the ability actions as `schema.play` and `schema.decode` do the plays.

`vector.metadata` is the export and `vector.write_metadata(path)` writes it, to be kept with every run and checkpoint. `vector.check_metadata(saved)` refuses a saved export this vector would read differently: another schema version, sizes or field names, a vocabulary missing, or a vocabulary whose ids moved (through `Vocabulary.checkSame`, naming the first moved name), so a model trained on one data version is remapped by name rather than read shifted.

### The wrappers

Two wrappers over a vector of one battle, both with autoreset off:

- `ClashParallelEnv(deck0, deck1, install=..., tables=..., slots0=None, slots1=None)`: a parallel multi-agent environment whose agents `seat_0` and `seat_1` play sides 0 and 1. Each agent's observation is its seat's 2528 integers in its own frame; an agent left out of the actions waits. `action_mask(agent)` gives an agent's mask.
- `ClashGymEnv(deck, opponent_deck, install=..., tables=..., opponent=..., seat=0, slots=None, opponent_slots=None)`: a single-agent environment for the learner on `seat`, `slots` and `opponent_slots` each deck's slot flags; the other seat is played by `opponent(observation, mask, rng)` (`random_opponent`, a random open action three decisions in ten, `wait_opponent` and the scripted `ScriptedBot` below come with it). `action_masks()` gives the learner's mask as booleans, the method masked-action trainers call.

The observation space is a box of int32 of the seat's 2528 integers, not floats: its values are vocabulary ids, codes, flags and integer amounts, and an encoder embeds the ids and scales the amounts itself. Its bounds (`schema.observation_bounds(vocabulary_size)`): a vocabulary id field (hand, next card, deck, enemy cards seen, waiting commands' cards, a champion slot's champion, an entity's row) lies in -1 to the last id; the result, the seat, the overtime and pending marks, the deck's slot flags, the hand's forms, an entity's kind, side and flags in 0 to their largest code; every other field in the int32 range. The action space is the 2307 actions. A `reset(seed=...)` takes a 32-bit seed as the battle's seed; a reset without one draws the battle's seed from the environment's own generator.

Each seat's info (`VectorBattle.seat_info`) is its own: `tick`, `episode_step`, `winner` (the side, 2 for a draw, -1 while running and for an episode cut short), `crowns` and `crowns_enemy`, `illegal` (1 when this seat's action of the decision was refused as illegal) and `illegal_total` (this seat's in the episode), `last_refusal` (the refusal's name, `NONE` for a wait, a play or an ability command given), `last_run_refusal`, `last_ability_run_refusal`, `first_side`, `not_modelled`, `truncated`, `fault`, `fault_message`, `fault_signature`, `fault_bundle`, and `action_mask` (int8). The other seat's counts are in the other seat's info only.

### The scripted bot and evaluation

`ScriptedBot` is a deterministic opponent of three rules, of the same shape as `random_opponent` (`bot(observation, mask, rng) -> action`). It reads the seat's observation and mask only: its elixir, hand, hand costs and waiting plays, the enemy princess towers' hit points and the enemy characters on the field. Each decision:

1. **Defend**: an enemy character past the river on the seat's half and no play of its own waiting: the cheapest open troop or building card in the lane of the deepest such character, on the row just behind the princess towers.
2. **Attack**: the elixir less what its waiting plays hold back at 9 or more (`attack_elixir`): the cheapest open troop card at the bridge of the lane whose enemy princess tower has fewer hit points, on the last row of its half; `rng` breaks a tie of the two lanes, and is drawn from for nothing else.
3. Otherwise **wait**.

A card is open when the mask opens a tile of its hand slot (the mask carries the elixir, the start cooldown and a play of the card already waiting). The bot plays its chosen tile when the mask opens it, else the nearest tile of that slot the mask opens; it never takes an action the mask closed, and never plays a spell. The lanes and rows are tiles of the seat's frame of the standard arena, constructor arguments with defaults.

A card's cost is `hand_cost` of the observation. Its type comes from the tables: `VectorBattle.card_types()` gives each deck card's vocabulary id the type of its card table, `troop` (the character cards), `building` (the building cards) or `spell` (the other cards). The bot is bound to a vector with `bind(vector)` before its first decision, which reads the layout and the card types; `ClashGymEnv` binds an opponent with a `bind` method as it makes its vector, and `evaluate` binds both policies. A synthetic vector has no tables, so a bot on one is given its `card_types`.

```python
env = ClashGymEnv(deck, deck, install=install, tables=tables, opponent=ScriptedBot())
result = evaluate(policy, ScriptedBot(), deck=deck, episodes=200, install=install, tables=tables)
result.wins, result.losses, result.draws, result.truncated, result.win_rate
```

`evaluate(policy, opponent, *, deck, episodes, install, opponent_deck=None, tables=None, engine="ladder", config=None, envs=8, threads=1, seed=0, **vector_kwargs)` plays `episodes` episodes of `policy` against `opponent` (each a policy of the opponent's shape; a trained model is wrapped in such a function) on a vector of `envs` battles, and counts them from the policy's seat as an `EvaluationResult`: `episodes`, `wins`, `losses`, `draws`, `truncated` (cut short by the tick limit or a fault), `faults` by kind, `illegal` (the policy's actions refused as illegal) and `win_rate` (wins over all episodes). The policy plays seat 0 in the even environments and seat 1 in the odd ones, so an even `envs` gives each side half the episodes; each environment plays a fixed share of the episodes, so which ones count does not depend on which end first; the battle seeds and each seat's generator are drawn from `seed`, so a run is reproduced by it.

Measured on 2026-10-16 on one Mac (Apple M4 Pro), data version 16.402.19, with other work running (1-minute load average 8.3 to 9.0): `evaluate` over 300 episodes each, 16 battles on 4 threads, seed 2026, both seats holding the deck `Knight`, `Archer`, `Musketeer`, `Giant`, `MiniPekka`, `Fireball`, `Arrows`, `Zap` at level 11, a decision being 5 ticks.

| policy | opponent | wins | losses | draws | truncated | seconds |
|---|---|---:|---:|---:|---:|---:|
| `ScriptedBot` | `wait_opponent` | 300 | 0 | 0 | 0 | 3.5 |
| `ScriptedBot` | `random_opponent` | 300 | 0 | 0 | 0 | 4.5 |
| `random_opponent` | `ScriptedBot` | 0 | 300 | 0 | 0 | 3.9 |
| `ScriptedBot` | `ScriptedBot` | 141 | 157 | 2 | 0 | 9.8 |

No episode faulted and the bot took no illegal action. The bot is a floor for a trained policy to beat, not a strong player: it never plays a spell, defends with whatever troop is cheapest and attacks in one lane at a time.

### Labelled replays

```python
from crforge_env import label_replays

run = label_replays(paths, install, tables, decision_ticks=5, threads=4)            # in memory
run = label_replays(paths, install, tables, decision_ticks=1, check=True, out_dir="rows/")
run.metadata                       # the schema, the configuration with the interval, the vocabulary
run.replays[0].observations        # int32 (rows, 2528); .masks uint8 (rows, 2307); .actions int32
run.replays[0].ticks, .seats, .flags, .counts, .drive
run.summaries, run.unreadable, run.totals()
```

`label_replays(paths, install, tables, *, decision_ticks=5, threads=1, shape="replay", check=False, out_dir=None, jvm_args=())` labels every replay of the files (replay documents and crawl outputs, as above) on the tables of their data version ([Labelling replays](#labelling-replays)). Each replay is labelled whole on one thread, `threads` at once. `shape` is `replay` for replays the game client records and `generated` for generated test cases. `check` also drives a battle from each replay's labels and reports it in `drive` (`reproduced`, `state_reproduced`, `first_divergent_tick`, `divergence`, `stop` and the counts). With `out_dir` each replay's arrays go to `<out_dir>/<source>.npz` (`ReplayLabels.save`, the counts and drive as JSON strings in it), with `metadata.json` and `counts.jsonl` (one replay a line) beside them, and none is kept in memory. `FLAGS` names the row flags and `flag_names(flags)` reads them. The rows are local data of the machine that makes them: never commit them.

### Faults are truncations

A fault is never a result. When a battle's episode ends by a fault (a refusal of the battle core, a bug of the core, a bug of the environment), the step that reports it is `terminated=False, truncated=True` with reward 0 for both seats, and the info names the fault's kind under `fault` (`refusal`, `bug` or `env_bug`, `none` otherwise) with `fault_message`, `fault_signature` and `fault_bundle` (the folder of the fault's bundle, empty when none was written). A trainer bootstraps such a step as it bootstraps a tick limit, and never learns from it as a loss or a win. A tick limit (`LadderConfig.max_ticks`) truncates the same way, with `fault` `none`. A reset whose new episode faults before its first decision raises `BattleFaultError` with the fault.

### Tests and checks

```bash
cd python
uv run pytest --install=../env/build/install/env --tables=<tables folder>   # all tests
uv run pytest --install=../env/build/install/env                            # the Ladder tests skipped, and the run says so
uv run ruff check && uv run ruff format --check
```

The tests without tables run the vector, the schema and both wrappers on synthetic environments: the arrays view the buffers, a forced fault of each kind ends the episode as truncated with no reward in both wrappers, the same seeds and actions give the same observations on one thread and four, a saved export of another schema is refused, and both wrappers pass their API's own checker. The Ladder tests add: a battle's start (decks, towers, a mask of the wait alone before the start cooldown), the mask honoured (a closed action counted for its seat alone, random open actions never illegal), determinism on one thread and four, a whole battle to its result, a tick limit as truncation, both API checkers on a battle, a saved vocabulary whose ids moved refused, and a fault's bundle written, its folder named in the fault and the seats' info, and none written by default. The label tests write an episode of the environment as a replay (`VectorBattle.replay_json`) and label it back: at 1-tick decisions its own observations, masks and hand slots come back and the labels drive the same battle; a crawl record of it, plain or compressed, is labelled as the replay document is, on one thread or two, in memory or written to a folder. The scripted bot's tests hold its rules on observations written by hand, its determinism and the mask on synthetic environments with hand-written card types, the evaluation's counting on synthetic episodes, and on Ladder battles the card types against the tables, a win against `wait_opponent` and a whole battle against `random_opponent` with plays and no illegal action. CI runs them in the `python` job of `build-and-test.yml`, on the tables of the lock's version built from the data repository's copy of the asset files.

### The Python-side benchmark

`crforge-env-bench --install <env/build/install/env> --tables <folder>` steps a vector of 1, 16, 64 and 256 environments of each engine (`--engine` picks one) for 200 decisions, 3 trials after a warm-up, with a random open play 3 decisions in 10 a seat drawn on arrays in Python, and times the whole loop: the action draw, the call into the JVM and the reads of rewards and dones. Every battle is the same match of two decks of card row names (`Knight`, `Archer`, `Musketeer`, `Giant`, `MiniPekka`, `Fireball`, `Arrows`, and `Zap` or `Goblins`). The best trial is reported, with the spread over the trials.

Measured on 2026-10-16 on one Mac (Apple M4 Pro, 14 cores, JDK 17.0.14, Python 3.12.7), with other work running (1-minute load average 3.7 to 5.1): `crforge-env-bench --threads 1` and `--threads 4`, data version 16.402.19, a decision being 5 ticks. A short run on a busy machine: read the numbers as magnitudes.

| engine | battles | threads | env steps/s | ticks/s | us a call | spread |
|---|---:|---:|---:|---:|---:|---:|
| `ladder` | 64 | 1 | 22,405 | 112,011 | 2,857 | 20% |
| `ladder` | 256 | 1 | 22,731 | 113,601 | 11,262 | 28% |
| `ladder` | 64 | 4 | 63,694 | 318,428 | 1,005 | 30% |
| `ladder` | 256 | 4 | 60,023 | 299,975 | 4,265 | 21% |
| `synthetic` | 64 | 1 | 217,106 | - | 295 | 4% |
| `synthetic` | 256 | 1 | 206,586 | - | 1,239 | 3% |
| `synthetic` | 64 | 4 | 327,906 | - | 195 | 2% |
| `synthetic` | 256 | 4 | 348,710 | - | 734 | 4% |

From 64 battles on, Python steps Ladder battles at about the JVM benchmark's rate above ([Performance](#performance)): the bridge and the action draw add little to a Ladder decision. On the synthetic engine, where a decision costs the JVM about 3 us, the draw of the random actions in Python (about 2 us an environment) is most of the rest. Runs of 1 and 16 battles are left out of the table: their spread was 30 to 265 percent on this machine (the warm-up of the first engine run, and per-call overheads that dominate at that size).

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
