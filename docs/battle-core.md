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
    BattleWorld       the routing grid, the building overlay and the spatial index, rebuilt per tick
    WorldEntity       an entity that stands on the arena
    CharacterEntity   targeting in slot 0, movement in slot 1, the state visit as the post-hook
    TowerEntity       a crown tower
    UnitData          the published columns of a unit, unconverted
    Standard1v1Battle the standard arena with its six towers
```

The movement, targeting and state rules themselves live in `org.crforge.core.pathfinding` and are shared with the original engine's grid mode; see [Troop Pathfinding](pathfinding.md). The battle core runs them directly. The original engine reaches them through `GridPathfindingSystem`, which has to split one tick into two calls around its own combat step and copy the result back into its own components.

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

1. Cleanup: drop removable entities, then admit the entities added since the last cleanup, giving each its id as it is admitted.
2. Take a snapshot of the live list. Every entity loop below runs over it.
3. The holder pre-pass: the spatial index and the building overlay are rebuilt from the snapshot.
4. Every entity's pre-hook.
5. Every entity's pending actions of phase 1.
6. One whole-list pass per component slot, lowest first: the component's refresh, then its visit when the component is switched on. Targeting is slot 0, movement is slot 1.
7. Every entity's running actions.
8. Every entity's pending actions of phase 2.
9. Every entity's post-hook, which for a character is its state visit.
10. The holder's after-post-hooks step, then every entity's pending actions of phase 3.
11. The holder post-pass: the overlay is rotated and the index cleared.
12. Cleanup again.
13. The end-of-tick action countdown, over the live list rather than the snapshot.

Three consequences that everything else leans on: entities are visited in ascending id, which is creation order; an entity added during a tick is not visited until the next one; and an entity that becomes removable during a tick is visited for the rest of it and is gone before the next snapshot.

## What is covered today

A ground character deployed on the standard arena walks its lane, picks its target, routes around buildings, is pushed and steered by other characters and locks onto a tower, tick for tick as the reference trajectories record it. Its state changes carry what the standard game attaches to them: taking a target prepares the route at once, stopping empties it, and resuming prepares a new one before the next movement visit.

- `BattleGoldenTrajectoryTest` replays the five Knight reference trajectories through `Battle` and asserts position, state, route length and target after every step. Reference tick `n` is battle step `n + 1`, and nothing is shifted to make that so: it is what running commands after the entity tick produces.
- `BattleTrajectorySweepTest` replays 48 more reference trajectories the same way: sixteen ground units whose speed, attack range, sight range, collision radius and deploy time all differ, deployed at random points on both sides, two thirds of them switching from the king tower to a princess tower on the way. A change to a cell cost, the default target rule, the endpoint scan or lane assignment moves a route somewhere in here even when it leaves the five Knight walks alone.
- `BattleMultiUnitParityTest` runs multi-unit scenes through both engines and requires identical positions and states on every tick, which a single-unit trajectory cannot do, because with one unit a per-entity order and a per-pass order cannot be told apart.
- `EntityHolderTest` and `BattleTest` pin the two orders above line by line.

The reference trajectories are the output of a model of the game's rules, not captures of the game. What that means for a disagreement is set out in `core/src/test/resources/pathfinding/README.md`.

## Roadmap

The work lives on the `battle_core` branch and reaches `main` only when the engine is ready; every change is a pull request into `battle_core`.

Each milestone lands test first, against a reference it can be held to, and is driven through `Battle.step()` rather than by calling its rules directly. A milestone whose behaviour is not established yet does not start from a guess; it waits.

| Milestone | Content | Held to |
| --- | --- | --- |
| M1 (done) | The step, the entity tick, characters walking and locking on | five Knight trajectories, a 48-trajectory sweep over sixteen units and both sides, multi-unit parity with the grid mode |
| M2 | Hits: the attack timer and its load, level scaling, hit application, damage modifiers, hit points and shields, death and removal, a destroyed tower leaving the target lists | a Knight destroying a princess tower and then the king tower: the tick and remaining hit points of every hit, and every position of the 1253-tick run |
| M3 | Buildings as attackers, projectiles from launch to impact, area damage, king tower activation | tower shots against a walking unit: lock, launch and impact ticks |
| M4 | Deployment as a command: placement, the formation of a multi-unit card, the stagger, the states before the first move, placement validation | multi-unit deployments, including at the arena's edge |
| M5 | The action interpreter behind `EntityActions`: scheduling, the three phases, the composites, then the leaf actions by how often the card data uses them; the expression evaluator, game tags and object filters; loaders for the data they need | per-action fixtures, then whole cards whose behaviour is only expressed as actions |
| M6 | Buffs and status effects, area-effect entities, spells | per-card fixtures; waits until the behaviour is established |
| M7 | The rest of the character: air, jumping and hovering movement, dash and charge, attached units, spawner buildings, building lifetime, death spawns; then a sweep of the card library | per-card fixtures |
| M8 | The mode: match clock, elixir, hands, crowns, how a match ends | waits until the behaviour is established |
| M9 | Switch the Python bridge and the visualizer over, benchmark throughput against the original engine, retire it, merge to `main` | the bridge's own test suite |

M5's interpreter, composites and evaluator do not depend on M2 to M4 and can proceed beside them. Air, jumping and hovering units join in M7; until then `CharacterEntity` refuses them rather than guess.

**Ready for `main`** means: the original engine is gone or no longer the default, the bridge's tests pass on `Battle`, throughput is at least the original engine's, no class in the battle package is a guess, and the fidelity report says what is still partial and why.

## Known assumptions carried by M1

These are supplied answers, recorded on the classes that carry them and listed here so they are not mistaken for settled behaviour.

- A deploying character's targeting and movement components return at once. The reference trajectories encode this; whether the components run and find nothing to do, or are not run, is not settled.
- A tower has no components, so it never attacks.
- Hits are decided but land on nothing, so no entity ever loses hit points and nothing is ever removed.
- Both command passes read one queue with one rule, due when the command's tick is not after the battle's. Which commands belong to which pass is not settled.
- The mode never ends the match and always lets the entity tick run.
- The assumptions of the movement and targeting rules themselves are listed in [Troop Pathfinding](pathfinding.md#assumptions) and apply unchanged.
