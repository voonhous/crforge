# The battle core

The battle core is a second simulation engine, growing beside the original `GameEngine`. It exists because the two are built on different footings. The original engine was written from observation and a reference port, and the fidelity ledger lists nearly all of it as a guess. The battle core only takes in behaviour that is established, and every class in it says which parts are settled and which are not.

The original engine stays as it is and keeps serving the visualizer and the Python bridge. The battle core takes over a milestone at a time; when it covers a whole match, the bridge and the visualizer switch to it and the original engine is retired. Run `./gradlew :core:fidelityReport` to see how far that has come.

## Why a second engine and not a refit

Three properties of the established behaviour cut across the whole of the original engine, so they cannot be reached by fixing one system at a time.

- **Time is integer milliseconds.** Every duration is whole milliseconds stepped by exactly 50, so a duration of `ms` lasts `ceil(ms / 50)` steps. The original engine keeps every timer as float seconds and steps it by a float that is not quite 0.05, which makes most attack periods between 1.2 s and 4.0 s one tick long, along with several deploy times, buff durations and spawner pauses.
- **A tick is passes over the whole entity list, not a chain of systems.** Every entity's targeting runs before any entity's movement, which runs before any entity's state visit, over a snapshot of the entity list taken at the head of the tick and ordered by id. The original engine runs one system per concern, each with its own loop and its own order.
- **Commands run after the entity tick.** A deployment takes effect at the tail of its step, so what it creates first takes part in the following tick. The original engine flushes spawns at the head of a tick.

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
  EntityActions       the five action passes of one entity (no interpreter behind them yet)
  unit/
    BattleWorld       the routing grid, the building overlay and the spatial index, rebuilt per tick;
                      owns the holder, hands launched projectiles to it, and is where damage lands
    WorldEntity       an entity that stands on the arena, with its targeting component's state
    CharacterEntity   targeting in slot 0, movement in slot 1, the state visit as the post-hook
    TowerEntity       a crown tower: targeting in slot 0, the state visit and the combat gate as
                      the post-hook
    UnitData          the published columns of a unit, unconverted, its projectile row among them
    WorldObserver     what watches the arena from outside the tick: hits, launches, impacts,
                      removals
    TrajectoryRecorder writes a character's run in the layout of the reference trajectories
    Standard1v1Battle the standard arena with its six towers
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
3. The mode update runs. It either lets the entity tick run, handing it the current tick, or declines, in which case the holder is only cleaned up.
4. Due commands run, in the order they were queued.
5. The tick counter advances.
6. Due commands run once more, against the advanced counter.

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

A ground character deployed on the standard arena walks its lane, picks its target, routes around buildings, is pushed and steered by other characters and locks onto a tower, tick for tick as the reference trajectories record it. For its first ten walking ticks it only considers the towers of its own lane, so a unit deployed near the middle walks at its lane's princess tower before it turns to the king. Its state changes carry what the standard game attaches to them: taking a target prepares the route at once, stopping empties it, and resuming prepares a new one before the next movement visit.

Once locked on, it attacks. An attack starting from zero is credited the whole of a run-down load, so the first hit lands nine ticks after the lock and the rest follow at the hit speed. Each hit lands on the target and takes its hit points down by the character's damage at its level. A tower whose hit points reach zero is dead: it leaves the holder in the closing cleanup of the tick it dies, and every other entity is told at once. A character that was attacking it drops the reference and, with an attack running, stands for the attack finish time before it takes its next target and resumes its walk. The whole kill run, a Knight destroying a princess tower and then the king tower, reproduces tick for tick, and the engine writes such a run out in the layout of the reference trajectories.

A character with a projectile row fires instead of hitting. Each of its hits creates its projectiles in the attack tick, starts them the unit's launch radius along the line to the target and its launch height up, and aims them at where the target stood at the start of the visit. A projectile is an entity of the holder like a character, of its own kind, so it flies in the post-hook pass of every tick from the one after its launch, ahead of every character: a homing projectile re-pins its aim onto its target each step, moves its speed along the line and takes the height the arc gives there, and arrives on the step that reaches its aim. On arrival it is released, which makes it removable, and its impact deals its row's damage at its level - or the crown-tower share of it to a crown tower - to a target that still has hit points. A projectile whose target left the battle flies on to where the target stood and lands on nothing. A projectile row with a radius does not hit its target alone: its impact damages everything the area damage collects in the circle around its aim - an entity the validator accepts, on a layer the row reaches, inside the circle (a building by its square), and at most one tower-slot entity per area - and spares the launcher's own side only when the row says so. The Musketeer run, a Musketeer shooting a passive princess tower down in fifteen shots, reproduces tick for tick, launches, positions and impacts included.

The princess towers fight back. Every tower carries the same targeting component as a troop, in the same slot, and no movement component, so it stands for its whole life except to attack. With nothing in range it holds the opposing side's tower its default selection gives as its reference, out of range, and selects again every tick; a unit that walks into its range is locked on at once, and the tower fires its projectile row on the attack ticks through the same projectile path a Musketeer uses. The building branches of the targeting visit apply: a target that steps beyond the range and both radii is dropped at once, without the extension a unit keeps a target by, and a hit is never cancelled for distance. When the target dies the removal clears the reference and starts the target-lost countdown, and after it the tower returns to standing and takes its default reference again. A tower's post-hook is the state visit, which steps its elapsed time from its first tick, followed by the combat gate, which keeps the targeting component off while the tower is inactive. A king tower is inactive from creation; its activation is not modelled yet, so a king is visited once, on its first tick, and never fights. Two runs reproduce tick for tick: a Knight walking up the left lane, which the princess tower in front of it locks on at 130, shoots every sixteen ticks from 145 and kills with the fourteenth arrow at 357 after the Knight's five hits, a Musketeer that fires four shots at the same tower before the tower's sixth arrow kills it, and a Wizard whose three fireballs land on the tower through the area damage before the tower kills it.

- `BattleGoldenTrajectoryTest` replays the six Knight reference trajectories through `Battle` and asserts position, state, route length and target after every step, the inner deployment among them, which holds the lane rule's first ten ticks. Reference tick `n` is battle step `n + 1`, and nothing is shifted to make that so: it is what running commands after the entity tick produces.
- `BattleTrajectorySweepTest` replays 48 more reference trajectories the same way: sixteen ground units whose speed, attack range, sight range, collision radius and deploy time all differ, deployed at random points on both sides, two thirds of them switching from the king tower to a princess tower on the way. A change to a cell cost, the default target rule, the endpoint scan or lane assignment moves a route somewhere in here even when it leaves the five Knight walks alone.
- `BattleMultiUnitParityTest` runs multi-unit scenes through both engines and requires identical positions and states on every tick, which a single-unit trajectory cannot do, because with one unit a per-entity order and a per-pass order cannot be told apart.
- `BattleKillRunTest` drives the kill run, a Knight destroying the princess tower and then the king tower, and holds the battle to the whole of it: every one of the 1258 records, with the Knight's position, state, target, route length and movement budget and the target's remaining hit points; the tick and remaining hit points of every one of the forty hits and both deaths; the removal of a dead tower in the tick it dies, the five ticks its attacker stands without a target, and the resume; the hit points every tower and the Knight start the run with; and the damage of one Knight hit, all at the reference's level.
- `BattleMusketeerRunTest` drives the Musketeer run, a Musketeer shooting the left princess tower down while the towers stand passive, and holds the battle to what the Musketeer's outside shows: the lock at 155, a shot every twenty ticks from 168 with the tower untouched at the moment each leaves, the tower's hit points falling only when a shot arrives eight ticks later, the death on the fifteenth impact and the removal in that tick, every one of the 457 records, and the Musketeer's own hit points at its row's rarity.
- `BattleProjectileFlightTest` holds the projectiles of that run themselves: every launch with its id, row, owner, target, start and aim, every position after every flight step, every impact with its damage and the tower's remaining hit points, the projectile's place ahead of every character in the holder while it flies and its removal in the tick it arrives, and a shot whose target is destroyed mid-flight flying on to where the tower stood and landing on nothing.
- `BattleTowerRunTest` drives the three runs in which the towers fight, a Knight, a Musketeer and a Wizard on the left lane, and holds the battle to every record including the unit's own hit points, to every launch, impact, hit and death in the order the battle made them, and to every projectile position.
- `BattleTowerTargetingTest` holds the towers' own targeting to the same runs: the reference every tower holds on every tick, the princess tower's lock, the target-lost countdown the unit's removal starts, a king tower visited once and then switched off, a tower's elapsed time stepped from its first tick, and a passive tower that never selects.
- `AreaDamageTest` holds the area damage's rules one at a time, the ones the Wizard run's single victim does not reach among them: the owner's side spared unless asked, a unit's own radius and a building's square in the circle test, the crown-tower damage, one tower-slot entity per area, the limit, the split rounded up and the air gate.
- `TrajectoryRecorderTest` plays both kill runs and the three runs with the towers fighting with a `TrajectoryRecorder` attached to the battle's world and holds each file it writes to the committed reference byte for byte. The recorder writes a character's run in the layout of the reference trajectories, the header, the towers, the events, one compact record per tick and, for a run with projectiles, every projectile position, so a run the engine plays can be compared with a reference directly, or become a fixture. A run with the towers fighting also carries the unit's own hit points on every record and the towers' own events.

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
| M3 (in progress) | Done: ids per kind and the projectile as an entity of the holder, from launch to impact, with its removal notice; the targeting component on the towers, so the princess towers shoot; the area impact of a projectile with a radius. Next: king tower activation, and the area damage of a unit that splashes with a direct hit | done: a Musketeer shooting a passive princess tower down, every launch, projectile position and impact; a princess tower shooting down a walking Knight, a Musketeer and a Wizard, the lock and every launch, impact and record with the unit's own hit points, the Wizard's fireballs through the area damage; next: the king tower waking when a princess tower falls, a unit that splashes with a direct hit |
| M4 | Deployment as a command: placement, the formation of a multi-unit card, the stagger, the states before the first move, placement validation | multi-unit deployments, including at the arena's edge |
| M5 | The action interpreter behind `EntityActions`: scheduling, the three phases, the composites, then the leaf actions by how often the card data uses them; the expression evaluator, game tags and object filters; loaders for the data they need | per-action fixtures, then whole cards whose behaviour is only expressed as actions |
| M6 | Buffs and status effects, area-effect entities, spells | per-card fixtures; waits until the behaviour is established |
| M7 | The rest of the character: air, jumping and hovering movement, dash and charge, attached units, spawner buildings, building lifetime, death spawns; then a sweep of the card library | per-card fixtures |
| M8 | The mode: match clock, elixir, hands, crowns, how a match ends | waits until the behaviour is established |
| M9 | Switch the Python bridge and the visualizer over, benchmark throughput against the original engine, retire it, merge to `main` | the bridge's own test suite |

M5's interpreter, composites and evaluator do not depend on M2 to M4 and can proceed beside them. Air, jumping and hovering units join in M7; until then `CharacterEntity` refuses them rather than guess.

**Ready for `main`** means: the original engine is gone or no longer the default, the bridge's tests pass on `Battle`, throughput is at least the original engine's, no class in the battle package is a guess, and the fidelity report says what is still partial and why.

## Known assumptions carried today

These are supplied answers, recorded on the classes that carry them and listed here so they are not mistaken for settled behaviour.

- A deploying character's targeting and movement components return at once. The reference trajectories encode this; whether the components run and find nothing to do, or are not run, is not settled.
- A king tower is never activated, so it fights in no run; which tag keeps it inactive and what wakes it are established but not yet ported. A building with no reference resets its attack only when it has hit points at the first level, as every tower has; no reference run reaches that branch, because a tower always has a default tower to fall back on.
- The towers are in the holder from the battle's first step, one step before a unit placed on tick 0 is first visited, while the reference runs start towers and unit together. A tower's elapsed time therefore runs 50 ms ahead of the reference's; nothing is in any tower's range on that step, and the default selection it feeds gives the same tower either way.
- Every tower, king or princess, is a crown tower: the selector notices it from 2000 units farther, the index orders it last and a hit on it deals the crown-tower damage. Only the king fills its side's tower slot, which the validator's tower filters read.
- Every entity carries hit points and damage at its level, packed against its own row's rarity - the rows are published at the first level and the card's rarity only positions the level - and the towers scale as Common. A hit of a unit without a projectile lands on its target and lowers it; a unit with a projectile row launches it, and the projectile's impact lowers its one target by the row's damage at the projectile's level. A projectile row with a radius damages the circle around its aim instead; its target limit is not carried and taken as none, and a row that flies to a point or only heals does nothing on arrival. A unit that splashes with a direct hit still hits its target alone, and the buffs that change an amount are not modelled. An entity whose hit points reach zero is dead and leaves the holder in the closing cleanup of the tick it dies, as the reference run has it; what a death does beyond that - the rewards, the death spawns, what a destroyed tower does to the match - is not modelled.
- A projectile flies straight to its aim with the height the arc gives; the deflection pass finds nothing, the projectile's own collision radius is zero, and the draw that spreads the further projectiles of a multi-projectile attack answers zero. The hits along a flying body's path, the pushback on impact, the on-impact spawns, the chained hop, the pingpong sweep, the ring scatter, the drag-back hook and the delays before a flight are not modelled; no row the reference runs use has them.
- Neither side of a hit carries a buff that changes the damage, nothing is untouchable or immune, and the battle never holds damage. These are the answers the reference run was produced with; what would change them is not established.
- A removable entity - one that has asked to be removed or has no hit points left, or a projectile that has arrived - leaves the holder in the closing cleanup of the tick it becomes removable, and every remaining entity is told inside that cleanup: a character drops a reference to it, starting the target-lost countdown when an attack was running, its default targets lose it, and a projectile aimed at it keeps its aim where it stood and forgets it. Whether the removed entity is told of its own removal, and what the removal does to a held projectile or a followed entity, is not modelled.
- Both command passes read one queue with one rule, due when the command's tick is not after the battle's. Which commands belong to which pass is not settled.
- The mode never ends the match and always lets the entity tick run.
- The assumptions of the movement and targeting rules themselves are listed in [Troop Pathfinding](pathfinding.md#assumptions) and apply unchanged.
