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
  Schema                 the flat layout: header, entity rows, mask, action codec, seat frames, codes, state names, scales (version 4)
  Vocabulary             every card, character, building, projectile and area effect row named by number, and the buffs in a list of their own
  SeatView               what a seat's player sees of the field's characters and towers, and their health bars: the one place that rule lives
  BuffSlots              which of an entity's buffs its row's three slots list, in which order
  ElixirCount            one side's elixir as the other side's player counts it from what it sees
  EnvConfig              decision interval, card and tower level, tick limit, fault bundle folder, mask rule
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
2. Each environment gives its two actions, one seat after the other. Commands given on the same tick run in the order they were given, so the side given first alternates: side 0 on even decisions (counted from 0 in the episode), side 1 on odd ones. `I_FIRST_SIDE` says which.
3. Each action is given by its kind:
   - A wait does nothing.
   - A play of an action the seat's mask left open is given with `PlayerCommands.playCard(side, slot, level, x, y, name)` at the tile's centre, at `EnvConfig.level`. The player's screen runs the placement search at the giving and the command carries the tile it finds, so a drop anywhere is moved to a legal tile rather than refused. The command is stamped with the tick counter and runs `PLAY_DELAY_TICKS` (20) ticks later against the battle as it stands then.
   - An action the mask closed, out of range, or refused at its giving is taken as a wait and counted as illegal (`I_ILLEGAL_0`, `I_ILLEGAL_1`, the seat's `illegal`), with its refusal code in the seat's `last_refusal`.
   - An ability action the mask left open is given with `PlayerCommands.useAbility(side, unit, name)`, naming the unit of the play the champion slot follows by the name the battle gave it (below). It is stamped and runs 20 ticks later the same way.
   - A giving the battle core refuses as not modelled (an `UnsupportedOperationException` from `playCard` or `useAbility`) is refused the same way, with the code `NOT_MODELLED` and counted in `I_NOT_MODELLED`. It is not a fault.
4. The battle steps `EnvConfig.decisionTicks` times (5 by default, four decisions a second of game time; 1 decides every tick), or fewer when the match ends.
5. The environment writes both seats' observations and masks, the rewards and the info record. The step returns; everything is in the buffers.

An episode ends when the match ends. `dones[i]` is 1 and the observation is the terminal one. The rewards are +1 and -1, or 0 and 0 for a draw; `H_RESULT` says which and `I_WINNER` names the winning side (2 for a draw).

With autoreset on (the default) the next step resets that environment instead of stepping it, ignores its actions and reports the new episode's first observation with `I_RESET` set. `EnvConfig.maxTicks` cuts an episode short with `I_TRUNCATED` and `I_WINNER` -1.

### Reproducibility

An environment's episodes draw their seeds from a sequence its reset seed starts, so a run is reproduced by its reset seeds and actions alone. The same seeds and actions give byte-identical buffers on one thread or four (`LadderEnvTest`, `SyntheticVectorEnvTest`).

A battle is set up as the game sets one up: each player's data is handed over (one choice each, `Standard1v1Battle.addPlayerData`) before the decks are dealt with both players' words 0. So an episode is the battle a replay of it gives ([Fault bundles](#fault-bundles)). Each play's command is named `cmd<n>` after its place among the battle's commands, as a replay names it.

### Autoreset seeds

By default an autoreset episode starts at the next 32-bit integer of its environment's sequence, any seed at all. `VectorEnv.setAutoresetSeeds(min, max)` (Python `set_autoreset_seeds(low, high)`) keeps those seeds inside a range, both ends included. Each is drawn evenly from it by the same sequence, so the same reset seeds and actions still give the same run, on any thread count.

A trainer that keeps a window of seeds for its evaluations gives its training range here, so no training episode starts at an evaluation seed and the battle the autoreset began needs no second reset. The seeds given to `reset` and `resetEnv` are the caller's own and are not checked against the range.

`episodeSeed(i)` (`episode_seed(i)`) is the seed battle `i`'s current episode started at. The vector's metadata names the range as `autoreset_seeds` (`min` and `max`, the whole 32-bit range by default). Metadata written without a vector (`VectorEnv.metadataJson(config, vocabulary)`, the replay labeller's) has none. `AutoresetSeedsTest` and the Python tests hold the default sequence, the range, its two ends and the same seeds on one thread and several.

Measured on 2026-10-18 on one Mac (Apple M4 Pro), data version 16.402.21, with other work running (1-minute load average 15 to 20). A second reset of one battle (`reset_env`) takes about 0.3 milliseconds on the caller's thread, while a step of 64 battles on 8 threads takes about 2.2 milliseconds. So a trainer's second reset per episode cost about 1 % of its decision time on whole Ladder episodes (about 720 decisions or more each), more on episodes a tick limit cuts short.

## Faults

Each environment's reset and step run inside a fault boundary of their own in `VectorEnv`. An exception there ends that environment's episode alone: done, `I_TRUNCATED` 1, `I_WINNER` -1, rewards 0, both masks the wait alone, the observations cleared, and the fault's kind in `I_FAULT`.

The other environments, on the same thread or another, are stepped as if nothing happened. Nothing is rethrown from `step()`. The next step resets the faulted environment as it resets a finished one.

| Kind | `I_FAULT` | Source | Meaning |
|---|---|---|---|
| `refusal` | 1 | an `UnsupportedOperationException` from the battle core outside a giving (while it steps, or as the match starts) | a behaviour the core does not model: it refuses rather than guesses. A coverage gap, not a bug |
| `bug` | 2 | any other exception from the battle core | a broken invariant inside the core |
| `env_bug` | 3 | an exception in the environment's own code | the environment's mistake |

The environment wraps every call into the core in its own marker (`EnvFault.CoreFailure`), so the boundary tells the core's exceptions from its own without reading stack traces.

`VectorEnv.fault(i)` gives the kind, the exception's class and message, the tick, and a signature: the kind, the exception's class, the message with its numbers replaced by `#`, and the frame that threw it. `VectorEnv.faultCounts()` counts faults by signature since the vector was made, `faultKindCounts()` by kind.

A play refused at its giving or at its run is not a fault: it is a legal outcome shown to the seat.

Decks are checked before any environment is touched: eight cards, each a card row of the tables, none twice. A bad deck refuses the whole `reset` call with the environment and side named, and changes nothing.

### Fault bundles

With `EnvConfig.faultBundles` naming a folder (`EnvConfig.DEFAULT.withFaultBundles("<folder>")`; off by default), the vector writes a faulted Ladder episode to a folder of its own under it, `<kind>-<signature hash>-env<i>-tick<t>`. `VectorEnv.fault(i).bundle()` names that folder.

- Only the first fault of each signature the vector meets is written. The others are counted (`faultCounts()`), so a fault met every episode does not fill the disk.
- The bundle is written inside the fault boundary, after the episode is ended.
- A bundle that cannot be written is left out with the reason on the error stream, and the vector goes on as for any fault.
- A synthetic environment plays no battle and writes none.

| File | What it holds |
|---|---|
| `replay.json` | the episode's battle as a replay of the tables' data version (`org.crforge.core.battle.replay.ScenarioWriter`): the seed, the decks with their levels and slot flags, the towers, each player's data, every command the seats gave with the tick it was given on and the tick it runs on and the item its client built, and a capture block naming the tables' client version, data version and content sha. Its `endTick` is the tick after the faulting step. Left out (`replay` null, `replay_refusal` the reason) when the battle cannot be written, for one when no battle was built |
| `fault.json` | `kind`, `signature`, `exception`, `message`, `stack` (the full stack trace), `data_version`, `content_sha`, `schema_version`, `config` (decision ticks, levels, tick limit), `env_index`, `seed` (the episode's battle seed), `decks`, `slots` (each deck's slot flags), `tick`, `episode_step`, `actions` (both seats' actions of every decision of the episode, the faulting one last), `commands` (every command given to the battle, plays and ability commands: name, kind, side, card, level, given and run tick, point, option, and an ability command's unit and object id), `replay` |

Read back and stepped, the replay meets the same fault at the same tick, with the same message and signature. `ReplayScenario` translates it and `ReplayBattle.build` builds its battle, which a step past the fault's tick refuses as the episode did (`FaultBundleTest`).

The debug visualizer opens it as any replay (`./gradlew :desktop:run --args="--replay <bundle>/replay.json"`) and stops at its `endTick`. A play given and not yet run when the battle faulted carries the item its side builds at the fault, which the replay checks only if it runs that far.

Bundles are local files of the machine that runs the environment, a run's own data: they are never committed. Write them outside the repository, or under `fault-bundles/`, which `.gitignore` lists.

## The schema (version 4)

Everything is a 32-bit integer in the machine's byte order, so Python reads the buffers as arrays without a copy; only the rewards are floats. The constants and their documentation are in `org.crforge.env.Schema`, and `VectorEnv.metadataJson()` exports them (below).

### The seat's frame

A seat is one player's view, in the game's own view of that player's side, as the debug visualizer draws them (`ViewOrientation`): the player's king at the bottom. The battle's coordinates have y grow from side 0 towards side 1.

The game draws side 1's view with the positions as they are along the width and mirrored along the length, and side 0's view as that turned half a turn: mirrored along the width and kept along the length. So:

- in seat 0's frame `x' = 18000 - x, y' = y`;
- in seat 1's frame `x' = x, y' = 32000 - y`.

In both, x grows to the right of the player's screen and y from the player's own back line towards the enemy's. The two frames are half a turn apart, so a policy that plays either side sees the same thing. Left and right (the princess towers' order in the header) are as the seat's player sees them.

Every point of the observation is in the seat's frame: an entity's position, its target's position and a projectile's aim, a waiting command's point and an enemy play's point. A move (`dx`, `dy`) is the difference of two such points, so it changes sign between the two seats.

The action grid is 18 tiles across and 32 along in the seat's frame, a tile being 1000 game units, two of the routing grid's cells; a play is dropped at the tile's centre.

### The observation

`SEAT_INTS` = 7024 integers a seat: a header of 224, then 200 entity rows of 34, the first `entity_count` written. Every amount is an integer as the battle core holds it. The metadata's `scales` give the units an encoder divides by (below), and nothing in Java normalizes.

#### The header

The header tables below give, per field (a field of several values is named once per value, `x4` for four), what it holds; a field marked new is new in version 4, a field marked changed holds what it held in version 3 with the rule of version 4.

| Header field | What it is |
|---|---|
| `tick`, `episode_step`, `seat`, `decision_ticks`, `play_delay_ticks` | the battle's tick counter, the decisions taken, the game side the seat plays, the two intervals |
| `result` | running 0, won 1, lost 2, draw 3 |
| `elixir`, `elixir_reserved` | the king's elixir in ten-thousandths; the elixir the seat's client holds back for its commands (`PlayerCommands.promised`, in ten-thousandths): each waiting command's cost, and a play refused at its run for 60 ticks from its giving |
| `crowns`, `crowns_enemy` | the crowns each side has taken |
| `hand_card` x4, `hand_cost` x4, `hand_pending` x4, `next_card`, `hand_refill_ms` | the hand by vocabulary id (-1 for an empty slot) and listed whole-elixir cost, 1 where a slot's card has a play waiting (`PlayerCommands.isPending`), the card that refills next, the refill cooldown |
| `deck` x8 | the seat's deck by vocabulary id |
| `enemy_seen` x8 (changed) | the enemy's cards in the order the screen showed them, -1 beyond: what a player learns of the other deck. A card shows when its play shows it (below): most at the run, a tunnelling card when its unit comes up. A Mirror's play lists the Mirror and the card it repeated |
| towers x12 | hit points and maximum of the seat's king, left and right princess tower, then the enemy's king, left and right; a fallen tower reads 0 |
| `entity_count`, `entity_overflow` | rows written, entities left out for want of rows |
| `time_ms`, `section`, `section_type`, `section_left_ms`, `full_bar_ms`, `overtime` | the match's clock: the battle time, the timeline's section and its type (codes, not amounts), the time left in it (the battle clock), the time the current elixir rate fills one bar in, 1 in overtime |
| `play_open_in_ticks` | ticks until the match takes a card play (the game mode's start cooldown); 0 from then on |
| `last_refusal` | why the seat's action of the last decision was refused (code below), 0 for a wait or a play given |
| `last_run_refusal` | the code of the seat's play refused at its run in the last decision: the match gate's code, 1000 for a placement that found no place, 0 for none |
| `illegal` | the seat's actions refused as illegal in the episode |
| `pending_count`, pending rows 4 x (`pending_card`, `pending_x`, `pending_y`, `pending_ticks`) | the seat's own commands given and not yet run, oldest first: the card, the point the command carries in the seat's frame, the ticks until it runs; unused rows are -1, 0, 0, 0. A hand slot's card has at most one play waiting, so four rows hold them all |
| `deck_slot` x8, `deck_evolution` x8 | the seat's own deck's slot flags by deck index (evolution 1, hero 2, both 3, none 0), and each card's evolution count as the king counts it (plays of an evolution slot's card since its last evolved play), -1 where the count is not established (a play of the card refused at its placement) |
| `hand_form` x4 | the form a play of each hand slot's card would take now: 0 plain, 1 evolved, 2 hero (`EvolutionItem`'s field); 0 for an empty slot |
| champion rows 2 x (`ability_champion`, `ability_unit`, `ability_state`, `ability_cooldown_ms`, `ability_charges`, `ability_command_ticks`) | the seat's king's two champion slots, row `k` for ability action `k`: the vocabulary id of the champion row the slot follows (-1 for none), the seat's id of the unit its action names while that unit lives (-1 otherwise; the same id as the unit's row, see Entity ids below), the button state as `ChampionController` works it out, the cooldown left in ms, the charges left (-1 uncounted), and the ticks until the seat's latest ability command for the slot runs (0 for none waiting) |
| `last_ability_run_refusal` | the ability gate's code of the seat's ability command refused at its run in the last decision, 0 for none |
| `enemy_play_count` | the enemy's plays placed so far in the episode, each counted at its run, when the screen first shows something of it (a tunnel's trail included) |
| `enemy_elixir` (changed) | the enemy's elixir as the seat's player counts it, in ten-thousandths (the rule below); never the enemy's own count |
| enemy play rows 8 x (`enemy_play_card`, `enemy_play_cast`, `enemy_play_form`, `enemy_play_ticks`, `enemy_play_x`, `enemy_play_y`, `enemy_play_kind`, `enemy_play_flags`, `enemy_play_point_ticks`) (changed, three fields new) | the enemy's last 8 plays placed, newest first, each entering on its run tick as the screen shows it then (the rule below): the vocabulary id of the deck card played, the vocabulary id of the row it was cast as (its evolved or hero row, a variant card's option, the card a Mirror repeated; the card itself otherwise) and its form (0 plain, 1 evolved, 2 hero; 0 for a Mirror or a variant card), all three -1 until the screen shows the card; the ticks since it ran (1 at the earliest, never 0, see below); the point the screen shows it at in the seat's frame, -1, -1 until it shows one; its kind (1 `troop`, 2 `area`, 3 `launched`, 4 `thrown`, 5 `tunnel`; the metadata's `play_kind_names`); its flags (bit 0 `card_shown`, 1 `point_shown`, 2 `mirror`, 3 `evolution_diamond`; `play_flag_names`); and the ticks since its point was shown, -1 before. Unused rows are -1, -1 and zeros (kind 0). With all 8 cards seen, the order of these plays tells a player the enemy's hand: a card played k-th is back in the hand at the enemy's play k + 4 |
| `queue` x4 | the seat's own queue behind the hand, the cards in the order they come back, by vocabulary id (the first is `next_card`), -1 beyond |
| `deck_level` x8 | the level, counted from 1, each of the seat's deck cards is played at: `EnvConfig.level` (11 by default) in self-play, the replay's levels in a labelled replay |
| `enemy_seen_evolution` x8, `enemy_seen_evolution_need` x8 (new) | the enemy's evolution progress as the seat's player counts it, by the place of the card in `enemy_seen`: for a card the screen marked as the enemy's evolution card (a play of it showed the evolution cycle diamond, or it was played evolved), the plays that showed the diamond since its last evolved play, held to the need, and the need, its evolved row's cycle cost as the tables list it (`DarkElixirCost`); -1 and 0 for a card not marked. Counted from the diamonds alone (below), never read from the enemy's own count |

The remaining 8 header integers are reserved and zero.

#### The enemy's elixir as a player counts it

`enemy_elixir` is `ElixirCount`'s count of the enemy's elixir from what the seat's player sees, read from no hidden state.

- **Start.** It starts at the game mode's starting elixir (the battle timeline's row, the same for both sides).
- **Regeneration.** It gains each tick the regeneration the clock's current rate gives, on every tick that steps the entities, held to the maximum (`MAX_MANA` whole elixir). That is `MAX_MANA * 500000 / ElixirFullBarMS` ten-thousandths, the king's own step. The rate is the clock's, the same for both sides. A played row's production stop holds the regeneration for its time, as the king's count does.
- **Plays.** It loses the cost of each enemy play placed, never below 0: the cost of the row it was cast as, or a Mirror's cost with the card it repeats. A play refused at its run or at its placement costs the enemy nothing and is not seen, so it is not counted.
- **Tunnelling plays.** A play whose card the screen does not show at its run, a tunnelling card, costs only once its unit comes up and shows the card, and then as of its run. The count is worked out again from where it stood before that run, as a player who learns the card counts back. So until then it leaves the play out and never tells the card by its cost.
- **Champion abilities.** It loses the cost of each enemy champion ability command that ran and passed, never below 0: the ability of the unit the command named, as the battle charges it, whether or not the ability's slot requested a copy. A champion on another form of its row, such as the hero Wizard while it flies, pays and requests none.

It also counts every elixir gain the game shows the player, at the tick the battle pays it, held to the maximum as the king adds it. The game pops an elixir float text with the amount at the paying unit on both players' screens for:

- an elixir collector's production (a row's `ManaCollectAmount` each `ManaGenerateTimeMs`). A production the battle holds back while its side is at the maximum pays nothing and shows nothing, so nothing is counted until it pays;
- the elixir a unit's death pays its own side (a row's `ManaOnDeath`, the collector's);
- the elixir a unit's death pays the side that killed it (a row's `ManaOnDeathForOpponent`, the Elixir Golem's pieces).

`LadderEnv` hears these as the battle's own reports of each payout (`WorldObserver.elixirCollected`, `deathManaPaid`, `deathElixirPaid`, `ElixirCount.Gains`), which name the side and the amount and nothing of either side's elixir. It adds them after the tick's costs and before its regeneration, the battle's order.

The one gain the game does not show is a champion ability's refund when its last copy leaves within the refund window. There is no float text, and the opponent has no ability button or elixir bar on the screen. It is not counted, so the count drifts from the truth by the refund there and nowhere else.

The king regenerates in its step of the entity tick, so a tick that steps no entities regenerates neither the king nor the count:

- After the match ends (a king's fall, a crown in overtime, the time limit with unequal crowns, or the tiebreaker's end) the battle goes on stepping the entities, and both regenerate at the frozen clock's rate. That lasts until the tick whose end timer reaches the end screen's delay (the locations' `EndScreenDelay`), which steps none, after which the battle stops.
- In the tiebreaker only a step whose clearing removed or killed something runs the update, the idle and draining steps none.

Whether a tick stepped the entities is the match's (`LadderMatch.isLastTicked`), the same on both players' screens, and says nothing of either side's elixir.

On every other deck the count is the truth at every decision and at the last observation, after the end. Tests hold it (`SeatObservationTest`, `EnemyElixirCountTest`):

- over a battle of random plays on both seats;
- over a battle where both seats play an Elixir Collector and an Elixir Golem (production, the collector's death and the golem pieces' deaths all paid);
- over a hero Wizard's ability used while it flies;
- to the last observation of a battle ended by a king's fall, by the time limit with more crowns, by a crown in overtime and through the tiebreaker, each with a side below the maximum at the end.

Another holds that a queen killed while she casts is refunded and the count leaves the refund out, to the last observation after the match ends.

`LadderEnv.elixirCounted(side)` reads the count, for a diagnostic against the truth. The observation never carries the error.

#### The entity rows

| Entity field | What it is |
|---|---|
| `id` | the seat's id of the entity: each seat numbers the entities 0, 1, 2, ... in the order it is first shown them, stable through the entity's life (see Entity ids below) |
| `kind` | character 1, tower 2, projectile 3, area effect 4 |
| `side` | 0 the seat's own, 1 the enemy's, 2 neither |
| `row` | the vocabulary id of the entity's row (a morphed character's current row; an evolved or hero form is a row of its own) |
| `x`, `y` | game units in the seat's frame |
| `hp`, `max_hp`, `shield` (changed) | 0 for an entity without hit points (a projectile, an area effect). An enemy character's `hp` and `shield` are -1 (`Schema.UNKNOWN`) unless the seat's screen draws its health bar (the rule below); its `max_hp` is always shown, as the row and the level are and the card's stats give it |
| `state` | a character's or tower's state, a category, not an amount: the battle core's 17 states, named by number in the metadata's `state_names` (0 `standing`, 1 `moving`, 2 `attacking`, 3 `dashing`, 4 `deploying`, ... 11 `waiting_to_deploy`, 16 `ability_follow_up`; `GridEntityState`). A tower holds states too (0 and 2 seen). -1 (`NO_STATE`) for a projectile or an area effect |
| `flags` | bit 0 `alive`, 1 `hidden`, 2 `invisible`, 3 `underground`, 4 `air`, 5 `building`, 6 `king`, 7 `clone`, 8 `awake`, 9 `no_move`, 10 `no_attack`, 11 `charged`; below |
| `level` | the entity's level, counted from 1 (a projectile's and an area effect's their own, as the core packs it) |
| `dx`, `dy` | the move since the seat's last observation, in game units of the seat's frame: the entity's position now less where that observation showed it; 0, 0 for an entity that observation did not show (new, or not seen then). One decision apart (5 ticks by default), so it is a velocity and a heading |
| `target`, `target_x`, `target_y` | the seat's id of the entity's target (the `id` of the target's row when it has one) and its position in the seat's frame: a character's or tower's reference (`TargetingState.getReference`, what it attacks or walks to), a projectile's target (what it was fired at); -1, 0, 0 for none, or for a target the seat does not see. An area effect has none |
| `deploy_ms` | a character's deploy time left, in ms (`GridEntity.getDeployCountdown`); 0 for other kinds |
| `attack_ms`, `load_ms` | a character's or tower's attack timer (the attack time run, a hit landing whenever it crosses a multiple of the hit speed) and its load countdown (the wind-up still to run), in ms, as its targeting holds them (`TargetingState`); 0 for a projectile or an area effect |
| `speed_percent`, `hit_speed_percent` | a character's or tower's movement speed and attack pace after its buffs, in percent of its own (`BuffComponent.speed(100)`, `hitSpeed(100)`): 100 with none, more under a boost, less under a slow, 0 under a stun or a freeze; 0 for a projectile or an area effect |
| `buff_count`, `buff_0`, `buff_ms_0`, `buff_1`, `buff_ms_1`, `buff_2`, `buff_ms_2` (changed) | the buffs listed on a character or tower, and three of them: those that stop it moving or attacking (a stun, a freeze) first, then slows, then other buffs with a time, then buffs that never run out, each group oldest first (`BuffSlots`), so a fresh stun or slow is never pushed off the slots by buffs a unit carries for its life (an evolution's or a hero's own). Each slot is the buff's id in the buff list (the metadata's `buff_names`, apart from the row vocabulary, so no card id moved) and its time left in ms (-1 for one that never runs out); unused slots -1, 0. A projectile or an area effect lists none |
| `aim_x`, `aim_y` (changed) | a projectile's aim, the point its flight ends at, in the seat's frame; 0, 0 for other kinds. For an enemy spell launched from the king, -1, -1 until it lands: nothing marks where it will land |
| `time_left_ms`, `radius` | an area effect's life left in ms (its countdown) and its radius at its countdown now in game units (the radius its update hits with, growing from the row's radius to its maximum where the row sets one); 0, 0 for other kinds |
| `ability_ticks` (new) | the ticks since a character's champion ability last ran (both players see the champion cast and what the ability does), -1 for none and for other kinds. An ability is no play: it enters no enemy play row |

The flags:

- `alive` is the entity in play, for every kind: a character or tower with hit points left (a fallen tower stays listed for a while with `alive` 0 and 0 hit points), and every projectile and area effect listed (version 2 left it 0 for those two kinds).
- `hidden`, `invisible` and `underground` are the battle core's targeting tests of a character, on the seat's own rows and the enemy's alike. The screen draws a hidden building lowered and an invisible unit faint, and an enemy unit underground as a trail (below).
- `awake` is set on a tower whose targeting the tags it carries do not keep off (`TowerEntity.isInactive`): a princess tower, and a king tower once woken. At the first observation, before the first step has folded any tower's tags in, a king reads awake too.
- `no_move` and `no_attack` are a character's or tower's tags (as its last pre-hook folded them in) that forbid it to move or to attack.
- `charged` is a character whose charge is complete.
- `clone` is a clone character or a clone's area effect.

#### Entity ids

An id in a seat's observation (`id`, `target`, `ability_unit`) is the seat's own number of the entity, not the battle's. Each seat numbers the entities 0, 1, 2, ... in the order its observation first names them (`SeatIds`), in the order the observation is written (the champion rows, then the entity rows, a row's target as it is written).

- An entity keeps its id for its life, even while the seat does not see it, and no other entity is given it in the episode.
- The two seats number apart, so one entity may have two ids, and a reset starts both afresh at 0.
- An entity hidden when it is made and shown later takes the next free id when it is first shown. One shown and hidden again keeps its id, as a player who saw it remembers it.

So an id links a row to the rows and fields that name it, and says nothing of the entities the seat was not shown. The battle's own ids are global and in creation order. Until 2026-10-18 the observation wrote them, and a gap between two of them told a seat that entities it was not shown (an enemy unit hidden, tunnelling or waiting its turn to deploy) were made between the two.

The id rule is versioned apart from the layout, as the mask rule is. Rule 2 (`Schema.ID_SEAT_FIRST_SIGHT`, the rule written now) and rule 1 (`Schema.ID_BATTLE`, the battle's ids, written by schema version 3 before the change) write the same fields with the same meaning; only their values differ. So the schema's version stays 3, and the metadata exports the rule as its own `id_version`, outside `schema` and `config` (absent in an export written before the rule, and for a synthetic vector).

A saved export of version 3 still passes `check_metadata`, and label folders written before keep their use. An encoder that reads no id (the ids are links, not amounts) reads both alike. One that embeds the ids' values should not mix rows of the two rules.

#### Row order and overflow

Towers and characters are listed first, in the battle's holder order (oldest first), then area effects and projectiles, newest first. When the 200 rows are full the oldest projectiles and area effects are left out before any character, and the newest characters only once those are all out. `entity_overflow` counts what was left out.

#### What a seat is shown of the other side

It is what its player sees on the screen: the entities on the field, the enemy's crowns and towers, the enemy's cards as the screen showed them, its last plays as the screen showed them, its evolution progress counted from the diamonds the screen shows, and its elixir counted from what the screen showed. The enemy's hand, cycle, elixir, evolution count and commands not yet run are not shown, and nothing of them feeds the mask.

Which characters and towers a seat sees, and what of them, is one rule, in one place (`SeatView`): the entity rows, the overflow count, the target fields, the buildings the deploy mask reads and the health bar fields all ask it.

- **Every unit but one waiting its turn.** A seat sees its own side's entities, every tower, and every enemy character but one waiting its turn to deploy (state 11, `waiting_to_deploy`, the later units of a group play; the screen draws none of it). A hidden building (a Tesla lowered while it has nothing to attack) is drawn in its lowered pose, and an invisible unit (a Royal Ghost, an Archer Queen cloaked, a Suspicious Bush, the evolved Ghost forms) faint: both are shown, with their `hidden` and `invisible` flags. A target field may name them.
- **A unit underground is its trail.** An enemy ground unit travelling underground is drawn as a moving dirt trail from its king tower. It travels underground when tunnelling to its placement (state 6, as the Miner and the Goblin Drill do from their king tower at the play's run), or when routing underground where its ability sent it (state 7 on a row without `IngamePathfindVisible`).

  Its row is at its current position, with its move, its state and the flags `alive`, `hidden` and `underground`, and nothing else: its `row`, `max_hp` and `level` are -1, its `hp` and `shield` -1, no target, timers or buffs. Nothing is drawn where it will come up, so no field shows that point before it does.

  The two tunnelling cards' trails are different effects, but whether a player tells them apart by eye is not settled. So the trail names no row until the unit comes up (`SeatView.TRAIL_NAMES_CARD`, false, the one switch for both the row and the play's card). The trail's speed differs between the two cards and shows in `dx`, `dy`, as it does on the screen.
- **Health bars.** An enemy character's `hp` and `shield` are shown only while the screen draws its health bar, by the table below. Elsewhere they are -1 (`Schema.UNKNOWN`), and never while the unit is underground or waiting its turn to deploy. Its `level` is always shown (but on a trail): the level badge is on the screen whether or not the hit points are.

  The table gives, per kind of row, when the player sees the enemy unit's hit points and shield.

  | Row | Hit points and shield on the enemy's screen |
  |---|---|
  | A character or building row that does not set `HideHealthbar` (it names a health bar row, or none and is given a default one by its hit points) | always: every health bar row such a row reaches shows on the enemy's screen always; an unhurt unit's hit points are its `max_hp` |
  | A row that sets `HideHealthbar` and whose starting client actions name a health bar (`Healthbar_Medium` and the like: most champion, hero and evolution rows, the Dark Prince, the Guards, the Archer Queen) | from the unit's first damage on, and for its life, healed back to full or not. Damage is its hit points off their maximum, or its shield changing while the row has a shield (a hit, a lost shield and a shield that grows all count). Hidden while the unit runs an action that sets `HideHealthbarWhileRolling` (the Barb Log hero's roll) |
  | A row that sets `HideHealthbar` and whose starting client actions name no health bar (dummies, ghosts and helper objects: 7 character rows in 16.402.18, 16.402.19, 16.402.21 and 16.402.22) | never |

  A player sees an enemy Dark Prince, Guards or Archer Queen without a bar until it is first damaged, and with one from then on, also once healed back to full. The damage is watched at every tick, not only at decisions, so a unit hurt and healed between two decisions shows its bar too.

  Until 2026-10-18 the rule showed these rows' bar only while the unit was hurt, never for a row naming no health bar, and a building's only while it was hurt or shielded unless it had a life time or an elixir production. Those differed from the screen only for an unhurt unit, whose hit points are its `max_hp`. The observation's layout and schema version 4 are unchanged. `max_hp` stays shown: the row and the level are, and the card's stats give it. Own rows and towers are unchanged.

  The health bar rows themselves are not among the game tables the environment reads. The rule takes their show-to-the-enemy-always column as set, which holds for every health bar row a character or building can reach in the tables of 16.402.18, 16.402.19 and 16.402.21. Nor are the client actions: a starting client action builds a health bar when its name holds `Healthbar`, as every one does in those tables.

#### What the screen shows of an enemy play

It is given in the play rows and the cards seen, by its kind, read from the row it was cast as (`DeployCard`; no card is listed by name):

| Kind | Enters the rows | Card, cast row, form | Point |
|---|---|---|---|
| `troop` (a troop or building card that does not tunnel) | on its run tick | at once (its first unit is drawn) | the placed point, at once (the enemy deploy timer is drawn there) |
| `area` (a spell cast as an area effect) | on its run tick | at once | the placed point, at once |
| `thrown` (a spell thrown onto the point, the Log and the Barbarian Barrel) | on its run tick | at once | the placed point, at once (it rolls through it from a short way behind) |
| `launched` (a spell launched from the king tower, the Fireball, the Rocket, the Arrows, the Goblin Barrel, the Snowball) | on its run tick | at once (its projectile leaves the king tower) | the placed point, on the tick its first projectile lands; until then its projectiles' `aim_x`, `aim_y` are -1 too |
| `tunnel` (a troop card whose unit tunnels, the Miner, the Goblin Drill) | on its run tick, as a tunnel with neither card nor point | when its unit comes up (state 6 ends) | where its unit came up, on that tick; never the placed point before |

A Mirror's play is listed with the card it repeated as its cast row and the `mirror` flag (the Mirror's own particle is drawn at the point). A champion's ability is no play: it shows on the champion's own row (`ability_ticks`).

An evolution slot's card played in its base form shows the evolution cycle diamond at the placed point, a generic mark that names no card. The row's `evolution_diamond` flag is set once the play's card and point are shown and the diamond's own delay has run: the deck card row's `EvoInfoDelay`, and where the row sets an `EvoInfoSpeed` the straight distance from the playing side's king tower to the point at that speed, in game units a tick. So it never shows the point early. It then marks the card as the enemy's evolution card and counts one diamond in `enemy_seen_evolution`, held to the need.

A play shown evolved marks the card too and starts its count again from 0, as does a hero play of a marked card. The count is the king's own while every play of the card showed its diamond. A play refused at its run, which the king counts and the screen does not show, is not counted.

#### Tests of what a seat is shown

Tests hold the rule (`LadderEnvTest`, `SeatObservationTest`, `HealthBarTest`):

- A seat's buffers stay byte for byte the same when the enemy holds another deck and loses its elixir every decision.
- While an enemy command waits its run, the seat's buffers are byte for byte those of a battle where the enemy did not play.
- An enemy's hidden Tesla keeps its row, where it stands, with the `hidden` flag (until 2026-10-18 the rule left it out, as the core's targeting test does).
- An enemy Miner shows from its run a trail row at the unit's position with no row name, hit points or level, and a tunnel play with neither card nor point. The cards seen and the elixir count are those of a battle where it was not played. When it comes up it shows the card, the point where it came up and the count equal to the enemy's elixir.
- An enemy Fireball shows its card at the run and its point, and no aim on its projectile, only once it lands.
- An enemy Archer Queen's ability shows on her row, cloaked or not, and adds no play. Her hit points are -1 while she is unhurt and shown once she is damaged.
- An enemy Dark Prince's hit points and shield are -1 while it is unhurt and shown once its shield is hit, its hit points still at their maximum.
- `HealthBarTest` holds the bar rule:
  - an Archer Queen damaged keeps her hit points shown once healed back to full, also when hurt and healed inside one decision;
  - a Dark Prince whose shield grows shows its bar;
  - on tables altered by hand, a Knight row that sets `HideHealthbar` and builds no bar never shows its hit points, and one naming no health bar row shows them unhurt.

  The roll's rule is held by no test yet: neither the ability action nor the player's commands reach a Barb Log hero, which its barrel leaves rather than its play.
- An evolution slot's Knight played in base form shows the diamond and is counted from it to the evolved play, equal to the king's count. A battle where the Knight is in no slot is seen byte for byte alike but for the diamond and the count.

A random battle checks every row against the core (the health bar rule from the tables, the buff slots' order, a trail's fields, a hidden aim). Another holds the ids: the ids a seat is first shown at a decision follow on from those before with no gap, also when the battle made an enemy unit the seat was not shown between two it was. An id names one entity for the episode, a target names its row, and the two seats number apart.

#### Reading the observation

What a trainer would misread without a note:

- `enemy_play_ticks` is 1 at the earliest, never 0: a play runs in a step, and the observation after that step counts it 1. At the default cadence the first value a seat sees is 1 to 5. `pending_ticks` 0, by contrast, is a command that runs in the next step.
- `awake` is 1 on both kings at the first observation of an episode only: no step has folded the towers' tags in yet. From the first step on, a king reads 0 until woken.
- `target` may name an id that is in no row (past the row cap, or a projectile's target already gone): use `target_x`, `target_y` for the position, the id only to link.
- `dx`, `dy` are per decision, so their scale follows `decision_ticks`; 0 also means "not shown last time", not only "did not move".
- `attack_ms` is elapsed time, `load_ms` time still to run; `load_ms` 0 is also a fresh unit.
- `buff_ms_k` -1 means forever, 0 an unused slot (with `buff_k` -1) or a buff at its last tick: read the id first.
- `state` -1 for a projectile or an area effect, 0 to 16 a category; `hp` 0 for those kinds and for a fallen tower (`alive` 0 then); `hp` -1 is an enemy whose health bar the screen does not draw, not a dead one.
- A row whose `row` is -1 is an enemy trail underground: its `max_hp` and `level` are -1 as well.
- An enemy play row's card, cast row and form are -1 until the screen shows the card, and its point -1, -1 until the screen shows one (`enemy_play_flags` tells which); `enemy_play_kind` is shown from the run.
- `enemy_elixir` is a count, never the truth: it leaves out a champion ability's refund, and a tunnelling play's cost until its card shows (then it is worked out again as of the run, so the count may jump).
- Own and enemy rows differ in what the screen shows of the enemy: `hp` and `shield` without a health bar, a trail's fields, a launched spell's aim.

### The mask

`ACTIONS` = 2307 bytes a seat, 1 for an action the seat may take now. It is what the player's screen offers (the layout and the mask rule are as in version 2):

- The wait is always open.
- Before the match's start cooldown (`LadderMatch.getStartCooldownTicks()`, from the game mode's row) nothing else is.
- After it, a hand slot is open when the client's try of its card passes (`PlayerCommands.tryCard(side, slot, level)`: the match's gates, the elixir less the promised costs, no play of the card waiting, and a Mirror with a card to repeat). Then the tiles of its 576 its card lands on without being moved are open (the mask rule, below). A try the core refuses as not modelled closes the slot.
- A champion slot's ability action is open when the client's try of an ability command naming the slot's unit passes (`PlayerCommands.tryAbility(side, unit)`: the same gates `useAbility` applies, in order, with the elixir less the promised costs, and nothing given).

  The unit is the one of the play the slot follows (its deploy count) whose row names the champion's ability, not a clone, by the name `Standard1v1Battle` gave it. A slot that follows no play, or a champion no play of the side made, keeps its action closed. The match does not gate an ability command on its start cooldown; no champion stands before it anyway.

**The mask rule.** Which tiles of an open slot are open is the mask rule, versioned apart from the schema (`Schema.MASK_LANDING_TILES` 2, the default, and `Schema.MASK_ALL_TILES` 1; `EnvConfig.maskVersion`):

- Rule 2, the landing tiles: a tile is open when the card, dropped at the tile's centre, lands on that tile, not moved. The placement search the screen runs on a drop (the one the run makes, `CardPlacement.search`) ends on its first ring there, its snapped point legal.

  The search is the battle core's, on the deploy mask of the card's own row (`CanDeployOnEnemySide`, `CanPlaceOnBuildings`, `CanPlaceOnWater`, the margin columns, the row limits and the whole-row filter) and of the buildings and towers on the field. Nothing is listed by card. So:

  - a troop or a building gets its own half less the river, the towers' and buildings' footprints and the cells its row may not take, and the enemy's pocket once a princess tower falls (its box no longer closes it);
  - a card whose row lets it deploy on the enemy side (most spells, the Miner) gets the whole arena but what its search refuses;
  - a building of an even footprint lands on the tile's corner of the lowest coordinates, one tile for each corner it may take.

  `PlayerCommands.searchCards` names the card the search is handed (an evolved or hero row, a variant's option, the card a Mirror repeats). `PlacementSearch.landingTiles` gives the tiles. The field the mask reads is the one the seat sees: every building of both sides, a hidden Tesla lowered included (no building waits its turn to deploy), so it is the whole field's buildings and towers. The tiles are kept per seat and card until the buildings and towers the seat sees change.
- Rule 1, every tile: every tile of an open slot is open, as a drop anywhere is moved to the tile the placement search finds. It is the rule before rule 2, kept for models trained under it.

The mask rule is not part of the observation, and both rules use the same 2307 actions, so a change of rule leaves the schema's version where it is. The metadata exports it as its own `mask_version`, outside `schema` and `config`, and a model trained under one rule loads and plays under the other.

Measured on 2026-10-18 on one Mac (Apple M4 Pro), data version 16.402.21, under schema version 2, over 320 battles of random play. Each seat took a random open action three decisions in ten, each side a deck of eight cards drawn from the tables' card rows; 142 battles were cut short by a card the battle core refuses. Every fourth decision, rule 2 closes:

- 58 percent of an open troop slot's tiles (240 of 576 open);
- 70 percent of a building's (176 open);
- 14 percent of a spell's (496 open; the Fireball, the Zap and most spells everywhere, the Log, the Heal and the Royal Delivery their own half, the Goblin Barrel 544).

The Miner, the one troop of the sample with more than half the arena, keeps 450. On sampled decisions, 21,312 tiles were compared with the battle core's own screen search on the whole field: no tile the card lands on unmoved is closed, and no open tile is moved. One thread steps about 8 percent fewer decisions a second under rule 2 (20,100 to 20,400 against 21,600 to 22,300, 64 battles, other work running, 1-minute load average 4.0 to 4.2).

The mask reads the seat's own hand, elixir and waiting commands and the buildings and towers the seat sees, so it shows nothing the seat does not see. A play the mask let through is given (the try and the giving are the same check), and runs 20 ticks later against the battle as it stands then. So it may still be refused at its run, as a player's can: `last_run_refusal` shows it, and its cost stays held back for its promise.

### The action

One integer, unchanged since version 2:

- `0` the wait;
- `1 + slot * 576 + row * 18 + col` for a play of hand slot `slot` dropped at the centre of tile `(col, row)` of the seat's frame, `col` 0 at the seat's left and `row` 0 at its own back line;
- `FIRST_ABILITY + k` (2305 and 2306) for the tap on the ability of the king's champion slot `k` (`Schema.ability(k)`).

The ability actions come after every play, so the play actions keep their numbers from version 1.

### The info record

`INFO_INTS` = 12 integers an environment: `tick`, `episode_step`, `winner` (0 or 1, 2 for a draw, -1 while running and for an episode cut short), `crowns_0`, `crowns_1`, `fault` (the kind above, 0 for none), `reset`, `illegal_0`, `illegal_1`, `truncated`, `first_side`, `not_modelled`.

### Refusal codes

`last_refusal` holds the giving's refusal as `PlayerCommands.Refusal` numbers it:

- 0 `NONE`, 1 `MATCH_OVER`, 2 `KING_DEAD`, 3 `NOT_IN_HAND`, 4 `START_COOLDOWN`, 5 `CARD_PENDING`, 6 `NOT_ENOUGH_ELIXIR`, 7 `NO_TILE`;
- then the ability refusals, 8 `NO_CHAMPION`, 9 `COOLDOWN`, 10 `NO_CHARGES`, 11 `NOT_CHAMPION`, 12 `ABILITY_PENDING`, 13 `CLONE`, 14 `FROZEN`, 15 `ABILITY_DISABLED` (an ability command's elixir gate and dead king are reported as 6 and 2);
- and two of the environment's own: 100 `MASKED` (an action the mask closed, never given) and 101 `NOT_MODELLED`.

A change of the core's refusals moves the schema's version (`SchemaTest` pins the list).

### The vocabulary and the metadata

The vocabulary numbers every row name of the tables the battle reads its cards, characters, buildings, projectiles and area effects from, sorted, so a card and the character it places share the id of their one name.

The buffs an entity row names are numbered in a list of their own, `buff_names`: every buff the battle core can list, the rows of `character_buffs` and the buff rows the actions' buff spawns write inline, sorted by name. Being apart, they moved no card's id.

An id is stable within a data version only: a row another version adds moves every later id. So `VectorEnv.metadataJson()` (or `writeMetadata(path)`) is to be kept with every run and checkpoint. It holds:

- the schema's version, sizes, field names, flag, refusal, fault and state names (`state_names`);
- the scales (`scales`);
- the configuration;
- the vocabulary keyed by name (`names`, the name of each id) with the tables' `data_version`, `content_sha` and a `hash` of the list;
- the buff list (`buff_names`, `buff_hash`);
- `max_elixir` (the tables' `MAX_MANA`).

A model trained on one export is remapped by name to another. `Vocabulary.checkSame(export)` refuses an export whose ids or buff list these tables would read differently, naming the first moved name.

The scales an encoder reads the amounts by (`schema.scales`, the same for every data version):

| Scale | Value | What it scales |
|---|---:|---|
| `elixir_scale` | 10,000 | ten-thousandths to a whole elixir: `elixir`, `elixir_reserved`, `enemy_elixir` |
| `tile_units` | 1,000 | game units to a tile |
| `width_units` | 18,000 | the arena, every position |
| `height_units` | 32,000 | the arena, every position |
| `ms_per_tick` | 50 | |
| `percent` | 100 | `speed_percent`, `hit_speed_percent` |
| `max_state` | 16 | the last state number |

The most elixir is the tables' own (`vocabulary.max_elixir`, 10 whole elixir on every data version so far). Every ms field is milliseconds and every position game units. `log1p` of the hit points and the row's own maximum for a share are the encoder's to choose.

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

The buffers are direct `ByteBuffer`s in the machine's byte order, laid out environment after environment and seat after seat:

| Buffer | Shape | Type |
|---|---|---|
| `observations()` | `count x 2 x 7024` | int32 |
| `masks()` | `count x 2 x 2307` | bytes |
| `actions()` | `count x 2` | int32 |
| `rewards()` | `count x 2` | float32 |
| `dones()` | `count` | bytes |
| `infos()` | `count x 12` | int32 |

They are made once and valid for the vector's life, so a Python bridge maps them once and moves no bytes in a step.

- `reset(seeds, String[] deck0, String[] deck1)` gives each battle its own decks (eight names each, battle after battle).
- `reset(seeds, deck0, deck1, int[] slots0, int[] slots1)` adds each deck's slot flags (eight a deck, battle after battle, `Schema.SLOT_EVOLUTION` 1 and `Schema.SLOT_HERO` 2; null for none, every card played plain, as the resets without them do). The shape is checked before any battle is touched, and a card the battle core does not take in a slot ends that battle's episode as a refusal fault as its match starts.
- `resetEnv(i, seed)` resets one battle on its decks and slot flags.
- `setAutoreset(false)` leaves finished battles for the caller.

`VectorEnv.ladder(Path, ...)` takes a tables folder directly. The folder is one explicit argument, with no default.

With more than one thread the battles are stepped on a fixed pool of daemon threads, each thread taking a contiguous share; nothing is shared between battles but the read-only tables and vocabulary.

`VectorEnv.replayJson(i)` writes battle `i`'s episode so far as a replay of the tables' data version (`ScenarioWriter`, as a fault bundle's `replay.json`): its seed, decks, levels, towers and every command its seats gave with the ticks they were given and run on, its `endTick` the battle's tick now. The debug visualizer's replay viewer opens it, and the replay labeller below reads it as any replay.

## Labelling replays

Behavioural training (imitation) learns a policy from games people played: at each decision, what the player saw and what the player did. `ReplayLabeller` turns a replay into exactly that, in the environment's own schema: it steps the replay's battle and writes, at every decision of each seat, the observation and mask `LadderEnv` would show that seat, and as the label the action that gives the command the player gave. A model trained on the rows plays the environment as it is.

The tool holds no data. It labels any replay of a data version the battle core plays (`docs/compatibility.md`), on the tables of that version, and the rows stay on the machine that makes them.

### A row

A replay gives two rows a decision, seat 0's then seat 1's: the decision's tick, the seat (the game side it plays), its observation (`SEAT_INTS` integers, in the seat's own frame, player-visible only) and its mask (`ACTIONS` bytes), the label (an action of the schema, 0 the wait) and the row's flags. Every decision is a row, the wait-only ones included; a trainer that wants only real choices keeps the rows whose mask opens more than the wait.

### The battle

The replay's battle is built as `ReplayBattle.build` builds it (seed, towers, each player's data, decks, levels and slot flags), but with none of its commands queued. Each command is given on its given tick, the tick counter it was stamped with (the replay's `t`, never inferred). It is given through `PlayerCommands`, from the point the replay records and at the level its deck lists, by the same code `LadderEnv` gives its own plays with.

So what each seat is shown is what the environment shows a policy: the promised elixir, the plays waiting, the champion buttons, the mask from the client's try.

A command is queued as the replay states it (as `ReplayBattle` does) when:

- the client's try refuses it at its giving;
- the screen's placement search would move its recorded point;
- or the battle core does not model its giving.

So the battle stays the replay's. Such a command is counted, and flagged.

Each play's item is checked as it runs against the replay's (`ReplayBattle.checkItems`). A replay whose items or reading the battle core refuses stops there, with its rows so far and the reason.

### The alignment

Decisions fall every `decisionTicks` ticks from tick 0, as the environment takes them: 0, 5, 10, ... by default, every tick at 1. A command belongs to the decision whose window, from its tick up to the next decision's, holds its **given** tick; the run tick, 20 later, plays no part. The row of a decision holds the observation and mask written at its tick, before any command of its window is given, as a policy's decision is taken.

### The label

- **A card play**: the hand slot its card holds in the row's observation, and a tile of the seat's frame. A replay records the tile the screen's search found: a tile centre (less 1 on a coordinate the symmetrical snap shifts) or a building's tile corner (see [When a player's command runs](battle-core.md#when-a-players-command-runs)).

  So the label's tile is the first of the tiles touching the recorded point, the one holding it first, whose centre the screen's search, run on the battle at the giving, sends to the recorded point. Driven from the label, the play is then sent on the replay's point. When no tile's centre does, the tile holding the point is named and the row flagged.
- **An ability command**: the ability action of the champion slot whose unit is the one the command names (by its object id).
- **Two commands of one seat in a window**: the first, in the replay's order, is the label; the others are counted as collisions and the row flagged. At 1-tick decisions only two commands given on one tick collide; at 5 ticks any two within five ticks do.
- **A command the action space cannot say at the row** is labelled the wait and the row flagged:
  - its card is not in the row's hand (at 5 ticks, a card that came into the hand within the window);
  - no champion slot follows the unit an ability command names (a champion an action spawned, or an object id that names no slot's unit);
  - or a variant card's recorded option is not the one the client would pick now (the action names no option).
- **Under the landing mask rule** (rule 2, the labeller's default; `withMaskVersion` and `label_replays(mask_version=...)` pick the rule) the tile the play landed on comes first. That is the tile of the game's grid holding the recorded point, when the screen's search from its centre sends to the point, which it does when the card lands there unmoved.

  That is the one tile the row's mask opens for the point. So a tap the screen moved before sending it is labelled with the tile it landed on, not the tile the player touched (which the replay does not record). The tiles above follow only when it does not send there.

  On a building's tile corner the tile holding the point in the seat's frame is the corner's other neighbour on a mirrored axis. Its centre may be moved onto the corner, and under rule 1 that tile is the label.
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

The counts of a replay (`ReplayLabeller.Counts`, by name in `COUNT_NAMES`):

- `decisions`, `rows`, `commands`, `plays`, `abilities`, `labelled`, `label_masked`, `collisions`;
- `inexpressible` (`not_in_hand`, `ability_unit_unknown`, `option_differs`);
- `point_unmatched`;
- `given_as_recorded` (`refused_at_giving`: the client's try refused it, its card was not in the hand, or no champion slot names an ability command's unit; `point_moved`; `not_modelled_at_giving`);
- `after_end` (commands given on or after the tick the labelling stopped at: the match's end, the replay's `endTick`, or a refusal);
- `order_differs` (windows where both seats are labelled and the replay gave the two in the other order than the environment gives two seats' actions, side 0 first on even decisions);
- `rows_unpromised`.

### The proof

`ReplayLabeller.drive(replay, labels)` takes the labels alone and plays them as the environment plays actions: a `LadderEnv` reset on the replay's setup, each decision's two labels given by `step`. It checks the driven battle against the replay's own (`ReplayBattle.build`) at every decision:

- the two rows before it against the labels' rows;
- the commands given in it against the replay's (kind, side, card, level, given and run tick, point, option, the unit an ability command names);
- each play's item as it runs;
- the battle's state after it (tick, the battle's random state, each side's elixir, hand, queue and crowns, and every entity's id, kind, side, row, position, state and hit points).

It reports the first difference of any kind and goes on past a row or a command's point that differ. It stops at the first command that differs otherwise, the first item and the first state:

- `reproduced` when everything matched exactly;
- `stateReproduced` when every command matched but perhaps for its point and the state matched at every decision to the end (a play sent from the tile's centre on a point next to the replay's, which its run places where the replay's was).

At 1-tick decisions a replay whose commands are all expressible, with no collision and no order the alternation reverses, reproduces exactly. An episode of the environment written as a replay is labelled with its own rows and actions, and driven back to the same battle (`ReplayLabellerTest`, `test_labels.py`).

At 5 ticks a command given between two decisions is driven at the decision before it, so it is stamped and run up to 4 ticks early. The driven battle leaves the replay's at the first such command. The rows remain the replay's, which is what training reads.

### Using it

```java
ReplayLabeller labeller = new ReplayLabeller(tables, 5);           // replays the game client records
ReplayLabeller labeller = ReplayLabeller.of(tables, 1, "generated"); // generated test cases, every tick
for (ReplayFiles.Source source : ReplayFiles.read(Path.of("<file>"))) {
  ReplayLabeller.Labels labels = labeller.label(source.document()); // rows, counts, stop tick, refusal
  ReplayLabeller.Drive drive = labeller.drive(source.document(), labels);
}
```

`ReplayFiles.read` reads a replay document (`.json`) as it is, or a crawl's JSON Lines output (`.jsonl`, plain, zstd or gzip, found by its first bytes) as the desktop replay viewer reads it. Those are format 1 records, the replay as a string, each given the capture block its record names (the battle's data version and content sha). So a record is read only on the tables of its own data version and refused on another's. A record that names candidate versions, or cannot be read, is listed with why.

`labeller.metadataJson()` is the environment's metadata export with the labeller's decision interval, to be kept with the rows.

### Measured on the reference battles

Measured on 2026-10-16, under mask rule 1 (every tile), on one Mac (Apple M4 Pro, JDK 17.0.14, Python 3.12.7) with other work running (1-minute load average 4.6 at the start and 9.3 at the end of the 16.402.19 run, 5.6 and 8.2 of the 16.402.21 run). The runs are `label_replays` on 4 threads over every reference battle of the data repository of each version, labelling alone for the rate, then again with `check`.

The reference battles are of two kinds:

- replays the game client recorded (25 of 16.402.19, 33 of 16.402.21);
- generated test cases (967 each), whose plays are dropped on arbitrary points and some before the start cooldown, as no player's screen sends them.

Only the first kind is players' games.

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

The recorded replays of 16.402.19 at 1-tick decisions:

- 1,629 commands (1,538 plays, 91 ability commands), 1,499 labelled;
- 16 labels closed by the mask;
- 1 collision;
- 16 inexpressible (2 cards not in the hand, 14 ability commands whose object id names no champion slot's unit);
- 43 points no tile centre's search sends;
- 73 commands given as recorded;
- 113 after the end;
- 5 order flips.

At 5 ticks the same replays have 76 labels closed by the mask (a command's window opening before the elixir for it was there), 22 order flips and still 1 collision. Those of 16.402.21 are alike (2,298 commands, 2,163 labelled at 1 tick).

What the drive found, at 1-tick decisions: every recorded replay whose labels are all expressible, open in their masks, with no collision and no order flip reproduces the replay's battle to its end (13 of 13 of 16.402.19, 14 of 14 of 16.402.21). It does so exactly, every command's point included, when no point is unmatched (9 of 9, 10 of 10).

Every other replay stops at a flagged row or an order flip:

- an ability command whose unit no slot names;
- a label the mask closed (the client sent a play the battle core finds short of elixir at the giving, or one given after the match was decided);
- a variant card's option;
- two seats' commands on one tick in the other order than the environment's alternation.

The 43 unmatched points of 16.402.19 lie at the two middle tile columns, sent at 8500 and 9499 where the battle core's screen search gives 8499 and 9500 (mostly in an open pocket), and three into a pocket the battle core finds closed at the giving. The run places them where the replay's were, so the state still matches. Both, and the client's try at the giving, are filed for tracing on 16.402.17 rather than guessed.

At 5-tick decisions no recorded replay is driven exactly: its first command given between two decisions is given up to 4 ticks early.

Under schema version 3 (2026-10-18, mask rule 2, the labeller's default), labelling and driving every recorded replay of 16.402.18 (25), 16.402.19 (25) and 16.402.21 (33) at 1-tick and at 5-tick decisions gives each replay the same counts and the same drive outcome (exactly driven, state reproduced, first divergent tick, stop) as under version 2.

At 1 tick 12 of 25, 9 of 25 and 10 of 33 are driven exactly, and 18, 12 and 13 have the state reproduced. Over the 970 generated test cases of each version at 5 ticks, every count and every drive outcome is the same too. Only the rows the driven battle writes alike after its first divergence are fewer in 3 cases, as the rows carry more detail (timers, moves) that differs once the battles differ.

Under id rule 2 (entity ids per seat, see Entity ids above), the same runs give every replay and generated case the same counts, the same drive outcome and the same rows matched as under rule 1.

Under schema version 4 (2026-10-18: the screen's visibility rule, health bars, play rows and evolution count), the same runs on 16.402.18, 16.402.19 and 16.402.21 (the recorded replays at 1-tick and 5-tick decisions, the 970 generated cases at 5 ticks) give every replay and case the same counts, the same number of rows, the same drive outcome and the same rows matched as under version 3 with id rule 2.

At 1 tick 12 of 25, 9 of 25 and 10 of 33 recorded replays are driven exactly, and 18, 12 and 13 have the state reproduced. At 5 ticks 20 of 970 generated cases are driven exactly, and 253 have the state reproduced.

## What is not offered yet

- **Champion abilities of spawned champions**: an ability action names a unit a card play of the side made (`PlayerCommands.useAbility` names units by the play's name); a champion slot that follows a champion an action spawned keeps its action closed.
- **The enemy's champion slots and slot flags**: a seat sees only its own; the enemy's champion shows on the field as an entity.
- **Deck slot rules**: the environment checks the slot flags' shape only; which cards a slot may hold, and how many slots a deck has, are the battle core's to refuse as the match starts.
- **Levels**: every card is played at one level (`EnvConfig.level`, 11 by default) and every tower at `EnvConfig.towerLevel`; the header's `deck_level` and each row's `level` show them, and a labelled replay's vary.
- **Whether a tunnelling card's trail names the card**: the trail row and the tunnel play name no card until the unit comes up (`SeatView.TRAIL_NAMES_CARD`); a player may tell the two trails apart by eye, which is not settled.
- **The health bar rows' own columns and the client actions**: the rule takes every health bar row a character or building reaches as shown to the enemy always, and a starting client action as building a health bar when its name holds `Healthbar`, as the tables of the data versions held have them; neither the health bar table nor the client actions are among the game tables the environment reads.
- **An Arrows wave waiting its delay**: the screen hides a later wave's projectile while it waits; its row is listed (without its aim while the play's point is not shown).
- **A champion's refund in the enemy elixir count**: the game does not show it to the other player, so the count leaves it out (see the count's rule); counting it as inferred from the ability's use and the champion's death is a decision left open.
- **The opponent's hero and champion before they are played**, and the enemy's champion slots: only once a trace shows the screen shows them.
- **Static per-row features** (cost, hit points and damage at the level, speed, range, targets) for rows a model never saw; the Python package's `unit_facts` and `spell_facts` cover the scripted bot's needs.
- **A projectile's time to impact**: the row gives its aim point and its target, not its flight time left, which the core does not hold as one number.

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

Two inputs, each one explicit argument with no default and no environment variable:

- `install`, the module's installed distribution (`env/build/install/env`, whose `lib` folder holds the env module, the battle core and their libraries);
- for Ladder battles `tables`, the tables folder of a data version as the build makes it (`<cache>/tables/<data version>`, `~/.crforge/tables/<data version>` by default).

The JVM starts once per process with the jars of the first `install`; another folder later in the same process is refused. JVM options (heap, collector) go in `jvm_args` of the first vector.

### The vector

`VectorBattle(count, threads, install=..., tables=..., config=LadderConfig(...))` wraps `VectorEnv.ladder`; `engine="synthetic"` wraps synthetic environments instead and needs no tables. Its arrays are views of the buffers, made once:

| Array | Type | Shape |
|---|---|---|
| `observations` | int32 | `(count, 2, 7024)` |
| `masks` | uint8 | `(count, 2, 2307)` |
| `actions` | int32 | `(count, 2)` (written before `step()`) |
| `rewards` | float32 | `(count, 2)` |
| `dones` | uint8 | `(count,)` |
| `infos` | int32 | `(count, 12)` |

`reset(seeds, deck0, deck1, slots0=None, slots1=None)` takes one deck for all battles or one per battle, by card row name (`"Archer"`, as the tables name it), and each deck's slot flags the same way (eight values, `schema.slot_evolution` and `schema.slot_hero`; None for none).

The vector also has `reset_env(i, seed)`, `set_autoreset(flag)`, `info(i)` (the info record by name), `seat_info(i, seat)` (below), `fault(i)`, `fault_counts()`, `fault_kind_counts()` and `replay_json(i)` (battle `i`'s episode so far as a replay document, `VectorEnv.replayJson`).

`LadderConfig` fields left unset take `EnvConfig.DEFAULT`'s. `LadderConfig(fault_bundles=<folder>)` writes fault bundles ([Fault bundles](#fault-bundles)), and `fault(i).bundle` names a written bundle's folder.

A synthetic vector takes `synthetic_entities`, `synthetic_episode_steps`, and `synthetic_fault_at` with `synthetic_fault_kind` (`refusal`, `bug`, `env_bug`): every episode faults at that decision, so the fault path can be tested without a battle.

### The schema from the metadata

At start the package reads `VectorEnv.metadataJson()`. The schema's version, sizes and field names give every offset (`schema.header[name]`, the indices of a header field; `schema.entity[name]`, `schema.info[name]`, `schema.flags[name]`, `schema.refusals[code]`). The few codes the metadata does not list (results, entity kinds, the winner codes, the deck size, the arena's size) are read from the `Schema` class. No offset is typed twice in Python.

The package reads schema version 3; a module that writes another version is refused at start.

Every field is read by name:

- `schema.field(observation, name)` gives a header field;
- `schema.columns(observation)` the written entity rows as one column per entity field (`columns["buff_0"]`, `columns["dx"]`);
- `schema.state_names` the state names by number, `schema.scales` the scales;
- `schema.buff_slots`, `schema.enemy_play_rows`, `schema.queue_slots` the new blocks' sizes;
- a Ladder vector's `vector.buff_names` are the buff list the buff slots number and `vector.max_elixir` the most elixir (empty and None on a synthetic vector).

The action count is `schema.actions` (the metadata's `actions`), the first ability action `schema.first_ability`. `schema.ability(k)`, `schema.is_ability(action)` and `schema.ability_slot(action)` encode and read the ability actions as `schema.play` and `schema.decode` do the plays.

`vector.metadata` is the export and `vector.write_metadata(path)` writes it, to be kept with every run and checkpoint. `vector.check_metadata(saved)` refuses a saved export this vector would read differently: another schema version, sizes or field names, a vocabulary missing, or a vocabulary whose ids moved (through `Vocabulary.checkSame`, naming the first moved name). So a model trained on one data version is remapped by name rather than read shifted.

- The id rule is not checked either: `vector.id_version` (the metadata's `id_version`, None for a synthetic vector) names it.
- The mask rule is not checked: `vector.mask_version` (the metadata's `mask_version`, None for a synthetic vector) names it, and `LadderConfig(mask_version=1)` gives the rule of every tile. A model saved under one rule passes the check of a vector of the other, to be played or trained on under it.

### The wrappers

Two wrappers over a vector of one battle, both with autoreset off:

- `ClashParallelEnv(deck0, deck1, install=..., tables=..., slots0=None, slots1=None)`: a parallel multi-agent environment whose agents `seat_0` and `seat_1` play sides 0 and 1. Each agent's observation is its seat's 7024 integers in its own frame; an agent left out of the actions waits. `action_mask(agent)` gives an agent's mask.
- `ClashGymEnv(deck, opponent_deck, install=..., tables=..., opponent=..., seat=0, slots=None, opponent_slots=None)`: a single-agent environment for the learner on `seat`, `slots` and `opponent_slots` each deck's slot flags. The other seat is played by `opponent(observation, mask, rng)` (`random_opponent`, a random open action three decisions in ten, `wait_opponent` and the scripted `ScriptedBot` below come with it). `action_masks()` gives the learner's mask as booleans, the method masked-action trainers call.

The observation space is a box of int32 of the seat's 7024 integers, not floats. Its values are vocabulary ids, codes, flags and integer amounts, and an encoder embeds the ids and scales the amounts itself.

Its bounds (`schema.observation_bounds(vocabulary_size, buff_count)`):

- a vocabulary id field (hand, next card, deck, enemy cards seen and played, the queue, waiting commands' cards, a champion slot's champion, an entity's row) lies in -1 to the last id;
- an entity's buff slot in -1 to the last buff id (on a Ladder vector);
- an entity's state in -1 to the last state;
- the result, the seat, the overtime and pending marks, the deck's slot flags, the hand's and the enemy plays' forms, an entity's kind, side and flags in 0 to their largest code;
- every other field in the int32 range.

The action space is the 2307 actions. A `reset(seed=...)` takes a 32-bit seed as the battle's seed. A reset without one draws the battle's seed from the environment's own generator.

Each seat's info (`VectorBattle.seat_info`) is its own:

- `tick`, `episode_step`;
- `winner` (the side, 2 for a draw, -1 while running and for an episode cut short);
- `crowns` and `crowns_enemy`;
- `illegal` (1 when this seat's action of the decision was refused as illegal) and `illegal_total` (this seat's in the episode);
- `last_refusal` (the refusal's name, `NONE` for a wait, a play or an ability command given), `last_run_refusal`, `last_ability_run_refusal`;
- `first_side`, `not_modelled`, `truncated`;
- `fault`, `fault_message`, `fault_signature`, `fault_bundle`;
- `action_mask` (int8).

The other seat's counts are in the other seat's info only.

### The scripted bot and evaluation

`ScriptedBot` is a deterministic opponent of a few readable rules, of the same shape as `random_opponent` (`bot(observation, mask, rng) -> action`). It reads the seat's observation and mask only: its elixir, hand, hand costs and waiting plays, the enemy towers and the characters of both sides on the field.

It attacks with the deck's win conditions, read from the tables:

- a troop whose unit attacks buildings only (its row sets `TargetOnlyBuildings`);
- a siege building, a building whose range reaches an enemy princess tower from the bridge (at least the distance from the centre of a bridge tile to the centre of the enemy princess towers' row on that lane, `tower_row`).

A win condition troop of `tank_hitpoints` (2500) hit points or more at the card level is a tank. A deck without a win condition attacks as the bot did before it knew them, with its cheapest troop.

Each decision, in order (the first four only with no play of its own waiting):

1. **Finish a tower**: a spell in the hand whose tower damage is at least an enemy tower's hit points is cast on that tower. While it can finish a tower, such a spell is kept for it and never cast on characters.
2. **Spell a group**: the best cast of an open spell on the enemy characters it covers, a circle around a tile's centre (or, for a rolling spell, the strip from that tile forwards over its length), each tile holding a character the spell hits a candidate.
   - Its worth is what it takes from each character it hits as a share of that character's maximum hit points or of the spell's damage, whichever is larger (1 for the whole kill of a character at least as sturdy as the spell's damage), summed over its targets (the ones it hits at most, for a spell that hits a few). So a small spell is worth casting on a few weak characters where a big one is not.
   - A spell with damage is cast when it hits `spell_targets` (2) or more characters and is worth at least `spell_worth` (1.5).
   - A spell whose damage the bot does not read (one that deals it through a buff or an action) is cast when it covers `area_targets` (3) or more.
   - A lone character, or a group the spell only scratches, draws no spell.
3. **Defend**: an enemy character past the river on the seat's half: the cheapest open troop or building card that is not a win condition in the lane of the deepest such character.
   - A troop goes on the row just behind the princess towers.
   - A building goes in the middle of the seat's half on that lane's side (`building_cols`, `building_row`), where it pulls the enemy troops off their lane.
   - Win conditions are kept for the attack; with only those open, the bot does not defend.
4. **Support**: a win condition of its own on the field (an entity row of its own side whose row is a win condition's unit) with no troop of its own within `support_radius` (4 tiles) of it, and the elixir at 5 or more (`support_elixir`).
   - The dearest open troop that is not a win condition goes `support_gap` (2) rows behind the foremost such unit, or at the bridge once it crossed the river.
5. **Attack**: the elixir less what its waiting plays hold back at 9 or more (`attack_elixir`), in the lane whose enemy princess tower has fewer hit points: the cheapest open win condition.
   - A tank goes on `back_row` (the back line of its lane, behind its princess tower, so the elixir for its support comes in while it walks).
   - Any other goes at the bridge, on the last row of its half.
   - With no win condition open, the cheapest open troop goes at the bridge.
   - `rng` breaks a tie of the two lanes, and is drawn from for nothing else.
6. Otherwise **wait**.

A card is open when the mask opens a tile of its hand slot (the mask carries the elixir, the start cooldown and a play of the card already waiting). The bot plays a troop or building on its tile when the mask opens it, else on the nearest tile of that slot the mask opens, and a spell only on the tile it aimed at. It never takes an action the mask closed. The lanes and rows are tiles of the seat's frame of the standard arena, constructor arguments with defaults, as are the spell thresholds.

A card's cost is `hand_cost` of the observation. Its type comes from the tables: `VectorBattle.card_types()` gives each deck card's vocabulary id the type of its card table, `troop` (the character cards), `building` (the building cards) or `spell` (the other cards).

What a spell does comes from the tables too, read in Python through the battle core's own row readers and level scaling (`BattleRecords`, `ProjectileAmounts`, `LevelScaling`; the env module exports none of it). `VectorBattle.spell_facts(level=None)` gives each spell card cast on the enemy (its row sets `OnlyEnemies`) its `SpellFacts` at the vector's card level:

- the radius it hits (the card's, else its projectile's or area effect's);
- its damage per target (its projectile's times its waves; a projectile that rolls on as another, the rolling one's, with its width and range; an area effect's own times its hits, or its projectile's with its hits as the most targets);
- a crown tower's share;
- whether it hits air and ground targets.

A spell the battle core does not read, or whose projectile places characters (a barrel), is left out, and the bot never casts a spell it has no facts for.

What a troop or building card places comes from the tables the same way. `VectorBattle.unit_facts(level=None)` gives each troop and building card's vocabulary id its `UnitFacts`, read through the battle core's card reader and level scaling (`BattleRecords`, `LevelScaling`):

- the vocabulary id of the unit it places first (its summoned character, else the first of its list);
- the unit's range;
- whether it attacks buildings only (`TargetOnlyBuildings`);
- whether it is a building (`IsBuilding`);
- one unit's hit points at the vector's card level.

A card the battle core does not read is left out, and a card with no facts is no win condition.

The bot is bound to a vector with `bind(vector)` before its first decision, which reads the layout, the card types, the spell facts and the unit facts (and sets `attack_cards`, `siege_cards` and `tank_cards`). `ClashGymEnv` binds an opponent with a `bind` method as it makes its vector, and `evaluate` binds both policies. A synthetic vector has no tables, so a bot on one is given its `card_types`, and its `spell_facts` and `unit_facts` when it should cast spells or know win conditions.

```python
env = ClashGymEnv(deck, deck, install=install, tables=tables, opponent=ScriptedBot())
result = evaluate(policy, ScriptedBot(), deck=deck, episodes=200, install=install, tables=tables)
result.wins, result.losses, result.draws, result.truncated, result.win_rate
```

`evaluate(policy, opponent, *, deck, episodes, install, opponent_deck=None, tables=None, engine="ladder", config=None, envs=8, threads=1, seed=0, **vector_kwargs)` plays `episodes` episodes of `policy` against `opponent` (each a policy of the opponent's shape; a trained model is wrapped in such a function) on a vector of `envs` battles. It counts them from the policy's seat as an `EvaluationResult`:

- `episodes`, `wins`, `losses`, `draws`;
- `truncated` (cut short by the tick limit or a fault);
- `faults` by kind;
- `illegal` (the policy's actions refused as illegal);
- `win_rate` (wins over all episodes).

The policy plays seat 0 in the even environments and seat 1 in the odd ones, so an even `envs` gives each side half the episodes. Each environment plays a fixed share of the episodes, so which ones count does not depend on which end first. The battle seeds and each seat's generator are drawn from `seed`, so a run is reproduced by it.

Measured on 2026-10-17 on one Mac (Apple M4 Pro), data version 16.402.21, with other work running (1-minute load average 5 to 7). `evaluate` ran over 300 episodes each, 16 battles on 4 threads, seed 2026, a decision being 5 ticks. Both seats held the deck `Knight`, `Archer`, `Musketeer`, `Giant`, `MiniPekka`, `Fireball`, `Arrows`, `Zap` at level 11 (its `Giant` is its win condition and a tank). "Previous" is the bot before it knew win conditions (#685: spells, defence, the cheapest troop's attack and wait).

| policy | opponent | wins | losses | draws | truncated | seconds |
|---|---|---:|---:|---:|---:|---:|
| `ScriptedBot` | `wait_opponent` | 300 | 0 | 0 | 0 | 3.4 |
| `ScriptedBot` | `random_opponent` | 300 | 0 | 0 | 0 | 4.1 |
| `random_opponent` | `ScriptedBot` | 0 | 300 | 0 | 0 | 3.7 |
| `ScriptedBot` | `ScriptedBot` | 131 | 150 | 19 | 0 | 13.0 |
| `ScriptedBot` | previous `ScriptedBot` | 187 | 90 | 23 | 0 | 13.8 |
| previous `ScriptedBot` | previous `ScriptedBot` | 145 | 135 | 20 | 0 | 13.7 |

No episode faulted and the bot took no illegal action. Over 512 battles each way on the same deck (battle seeds from 3,000,000), the bot beats the previous one 312-157-43 and loses to it 164-288-60 from the other seat.

Its decision costs about 15 microseconds a seat on this machine (the previous bot's 13, measured side by side at load 7), in a loop of 64 battles of the bot against itself, about as long as the battles' own steps.

The bot is a floor for a trained policy to beat, not a strong player:

- it defends with whatever troop or building is cheapest that is not a win condition;
- it attacks in one lane at a time with one win condition and one support troop;
- it places a tank on the back line and anything else at the bridge whatever the enemy holds there;
- it casts a spell by a rule of thumb on what it would take, with no lead on a moving target.

### Labelled replays

```python
from crforge_env import label_replays

run = label_replays(paths, install, tables, decision_ticks=5, threads=4)            # in memory
run = label_replays(paths, install, tables, decision_ticks=1, check=True, out_dir="rows/")
run.metadata                       # the schema, the configuration with the interval, the vocabulary
run.replays[0].observations        # int32 (rows, 7024); .masks uint8 (rows, 2307); .actions int32
run.replays[0].ticks, .seats, .flags, .counts, .drive
run.summaries, run.unreadable, run.totals()
```

`label_replays(paths, install, tables, *, decision_ticks=5, threads=1, shape="replay", check=False, out_dir=None, jvm_args=(), mask_version=None)` labels every replay of the files (replay documents and crawl outputs, as above) on the tables of their data version ([Labelling replays](#labelling-replays)). Each replay is labelled whole on one thread, `threads` at once.

- `shape` is `replay` for replays the game client records and `generated` for generated test cases.
- `check` also drives a battle from each replay's labels and reports it in `drive` (`reproduced`, `state_reproduced`, `first_divergent_tick`, `divergence`, `stop` and the counts).
- With `out_dir` each replay's arrays go to `<out_dir>/<source>.npz` (`ReplayLabels.save`, the counts and drive as JSON strings in it), with `metadata.json` and `counts.jsonl` (one replay a line) beside them, and none is kept in memory.
- `mask_version` picks the rows' mask rule (None for the default, rule 2; the metadata names it).

`FLAGS` names the row flags and `flag_names(flags)` reads them. The rows are local data of the machine that makes them: never commit them.

### Faults are truncations

A fault is never a result. When a battle's episode ends by a fault (a refusal of the battle core, a bug of the core, a bug of the environment), the step that reports it is `terminated=False, truncated=True` with reward 0 for both seats. The info names the fault's kind under `fault` (`refusal`, `bug` or `env_bug`, `none` otherwise) with `fault_message`, `fault_signature` and `fault_bundle` (the folder of the fault's bundle, empty when none was written).

A trainer bootstraps such a step as it bootstraps a tick limit, and never learns from it as a loss or a win. A tick limit (`LadderConfig.max_ticks`) truncates the same way, with `fault` `none`. A reset whose new episode faults before its first decision raises `BattleFaultError` with the fault.

### Tests and checks

```bash
cd python
uv run pytest --install=../env/build/install/env --tables=<tables folder>   # all tests
uv run pytest --install=../env/build/install/env                            # the Ladder tests skipped, and the run says so
uv run ruff check && uv run ruff format --check
```

The tests without tables run the vector, the schema and both wrappers on synthetic environments:

- the arrays view the buffers;
- a forced fault of each kind ends the episode as truncated with no reward in both wrappers;
- the same seeds and actions give the same observations on one thread and four;
- a saved export of another schema is refused;
- both wrappers pass their API's own checker.

The Ladder tests add:

- a battle's start (decks, towers, a mask of the wait alone before the start cooldown);
- the mask honoured (a closed action counted for its seat alone, random open actions never illegal);
- determinism on one thread and four;
- a whole battle to its result;
- a tick limit as truncation;
- both API checkers on a battle;
- a saved vocabulary whose ids moved refused;
- a fault's bundle written, its folder named in the fault and the seats' info, and none written by default;
- the two mask rules (rule 2 closing tiles of open slots, rule 1 opening every tile, their schema and configuration the same, an export of one passing the other's check).

Schema version 3 adds:

- every field of a random battle within the bounds (buff ids within the buff list, states within the state names);
- a buff and an enemy play seen;
- the enemy elixir count within the bar;
- a saved export whose buff list moved refused;
- the state names and scales against the Java `Schema`;
- the entity columns read by name.

Schema version 4 adds the play kinds and flags, the unknown value (-1) and the new fields against the Java `Schema`, and the bounds of the enemy plays' kinds, flags and forms not yet shown. The scripted bot leaves an enemy hidden or underground out of its spells' targets and takes an enemy whose health bar the screen does not draw as unhurt.

The label tests write an episode of the environment as a replay (`VectorBattle.replay_json`) and label it back. At 1-tick decisions its own observations, masks and hand slots come back and the labels drive the same battle. A crawl record of it, plain or compressed, is labelled as the replay document is, on one thread or two, in memory or written to a folder.

The scripted bot's tests hold:

- its rules on observations written by hand with hand-written spell and unit facts:
  - a group drawing a spell on its centre, a lone or barely hurt one none;
  - a spell closed by the mask or aimed at a closed tile never cast;
  - a tower finished and its spell kept for it;
  - a spell without damage, a ground spell and a rolling one;
  - a building-targeting troop attacking at the bridge before a cheaper troop, a deck without one keeping the cheapest troop;
  - a siege building at the bridge and a shorter one not;
  - a tank on its back line;
  - win conditions kept out of defence;
  - the support behind a win condition and none beside an escort;
- its determinism and the mask on synthetic environments with hand-written card types, spell facts and unit facts;
- the evaluation's counting on synthetic episodes;
- on Ladder battles:
  - the card types, spell facts and unit facts against the tables;
  - a win against `wait_opponent`;
  - a whole battle against `random_opponent` with plays and no illegal action;
  - spells cast in bot-against-bot battles;
  - a deck's win condition played in each bot-against-bot battle (a tank on its back line, any other at the bridge);
  - a siege building placed at the bridge, or on the open tile nearest to it when it does not land there unmoved.

CI runs them in the `python` job of `build-and-test.yml`, on the tables of the lock's version built from the data repository's copy of the asset files.

### The Python-side benchmark

`crforge-env-bench --install <env/build/install/env> --tables <folder>` steps a vector of 1, 16, 64 and 256 environments of each engine (`--engine` picks one) for 200 decisions, 3 trials after a warm-up, with a random open play 3 decisions in 10 a seat drawn on arrays in Python. It times the whole loop: the action draw, the call into the JVM and the reads of rewards and dones.

Every battle is the same match of two decks of card row names (`Knight`, `Archer`, `Musketeer`, `Giant`, `MiniPekka`, `Fireball`, `Arrows`, and `Zap` or `Goblins`). The best trial is reported, with the spread over the trials.

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

Schema version 3 (2026-10-18) writes 6,792 integers a seat instead of 2,528, and reads each entity's buffs, target and timers off the core. It was measured with `crforge-env-bench --engine ladder --counts 64 --threads 1 --steps 400 --repeats 5`, version 2 and version 3 alternated three times each on data version 16.402.21. One thread steps 17,900 to 18,300 decisions a second under version 2 and 16,900 to 17,900 under version 3 (best of 5 trials each; 1-minute load average 6 to 7, other work running): about 4 percent slower, within the spread of a busy machine.

A seat's observation is 27 kilobytes, so a 64-battle vector's observations are 3.5 megabytes a step (1.3 under version 2), and a replay labelled at 1-tick decisions holds about 27 kilobytes a row in memory.

Id rule 2 (2026-10-18, entity ids per seat in order of first sight) costs one array lookup per id written. Measured the same way, rule 1 and rule 2 alternated three times each, one thread steps 12,800 to 13,400 decisions a second under rule 1 and 12,100 to 13,700 under rule 2 (1-minute load average 11 to 16, other work running, spread over the repeats 16 to 30 percent): no difference the spread can show.

Schema version 4 (2026-10-18) writes 7,024 integers a seat and follows each play's card, point and diamond tick by tick. Measured the same way, version 3 (with the enemy elixir count of the end of the match) and version 4 alternated three times each on 16.402.21, one thread steps 11,200 to 11,800 decisions a second under version 3 and 11,600 to 12,200 under version 4 (1-minute load average 16 to 19, other work running, spread over the repeats 27 to 59 percent): no cost the spread can show.

Read it as: one thread steps about 20,000 to 24,000 Ladder decisions a second, about 100,000 to 120,000 battle ticks, about the battle core's own rate on the recorded replays ([performance.md](performance.md)). The synthetic engine, which writes observations and masks of the same size with no battle behind them, costs about 3 us a decision, so the encoding, the masks and the tries are a small part of a Ladder decision and the battle is the rest. Four threads give about 3.1 times one. The 256-battle single-thread row's interval is wide (a slow iteration); the others are within about 20 percent.
