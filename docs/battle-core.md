# The battle core

The battle core is a second simulation engine, growing beside the original `GameEngine`. It exists because the two are built on different footings. The original engine was written from observation and a reference port, and the fidelity ledger lists nearly all of it as a guess. The battle core only takes in behaviour that is established, and every class in it says which parts are settled and which are not.

The original engine stays as it is and keeps serving the visualizer and the Python bridge. The battle core takes over a milestone at a time; when it covers a whole match, the bridge and the visualizer switch to it and the original engine is retired. Run `./gradlew :core:fidelityReport` to see how far that has come.

## Why a second engine and not a refit

Three properties of the established behaviour cut across the whole of the original engine, so they cannot be reached by fixing one system at a time.

- **Time is integer milliseconds.** Every duration is whole milliseconds stepped by exactly 50, so a duration of `ms` lasts `ceil(ms / 50)` steps. The original engine keeps every timer as float seconds and steps it by a float that is not quite 0.05, which makes most attack periods between 1.2 s and 4.0 s one tick long, along with several deploy times, buff durations and spawner pauses.
- **A tick is passes over the whole entity list, not a chain of systems.** Every entity's targeting runs before any entity's movement, which runs before any entity's state visit, over a snapshot of the entity list taken at the head of the tick and ordered by id. The original engine runs one system per concern, each with its own loop and its own order.
- **Commands run before the entity tick.** A card play runs twenty ticks after a player makes it, at the head of its step, and the entity tick of that same step admits and visits what it creates. The original engine queues a spawn and flushes it at the head of the following tick.

## Package layout

```
org.crforge.core.battle/
  Battle              the step: clock, entity tick, commands, tick counter
  BattleMode          when the match is over; what the mode does before the entity tick
  BattleCommand       a tick-stamped action from outside the entity tick
  EntityHolder        the entity tick: every hook and pass of every entity, in one fixed order
  HolderPasses        the once-per-tick work around the entity visits
  BattleEntity        a container of four component slots with a pre-hook and a post-hook
  BattleComponent     one slot: a refresh and a visit
  EntityActions       the five action passes of one entity
  BattleRandom        the battle's random source: one shared 32-bit xorshift state
  expression/
    ExpressionCompiler the data's expression columns into opcode words, with the fold
    ExpressionEvaluator the stack machine that runs them over 32-bit integers
    Expression        one compiled expression: its words and its stack depth
    ExpressionEnvironment what an expression's symbols name and what each call answers
    BattleFunctions   the 47 names of the battle's functions, their ids and argument counts
  unit/
    BattleWorld       the routing grid, the building overlay and the spatial index, rebuilt per tick;
                      owns the holder, hands launched projectiles to it, and is where damage lands
    WorldEntity       an entity that stands on the arena, with its targeting component's state
    CharacterEntity   targeting in slot 0, movement in slot 1, the state visit as the post-hook
    TowerEntity       a crown tower: targeting in slot 0, the state visit and the combat gate as
                      the post-hook
    UnitData          the published columns of a unit, unconverted, its projectile row among them
    WorldObserver     what watches the arena from outside the tick: hits, areas, launches,
                      impacts, removals, a king tower's activation
    TrajectoryRecorder writes a character's run in the layout of the reference trajectories
    Standard1v1Battle the standard arena with its six towers
    ActivationEvent   one step of a king tower's activation, as observers are told it
  action/
    ActionHolder      one entity's scheduled actions behind the five passes: the pending entries,
                      the running instances and the tags they set
    BattleAction      an action: what starting it does, and whether it lasts
    ActionInstance    one run of an action that lasts, stepped by the run pass
    WaitToActivate    lasts until its condition holds, then schedules its activation
    WithDuration      lasts a fixed time
    PresentationAction shows something and changes nothing the simulation reads
    GameTags          the tag bits an action sets
  deploy/
    CardPlacement     one card play worked out: the map check, the search, the column, then each
                      unit's offset, position, lane and start
    MapCheck          the raw requested point: on the map, and on a cell that may take the card
    DeployMask        the tiles a card may be placed on: the towers' boxes and the buildings
    PlacementSearch   the clamp, the snap and the nearest legal tile in square rings
    Formation         where the index-th unit of a card stands around the placed point
    InitialDelay      whether a unit starts deploying or waits its turn
    DeployCard        the columns of a troop card its placement reads
  projectile/
    ProjectileData    the published columns of a projectile row, unconverted
    ProjectileEntity  a projectile in flight: an entity of its own kind in the same holder
    ProjectileLauncher the hit application's projectile path: count, spread, start, aim, hand-over
    ProjectileFlight  one step of the flight, the arrival and the impact
    ProjectileAmounts the damage and the crown-tower damage at the projectile's level
```

Every entity is of a kind - area effect, projectile, character - and its id is its kind's band of a
million plus a per-kind counter taken the moment it is handed to the holder, so a projectile
launched in the middle of a battle is still ahead of every character in the holder's id-sorted
list. Every troop and every building is a character.

The movement, targeting and state rules themselves live in `org.crforge.core.pathfinding` and are shared with the original engine's grid mode; see [Troop Pathfinding](pathfinding.md). The battle core runs them directly. The original engine reaches them through `GridPathfindingSystem`, which has to split one tick into two calls around its own combat step and copy the result back into its own components.

Level scaling, the hit-points object and the damage chain live beside those rules in `org.crforge.core.pathfinding.combat`: `RarityTable` (the published rows), `PackedLevel` (a level as an entity carries it), `ScalingGlobals` and `LevelScaling` (a stat at a level, under the card rule and the tower rule), `HitPoints` (what damage lowers, with the alive and removal tests), `DamageApplication` (one damage event reaching an object: the guards, the amount the two sides' buffs make of it, the dedupe list, the shield and the subtraction) and `AreaDamage` (damage landing on a circle: which entities it collects, in id order through the shared validator, and what each takes). Every arena entity is created at a level and scales its hit points and damage once, at creation.

The attacker's half of a hit stays with the targeting rules: `HitApplication` is the hit the targeting visit fires - the component writes, the long-distance cancel and the damage of one hit at the owner's level - and `DirectHit` is how a hit without a projectile reaches its target, with the plain damage and the crown-tower damage and the direction it came from. A unit with a projectile row launches instead, through the battle's `ProjectileLauncher`: the projectile is created in the attack tick, has its id at once, enters the holder's live list at that tick's closing cleanup and flies from the next tick on.

**The dependency rule.** `org.crforge.core.battle` and `org.crforge.core.pathfinding` may import each other, `org.crforge.core.fidelity` and `org.crforge.core.util`, and nothing else from this code base. The original engine may depend on them; they may not depend on it. `BattlePackageDependencyTest` enforces this, with `GridPathfindingSystem` as the one permitted exception because it is the original engine's adapter. Without the rule a single import would let guessed behaviour into a class that claims to be settled.

## One step

`Battle.step()` is 50 ms of game time:

1. If the mode says the match is over, nothing happens at all: no clock, no entity tick, no tick counter.
2. The clock advances by 50 ms.
3. Due commands run, in the order they were queued: every command whose tick has come, a late one included.
4. The mode update runs. It either lets the entity tick run, handing it the current tick, or declines, in which case the holder is only cleaned up.
5. The tick counter advances.

So what a command creates is handed to the holder before the entity tick of its own step, and the tick's opening cleanup admits it: a unit placed on tick `n` is first visited on tick `n`, as the reference runs have it, with no offset anywhere. The towers of a standard battle are placed straight into the live list when the battle is set up, so they stand from the first tick.

`EntityHolder.tick()` is the entity tick:

1. Cleanup: drop removable entities, telling every remaining entity of each removal, then fold the entities handed over since the last cleanup into the live list, which stays sorted by id.
2. Take a snapshot of the live list. Every entity loop below runs over it.
3. The holder pre-pass: the spatial index and the building overlay are rebuilt from the snapshot.
4. Every entity's pre-hook.
5. Every entity's pending actions of phase 1.
6. One whole-list pass per component slot, lowest first: the component's refresh, then its visit when the component is switched on. Targeting is slot 0, movement is slot 1.
7. Every entity's running actions.
8. Every entity's pending actions of phase 2.
9. Every entity's post-hook: for a projectile its flight step, for a character its state visit, in that order because of the id bands.
10. The holder's after-post-hooks step, then every entity's pending actions of phase 3.
11. The holder post-pass: the overlay is rotated and the index cleared.
12. Cleanup again.
13. The end-of-tick action countdown, over the live list rather than the snapshot.

Three consequences that everything else leans on: entities are visited in ascending id, which is creation order within a kind with every projectile ahead of every character; an entity added during a tick is not visited until the next one; and an entity that becomes removable during a tick is visited for the rest of it and is gone before the next snapshot.

## What is covered today

A ground character deployed on the standard arena walks its lane, picks its target, routes around buildings, is pushed and steered by other characters and by the towers, and locks onto a tower, tick for tick as the reference trajectories record it. For its first ten walking ticks it only considers the towers of its own lane, so a unit deployed near the middle walks at its lane's princess tower before it turns to the king. Its state changes carry what the standard game attaches to them: taking a target prepares the route at once, stopping empties it, and resuming prepares a new one before the next movement visit.

Once locked on, it attacks. An attack starting from zero is credited the whole of a run-down load, so the first hit lands nine ticks after the lock and the rest follow at the hit speed. Each hit lands on the target and takes its hit points down by the character's damage at its level. A tower whose hit points reach zero is dead: it leaves the holder in the closing cleanup of the tick it dies, and every other entity is told at once. A character that was attacking it drops the reference and, with an attack running, stands for the attack finish time before it takes its next target and resumes its walk. The whole kill run, a Knight destroying a princess tower and then the king tower, reproduces tick for tick, and the engine writes such a run out in the layout of the reference trajectories.

A character with a projectile row fires instead of hitting. Each of its hits creates its projectiles in the attack tick, starts them the unit's launch radius along the line to the target and its launch height up, and aims them at where the target stood at the start of the visit. A projectile is an entity of the holder like a character, of its own kind, so it flies in the post-hook pass of every tick from the one after its launch, ahead of every character: a homing projectile re-pins its aim onto its target each step, moves its speed along the line and takes the height the arc gives there, and arrives on the step that reaches its aim. On arrival it is released, which makes it removable, and its impact deals its row's damage at its level - or the crown-tower share of it to a crown tower - to a target that still has hit points. A projectile whose target left the battle flies on to where the target stood and lands on nothing. A projectile row with a radius does not hit its target alone: its impact damages everything the area damage collects in the circle around its aim - an entity the validator accepts, on a layer the row reaches, inside the circle (a building by its square), and at most one tower-slot entity per area - and spares the launcher's own side only when the row says so. The Musketeer run, a Musketeer shooting a passive princess tower down in fifteen shots, reproduces tick for tick, launches, positions and impacts included.

A character without a projectile whose row has an area radius does not hit its target alone either: every landed hit damages the circle around the character, for a row that centres its area on the unit, or around where its reference stood at the start of the visit, through the same area damage, with the character as the owner. The character's own targeting columns decide what the validator accepts, so its own side and the character itself are spared; its target takes its share only as one of the victims, and nothing is pushed, as no row of the data pushes with a hit. Such a row may also wait its own time instead of the global one before it takes a new target after losing one - the Valkyrie's is 100 ms. Three runs hold it: a Valkyrie hitting the princess tower alone; the Valkyrie with an enemy Knight at the tower, both damaged by every hit; and the Valkyrie fighting an enemy Knight beside her own princess tower, which her circle spares. The last two are the first runs with two units, one walking at the other: a unit's movement reads its reference where it stands at that moment, so a unit visited later in the movement pass steers at a unit visited earlier where that one has just moved.

The princess towers fight back. Every tower carries the same targeting component as a troop, in the same slot, and no movement component, so it stands for its whole life except to attack. With nothing in range it holds the opposing side's tower its default selection gives as its reference, out of range, and selects again every tick; a unit that walks into its range is locked on at once, and the tower fires its projectile row on the attack ticks through the same projectile path a Musketeer uses. The building branches of the targeting visit apply: a target that steps beyond the range and both radii is dropped at once, without the extension a unit keeps a target by, and a hit is never cancelled for distance. When the target dies the removal clears the reference and starts the target-lost countdown, and after it the tower returns to standing and takes its default reference again. A tower's post-hook is the state visit, which steps its elapsed time from its first tick, followed by the combat gate, which keeps the targeting component off while the tower is inactive. A king tower sleeps until its side loses a princess tower or it loses hit points itself. Its placement queues a wait that sets the inactive tag, which the first tick's first pending pass starts after that tick's tags were folded in, so the king is visited on its first two ticks and then switched off. The run pass that first sees the condition ends the wait; the wait queues a 3300 ms activating run, which the same tick's second pending pass starts, and whose tag keeps the component off until the run pass after it finishes removes it. The king's first visit therefore falls seventy ticks after the tick that saw the condition. This is the first work the action passes do: a small action holder, shaped as the standard game's runtime is, with the three actions the king needs. The wait's condition is the data's own expression, `king_tower_damaged() || coop_king_tower_damaged() || tower_destroyed() || coop_tower_destroyed()`, compiled once and evaluated against the battle from the king: the first expression the battle runs. Four runs reproduce tick for tick: a Knight walking up the left lane, which the princess tower in front of it locks on at 130, shoots every sixteen ticks from 145 and kills with the fourteenth arrow at 357 after the Knight's five hits, a Musketeer that fires four shots at the same tower before the tower's sixth arrow kills it, a Wizard whose three fireballs land on the tower through the area damage before the tower kills it, and the Knight again with the towers at the first level: it destroys the princess tower at 388, the king sees the condition at 389, locks on at 459 and fires from 468, and the Knight dies at 635 after seven hits on the king.

A troop card is played through a command. A player's play is stamped with the battle's tick counter and runs twenty ticks later, at the head of that step: its placement is worked out against every character, live or still queued, and its units are created at their formation places in creation order and handed to the holder, whose opening cleanup admits them, so they are visited in that same step. The first unit deploys at once; every further one waits its turn in the waiting state, visited by neither component, for the card's stagger times its index, and then deploys for its own deploy time. A deploying unit is visited by the movement pass with no speed, so only a push moves it. Every entity takes part in contact, the towers included: a tower is a static neighbour of the push pass and an obstacle of the avoidance handler, and with a mass of 0 its share of a push is a single unit, so a unit deployed against its king is pushed off it one unit a tick. Five runs reproduce tick for tick: a Barbarians card on the left lane and one in the corner, a Skeleton Army in the corner, a Knight played by the top side and a play the map check refuses, every unit's position, state, reference and hit points, every launch, impact, hit and death, and every projectile position.

- `BattleGoldenTrajectoryTest` replays the six Knight reference trajectories through `Battle` and asserts position, state, route length and target after every step, the inner deployment among them, which holds the lane rule's first ten ticks. Reference tick `n` is battle tick `n`, with nothing shifted: the placement runs at the head of the first step.
- `BattleTrajectorySweepTest` replays 48 more reference trajectories the same way: sixteen ground units whose speed, attack range, sight range, collision radius and deploy time all differ, deployed at random points on both sides, two thirds of them switching from the king tower to a princess tower on the way. A change to a cell cost, the default target rule, the endpoint scan or lane assignment moves a route somewhere in here even when it leaves the five Knight walks alone.
- `BattleMultiUnitParityTest` runs multi-unit scenes through both engines and requires identical positions and states on every tick, which a single-unit trajectory cannot do, because with one unit a per-entity order and a per-pass order cannot be told apart.
- `BattleKillRunTest` drives the kill run, a Knight destroying the princess tower and then the king tower, and holds the battle to the whole of it: every one of the 1258 records, with the Knight's position, state, target, route length and movement budget and the target's remaining hit points; the tick and remaining hit points of every one of the forty hits and both deaths; the removal of a dead tower in the tick it dies, the five ticks its attacker stands without a target, and the resume; the hit points every tower and the Knight start the run with; and the damage of one Knight hit, all at the reference's level.
- `BattleMusketeerRunTest` drives the Musketeer run, a Musketeer shooting the left princess tower down while the towers stand passive, and holds the battle to what the Musketeer's outside shows: the lock at 155, a shot every twenty ticks from 168 with the tower untouched at the moment each leaves, the tower's hit points falling only when a shot arrives eight ticks later, the death on the fifteenth impact and the removal in that tick, every one of the 457 records, and the Musketeer's own hit points at its row's rarity.
- `BattleProjectileFlightTest` holds the projectiles of that run themselves: every launch with its id, row, owner, target, start and aim, every position after every flight step, every impact with its damage and the tower's remaining hit points, the projectile's place ahead of every character in the holder while it flies and its removal in the tick it arrives, and a shot whose target is destroyed mid-flight flying on to where the tower stood and landing on nothing.
- `BattleTowerRunTest` drives the seven runs in which the towers fight, a Knight, a Musketeer and a Wizard on the left lane, the Knight against towers at the first level and the three Valkyrie runs, placing a run's further units on their own ticks, and holds the battle to every record including the unit's own hit points and every further unit's position, state, reference and hit points, to every launch, impact, hit, area and death in the order the battle made them, and to every projectile position.
- `BattleTowerTargetingTest` holds the towers' own targeting to the same runs: the reference every tower holds on every tick, every tower's lock, the target-lost countdown the unit's removal starts in every tower that held it, a king tower visited on its first two ticks and then switched off, the king switched off from the condition until seventy ticks after it, a tower's elapsed time stepped from its first tick, and a passive tower that never selects.
- `AreaDamageTest` holds the area damage's rules one at a time, the ones the Wizard run's single victim does not reach among them: the owner's side spared unless asked, a unit's own radius and a building's square in the circle test, the crown-tower damage, one tower-slot entity per area, the limit, the split rounded up and the air gate.
- `ExpressionCompilerTest` and `ExpressionEvaluatorTest` hold the expression language case by case: every operator's opcode, precedence and associativity, the literals and their 32-bit wrap, the tokenizer, symbols resolved in the environment before the builtins, every builtin, the fold and what it leaves to run, every refused string, the stack depth, 32-bit arithmetic with division by zero, truth, no short circuit, calls and the two early answers. `BattleFunctionsTest` holds the 47 names, `BattleRandomTest` the random source against 42 recorded sequences of draws, and `BattleExpressionEnvironmentTest` the battle's answers to the king's condition and the refusal of a function it does not answer yet.
- `BattlePlacementRunTest` plays the five placement references through `Battle`, every card play a command due on its tick, and holds the battle to every unit's position, state, reference and hit points on every tick, to every launch, impact, hit and death, and to every projectile position. `BattleCardPlayTest` holds the stamp of a player's play and the twenty ticks it waits; `BattleTowerContactTest` holds a Knight deployed against its king tower being pushed off it one unit a tick.
- `CardPlacementTest` works out every card play of the five placement references - a Barbarians play on the left lane and one in the corner, a Skeleton Army in the corner, a Knight of the top side, and three Knight plays of which two are refused - and holds the refusal code, or the placed point, the column, the lane of the point and every unit's formation offset, position, lane and start. `FormationTest` holds the formation to 4992 recorded cases over every branch it takes, and `MapCheckTest` the map check's bounds and codes.
- `ActionHolderTest` holds the action holder's rules one at a time: the swap-with-last order of a pending pass, a zero-delay action starting at once only inside a pending pass, a delay waited out in the end passes, a duration finishing on the step after it reaches its length and its tag counted until the next run pass removes it, and a wait ending on the step its condition holds.
- `TrajectoryRecorderTest` plays both kill runs and the seven runs with the towers fighting with a `TrajectoryRecorder` attached to the battle's world and holds each file it writes to the committed reference byte for byte. The recorder writes a character's run in the layout of the reference trajectories, the header, the towers, the events, one compact record per tick and, for a run with projectiles, every projectile position, so a run the engine plays can be compared with a reference directly, or become a fixture. A run with the towers fighting also carries the unit's own hit points on every record and the towers' own events.

The references made before the towers fought - the lock trajectories, the sweep, the kill run and the Musketeer run - are played with the towers passive, as they were made: `Standard1v1Battle` takes a flag that keeps every tower's targeting component off for the whole battle.
- `LevelScalingTest` holds the two scaling rules to the published tables and percentages: for every level a card can have the card rule is the iterated floor of a tenth per step, and at the published values the tower rule is 1.07 (king hit points) or 1.08 per level up to the tournament cap and 1.10 from there.
- `EntityHolderTest` and `BattleTest` pin the two orders above line by line, the ids per kind and the fold that keeps a late projectile ahead of every character among them.

The reference trajectories are the output of a model of the game's rules, not captures of the game. What that means for a disagreement is set out in `core/src/test/resources/pathfinding/README.md`.

## Roadmap

The work lives on the `battle_core` branch and reaches `main` only when the engine is ready; every change is a pull request into `battle_core`.

Each milestone lands test first, against a reference it can be held to, and is driven through `Battle.step()` rather than by calling its rules directly. A milestone whose behaviour is not established yet does not start from a guess; it waits.

| Milestone | Content | Held to |
| --- | --- | --- |
| M1 (done) | The step, the entity tick, characters walking and locking on | five Knight trajectories, a 48-trajectory sweep over sixteen units and both sides, multi-unit parity with the grid mode |
| M2 (done) | Hits: the attack timer and its load, level scaling, hit application, the direct hit, the damage entry with its guards and the shield, hit points, death and removal, a destroyed tower leaving the holder and the target lists, the run exported in the reference layout | a Knight destroying a princess tower and then the king tower: the tick and remaining hit points of every hit, and every position of the 1258-tick run |
| M3 (done) | Ids per kind and the projectile as an entity of the holder, from launch to impact, with its removal notice; the targeting component on the towers, so the princess towers shoot; the area impact of a projectile with a radius; king tower activation through a small action holder; the area damage of a unit that splashes with a direct hit | done: a Musketeer shooting a passive princess tower down, every launch, projectile position and impact; a princess tower shooting down a walking Knight, a Musketeer and a Wizard, the lock and every launch, impact and record with the unit's own hit points, the Wizard's fireballs through the area damage; the king tower waking when a princess tower falls, its activation's every step; a Valkyrie's areas around herself, with two victims, and sparing her own tower |
| M4 (in progress) | Done: one command pass before the entity tick; the placement of a troop card worked out: the map check, the deploy mask, the search, the column, the formation and each unit's start; the card play as a command that creates the units, the stagger they wait through, and the towers taking part in contact. Next: the four recorded grid anomalies run again under the battle and settled, and a unit relocated off water | done: a Barbarians card on the left lane and in the corner, a Skeleton Army in the corner, a Knight of the top side and a refused play, every unit and projectile on every tick |
| M5 (in progress) | Done: the expression compiler and evaluator with the battle's function names and random source, the king's condition evaluated as the data writes it. Next: the action interpreter behind `EntityActions` - scheduling, the three phases, the composites, then the leaf actions by how often the card data uses them; game tags and object filters; loaders for the data they need | done: every recorded case of the compiler and the evaluator, 42 recorded sequences of draws, the level-1 king run with its condition compiled; next: per-action fixtures, then whole cards whose behaviour is only expressed as actions |
| M6 | Buffs and status effects, area-effect entities, spells | per-card fixtures; waits until the behaviour is established |
| M7 | The rest of the character: air, jumping and hovering movement, dash and charge, attached units, spawner buildings, building lifetime, death spawns; then a sweep of the card library | per-card fixtures |
| M8 | The mode: match clock, elixir, hands, crowns, how a match ends | waits until the behaviour is established |
| M9 | Switch the Python bridge and the visualizer over, benchmark throughput against the original engine, retire it, merge to `main` | the bridge's own test suite |

M5's interpreter, composites and evaluator do not depend on M2 to M4 and can proceed beside them. Air, jumping and hovering units join in M7; until then `CharacterEntity` refuses them rather than guess.

**Ready for `main`** means: the original engine is gone or no longer the default, the bridge's tests pass on `Battle`, throughput is at least the original engine's, no class in the battle package is a guess, and the fidelity report says what is still partial and why.

## Known assumptions carried today

These are supplied answers, recorded on the classes that carry them and listed here so they are not mistaken for settled behaviour.

- A deploying character's targeting component is not visited, and its movement component is visited but asks for no route and gets no speed, so only a push moves it. The visit the standard game gives a new unit's components when it is registered, in which nothing moves, is not modelled.
- A king tower's activation is the one set of actions the battle runs, written by hand in the shape of the standard game's runtime: its wait's condition is the data's expression, of whose functions the battle answers only the four it uses, the two co-op ones answering 0 in a battle of two players; the starting group around the wait is left out because its own body is empty, and the gate's enabling side answers for a standing, living tower. The rest of the king's own state visit is not modelled. A building with no reference resets its attack only when it has hit points at the first level, as every tower has; no reference run reaches that branch, because a tower always has a default tower to fall back on.
- Every tower, king or princess, is a crown tower: the selector notices it from 2000 units farther, the index orders it last and a hit on it deals the crown-tower damage. Only the king fills its side's tower slot, which the validator's tower filters read.
- Every entity carries hit points and damage at its level, packed against its own row's rarity - the rows are published at the first level and the card's rarity only positions the level - and the towers scale as Common. A hit of a unit without a projectile lands on its target and lowers it; a unit with a projectile row launches it, and the projectile's impact lowers its one target by the row's damage at the projectile's level. A projectile row with a radius damages the circle around its aim instead; its target limit is not carried and taken as none, and a row that flies to a point or only heals does nothing on arrival. A unit with an area radius and no projectile damages the circle instead of its target, with no push, as no row of the data pushes with a hit; a circle centred on the target rather than the unit has no reference run, as the one row that does it carries mechanics of later milestones. The buffs that change an amount are not modelled. An entity whose hit points reach zero is dead and leaves the holder in the closing cleanup of the tick it dies, as the reference run has it; what a death does beyond that - the rewards, the death spawns, what a destroyed tower does to the match - is not modelled.
- A projectile flies straight to its aim with the height the arc gives; the deflection pass finds nothing, the projectile's own collision radius is zero, and the draw that spreads the further projectiles of a multi-projectile attack answers zero. The hits along a flying body's path, the pushback on impact, the on-impact spawns, the chained hop, the pingpong sweep, the ring scatter, the drag-back hook and the delays before a flight are not modelled; no row the reference runs use has them.
- Neither side of a hit carries a buff that changes the damage, nothing is untouchable or immune, and the battle never holds damage. These are the answers the reference run was produced with; what would change them is not established.
- A removable entity - one that has asked to be removed or has no hit points left, or a projectile that has arrived - leaves the holder in the closing cleanup of the tick it becomes removable, and every remaining entity is told inside that cleanup, those handed over that tick before the live list: a character drops a reference to it, starting the target-lost countdown when an attack was running, its default targets lose it, and a projectile aimed at it keeps its aim where it stood and forgets it. Whether the removed entity is told of its own removal, and what the removal does to a held projectile or a followed entity, is not modelled.
- An expression names only the battle's functions and the builtins: a variable, a game tag or a data row named in one is refused until the data they come from is loaded. Of the 47 functions the battle answers the four the king's condition uses and refuses the rest. The random source draws as recorded, but the battle's seed and the order of its draws within a tick are not established, so nothing in the battle draws yet.
- The placement of a card play covers troop cards in a battle of two players: the Mirror card, spells, area-effect and building cards, a touchdown mode, a capture tower and the dummy rows are not modelled, and neither are the elixir and the other gates that come before the map check.
- The command pass runs every command whose tick has come, a late one at once, as the networked and local regimes do; the replay regime, which drops a late command, is not modelled.
- The mode never ends the match and always lets the entity tick run.
- The assumptions of the movement and targeting rules themselves are listed in [Troop Pathfinding](pathfinding.md#assumptions) and apply unchanged.
