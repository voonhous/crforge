# Troop pathfinding

The simulator has two sets of movement and target-acquisition rules. Hand-authored bridge waypoints are still the default; a grid mode that routes cell by cell over the arena now exists beside it and is selected per match. Complete troop trajectories are still not validated against the shipped game.

## Modes

`org.crforge.core.match.PathfindingMode` has two values.

- `WAYPOINTS` is the default. A per-tick steering angle is taken from the bridge and river geometry, a fractional speed is integrated through the position's sub-unit carry, `PhysicsSystem` separates colliding circles and clamps to the arena bounds. Every troop is handled this way.
- `GRID` runs ground troops through `org.crforge.core.pathfinding.GridPathfindingSystem`: a route over the arena's routing grid with a building footprint overlay, an integer per-tick movement budget, and target selection through the spatial index and the tower default selection.

A mode is a match-level decision, fixed when the match is created. `new Standard1v1Match(towerLevel, PathfindingMode.GRID)` selects the grid mode; the two older constructors keep `WAYPOINTS`. In the debug visualizer the `M` key flips the mode and the change takes effect on the next reset.

The grid system owns a troop only when `GridPathfindingSystem.manages(troop)` is true: a ground movement type, not jump-enabled, not hovering, not currently jumping, not tunnelling and not attached to a parent. Everything else in the same match keeps the waypoint rules, `TargetingSystem` and `PhysicsSystem`. A troop that flips between the two - a Miner while it tunnels - keeps its `GridUnitState` across the flip and its position is re-read each tick, but its route is not recomputed for the gap.

Entities the grid does not drive are still indexed and stamped into the overlay, so a managed troop can see them, route around them and target them.

## Movement states

Movement without a nearby enemy, movement without combat lock, and movement with no target reference are distinct cases and are handled separately. Default movement can carry a destination target reference. A change in direction after acquiring an enemy does not by itself imply a separate pursuit planner, so the design does not assume one.

In the grid mode the four items previously listed here as unresolved are now implemented. Default destination selection is seeded with the opposing side's king tower and ranks that side's registered towers in placement order. A troop's lane is assigned once when it is created, from the road id of the nearest road cell, and is never recomputed. The combat lock is the targeting visit putting the entity into the attacking state, which the system mirrors into `Combat`. Losing a reference raises a resume request, which returns the entity to the moving state and lets route preparation pick a new destination on the next visit.

`GridEntityState` names seventeen states. A managed troop on the ordinary path uses four of them: 4 while it is being placed, 1 while it walks, 2 while it attacks, 0 while it stands.

## The grid tick

The engine calls the system twice per tick, with its own combat step in between: `updateTargeting` immediately before `TargetingSystem.updateTargets`, and `updateMovement` immediately before `PhysicsSystem.update`. `AbilitySystem`, `CombatSystem` and `TransformationSystem` stay between the two and keep dealing damage.

`updateTargeting`:

1. Refresh one entity view per live entity and order them by ascending entity id, which is creation order. The towers and buildings of a standard match are created first, then troops.
2. Rebuild the spatial index from that ordered list.
3. Build the footprint overlay from the same list and copy the per-side change flags.
4. Per managed troop, in the same order, skipping a troop that is still deploying: run the targeting visit, then the resume tail when the visit asked for one, then the write-back into `Combat`.

The targeting visit steps its countdowns, decides whether the reference it holds is still worth keeping, re-selects one when it is not, and decides whether an attack happens this tick. Candidate selection is a circle query around the unit with king towers ordered last; validation drops a reference that is dead, out of range or no longer valid; the range test decides whether the unit can attack from where it stands; default selection supplies a tower when nothing else qualifies; an attack puts the entity into the attacking state.

`updateMovement`:

5. Per managed troop, skipping a deploying one, run the movement visit. It announces route preparation, the route follower, the avoidance handler, the push pass and each displacement, and `MovementChain` runs each pass at the point it is announced so that the passes see each other's writes.
6. Per managed troop, in a second loop over all of them, run the entity state visit, then write the troop's position and facing back.
7. Post-pass: rotate the overlay and clear the index.

The second loop is separate because all movement visits run before any entity state visit.

What the system writes outside its own state:

- `Combat.setCurrentTarget(...)`, and only when the chosen target differs from the one the troop already holds, because that setter resets the attack wind-up and the attack state machine.
- `Combat.setTargetLocked(true)` on every tick the targeting visit has put the troop into the attacking state. The lock is never cleared here: dropping the reference clears it through the target setter, and `CombatSystem` owns the rest.
- `Position.set(x, y)` with the integer position the movement pass produced, and `Position.setRotation` with the angle of the facing vector when it is non-zero. The grid mode integrates in whole game units and does not use the position's fixed-point carry.

Nothing is written while a troop is deploying, so its target stays null for the whole countdown. Damage, buffs and the troop's own `deployTimer` are left alone; the grid deploy countdown runs beside the timer and both are twenty ticks for a one second deploy time. `Position` is read back at the head of every tick, so a troop displaced by knockback, a Tornado, a hook or an ability is picked up rather than snapped back.

## Grid and routes

Path cells span 500 game units. Ordinary cell-center waypoints use `(500 * column + 250, 500 * row + 250)`. Position-to-cell conversion truncates division toward zero.

Routes use row-major cell IDs and are stored destination-first. Following consumes the last node. An empty route uses the current position as its waypoint. Special movement states can override ordinary waypoint selection.

The standard arena is 36 by 64 cells, 18000 by 32000 game units. Each published cell value contributes only its low seven bits to routing: bits 0 and 1 are the road id (lane), 0 for none; bit 4 is not placeable; bit 5 is water; bit 6 is blocked, which no cell on the standard map sets. Higher bits exist in the published data for a few cells; no routing rule reads them, so `TileMap` masks them away and never exposes them. What they mean is not established. Road 1 covers columns 4 to 17 and road 2 columns 18 to 31; water is rows 30 to 33 except at the bridge columns.

## Search behavior

The search uses eight neighbors in this order: up, down, left, right, upper-left, lower-left, lower-right, upper-right. Cardinal steps multiply destination-cell cost by 10; diagonal steps multiply it by 14.

The search does not itself require adjacent cardinal cells to be traversable before considering a diagonal. Whether terrain preparation should impose an equivalent restriction is still open.

For absolute cell differences `dx` and `dy`, heuristic method 0 uses `10 * max(dx, dy)`. Methods 1 and 2 use `10 * max(dx, dy) + 4 * min(dx, dy)`. Priority adds the weighted heuristic to the accumulated movement cost. An alternative mode instead carries the previous priority, accumulating heuristic contributions along the route. The active configuration is method 1 with a heuristic weight of 5, open-node refresh on and closed-node reopening off.

Open-node refresh and closed-node reopening have separate flags and require a strictly lower priority. Heap insertion preserves equal-priority order locally by avoiding equal swaps. During removal, equal lower children favor the right child. A generic priority queue can therefore produce different routes even at identical costs, so this heap behavior is part of the specification rather than an implementation detail.

The start is expanded before it is marked closed. A popped goal is expanded before the termination check. A positive search budget counts popped-node expansions; a nonpositive budget is unlimited.

Returned routes normally exclude the start. If the initial expansion leaves no open nodes, the result retains the start alone. Budget exhaustion can return a reconstructed route to a goal that has a parent but has not been closed. Callers must handle both cases explicitly.

## Cell costs

Cost evaluation distinguishes water permission, blocked cells, default terrain, roads, matching roads and dynamic overlays. A positive blocked-cell cost is a penalty, not automatic rejection. Out-of-bounds cells are rejected.

Some terrain and movement-state branches return their cost before dynamic overlays are applied. In the ordinary terrain branch, an active overlay uses the maximum of base cost and overlay cost. Road preference can depend on whether the road ID matches the unit's lane value.

The active cost constants are: default terrain 7, road 5, a road whose id matches the unit's lane 5, water 5 when the unit is permitted on water and 100 when it is not, and the building overlay 100. These rules alone do not predict which bridge a troop will choose.

The overlay is rebuilt each tick before any component pass. Every entity of the routing type that answers that it occludes has a square box stamped around it at the building cost, sized from its own collision radius, and its id is folded into a running hash of its side. Each side's change flag then says whether that side's hash differs from the previous build's, which is what route retention consults before keeping a cached route. The flags therefore react to which occluders exist and in what order, not to where they are.

## Endpoint, relocation and lane

The route search is not asked to reach the target itself but a cell near it. The endpoint scan covers a square around the target's cell reaching `range / 500 + 1` cells each way, clipped to the map; rows run low to high, and within a row columns run left to right while the unit stands strictly left of the arena's centre line and right to left otherwise. A cell qualifies when its centre is within `range` of the target. Qualifying cells are ranked: an ordinary cell ranks 2, a cell the unit would rather not stop on ranks 1. For a ground unit water ranks 1, and so does a cell under a building footprint. **Rank 1 is a preference, not a rejection**: the acceptance test is asked with the flag that accepts every in-bounds cell, so water and building cells only change the rank. A higher rank wins; within a rank the cell closest to the unit wins, and the first cell reached wins a tie.

Relocation moves a point off water when a unit is pushed or placed onto the river. The point is clamped 250 units inside every arena edge; if its cell is not water it is returned as it stands. Otherwise eleven rows 500 units apart around its own y, and columns from 2250 units left to 2750 units right, are scanned at cell centres for the nearest dry point. A reference y narrows the rows to the side of the river the unit came from. If nothing qualifies the clamped point is returned unchanged.

A unit's lane is the road id of the road cell nearest to its own cell, in squared cell distance, with ties going to the first cell in column-major order. It is written once when the unit is created and never recomputed.

## Speed convention

The speed column of the unit data is world units per tick in the grid mode. A Knight's 60 is 60 units per tick, so 1200 units per second, so 1.2 tiles per second. The waypoint mode converts the same column with `GameUnits.rawSpeedToUnitsPerSecond`, which treats 60 as one tile per second, so 1000 units per second. **The same unit therefore walks at different speeds in the two modes**; the grid figure is the one the reference trajectories encode.

The column itself is carried on `Movement.rawSpeed`, populated wherever a component is built from a unit's stats. A component built by hand, which only test fixtures do, leaves the column at zero and the grid system recovers it by inverting the loader's fixed factor.

## Reference trajectories

Three golden cases pin a whole Knight deployment on the standard arena with nothing on it but the six crown towers.

| Case | Deploy | Target | Attack lock | Stops at |
| --- | --- | --- | --- | --- |
| `knight_left` | (3500, 10000) | PrincessTower_1_1 | tick 235 | (3731, 22854) |
| `knight_right` | (14500, 10000) | PrincessTower_1_2 | tick 235 | (14731, 22854) |
| `knight_centre` | (9000, 12000) | KingTower_1_0, then PrincessTower_1_2 from tick 82 | tick 242 | (13731, 22952) |

The centre case is the interesting one: the king tower is closest in x from the deploy point, so the unit walks at it until a princess tower becomes closer in x at tick 82.

`GridGoldenTrajectoryTest` drives each case through `GameEngine` with `Standard1v1Match(11, GRID)` and asserts, after **every** engine tick, the troop's x and y, its grid state, how many nodes are left on its route, and that the target it holds is the tower the record names. At the end it also asserts the stop position, the attacking state, the locked tower and that the combat lock is set.

Reference tick `n` is engine tick `n + 1`, because a spawn is queued and flushed at the head of the following tick. One state offset is corrected for in the test rather than hidden: a reference trajectory records a deploying unit **before** its state visit and every other unit after it, so on the tick whose state visit ends the deployment the record still says deploying while the engine, asked after the whole tick, already says moving. That one-tick correction is the only transformation applied; positions, route lengths and references are compared raw.

**These files are not captures of the shipped game.** No such capture exists yet. Each one is the tick-by-tick output of a model of the game's movement and targeting rules. A disagreement between the simulator and a file means the simulator disagrees with the model, not necessarily with the game. The format and the per-run routing answers are described in `core/src/test/resources/pathfinding/README.md`.

## Assumptions

Each of these is a supplied answer or a deliberate choice, not something the trajectories demonstrate.

- **Entity order.** Entities are indexed once per tick, in creation order, before any component runs. Creation order is ascending entity id, so the towers come first. Index membership is fixed for the tick while the shape tests read live positions.
- **End of deployment.** A unit with a movement component leaves the deployment countdown in the moving state; a unit without one stands.
- **Default target candidates.** Default selection ranks the opposing side's towers in placement order, King first and then the two princess towers ordered by x, and the king tower is both the seed and a member of the candidate list. Excluding the king from the list changes the centre case's answer, so this detail is load bearing. Ordinary troops never enter these lists.
- **Game-mode answers for the standard mode.** The alternate-seed branch, the alternate-goal branch and the special-object branch of default selection are all inactive; the x-position rule is the live path.
- **Selector query.** Candidates come from a circle query around the unit whose radius is the sight range plus the 2000-unit crown-tower sight bonus plus the unit's own collision radius, with king towers ordered last.
- **Endpoint acceptance.** The endpoint scan accepts every in-bounds cell; water and building overlays only change a cell's preference rank and never reject it.
- **Movement answers.** No game-mode goal, no touchdown edge separation, no charge-range alternative, no facing suppression, and the pushed-ground branch of the displacement is off. That branch is the only thing that marks a unit stuck on water and nudges it away from the river line, so with it off the grid move's water test is never armed either.
- **Targeting switches.** Five switches are answered differently from the published values, because that is what the reference trajectories encode: attack finish time 0 rather than 250 ms, compare-using-hit-started off rather than on, current-target-ignores-pending-damage off rather than on, load-first-hit reset-timer-after-attack off rather than on, and load-first-hit keep-loaded-after-discard off rather than on.
- **Status effects.** Slow, rage and freeze do not feed the grid speed budget. A slowed or raged managed troop walks at its base speed.
- **Deployment lane flag.** The flag that lets a deploy position push a unit into the far lane is not modelled; every lane is the plain nearest-road answer. On the standard map both forms give the same answer.
- **Dead towers.** A destroyed tower leaves the candidate list at the end of the tick in which it dies: its view, its registration, the seed and any held reference to it are all dropped. The side lists' own compaction is not established, so this is a decision, not an observation.
- **Buildings other than towers**, including a hidden Tesla, get the tower configuration and occlude the routing overlay.
- **Air neighbours** are given a height of one and ground entities zero, so the push pass's layer test keeps them apart the way the simulator's collision test does. The simulator has no heights, so this is a modelling choice.
- **The per-neighbour contact flag** the avoidance handler reads is supplied, its writers are not documented, and it is answered as accepting.
- **Tower targeting columns** are taken from the simulator rather than hard-coded, so a princess tower carries a sight range of 9500 here where the published tower data says 7500. A candidate's own sight range is never read by the attacker's selection, so this is not observable.
- **The follower advances to the next node** as soon as the remaining distance projected on its route direction drops to 1000 units, so a unit effectively aims two nodes ahead and clips the corner of the cell it enters a bridge through. Whether the shipped game does exactly this is not settled. The model produces it and the golden files encode it, so the simulator matches the files but not necessarily the game.

## Observed anomalies

These are behaviours of the current model that were found by running several units at once. Each is recorded on every smoke run and asserted in a disabled test named after it; nothing was changed to hide them and no live invariant was weakened.

1. **A walking troop with a target has no route for one tick.** Two Knights facing each other, at ticks 67 and 69 for both of them; the skeleton scenario at ticks 226 and 245. The follower pops the last route node when a displacement reports arrival, and route preparation only rebuilds the route at the head of the next visit, so the route cannot be non-empty at the end of that tick. Reproduce with a BLUE Knight at (3500, 12000), a RED Knight at (3500, 20000), 171 ticks. What does hold, and is asserted live, is that the route is never empty for two ticks running.
2. **A pushed troop walks on water.** Only in the swarm scenario: 21 troop ticks on water in total, the longest a ten-tick run from (2326, 15002) to (2489, 15447), plus an eight-tick run from (4608, 15040) and a three-tick run from (2481, 16786). The swarm pushes its own members sideways off the bridge, and with the pushed-ground branch off nothing marks them stuck or nudges them back. Reproduce by playing `skeletonarmy` from BLUE's hand at (3500, 10000) and running 267 ticks. The other five scenarios assert that no troop ever stands on water and pass.
3. **A pushed troop ends up inside a tower footprint.** Same scenario and reproduction: one skeleton inside a princess tower's collision circle for twelve consecutive ticks from tick 197 and another for one tick at 226, 13 troop ticks in total. Fifteen skeletons crowd the tower, the push pass sums what surrounds one of them and the displacement applies the average with no test against a building footprint. The routing overlay keeps a *route* out of a building's cells; a push is not routed.
4. **A formation deployed at an arena corner leaves part of itself outside the arena.** A Skeleton Army played from BLUE's hand at (500, 1500): seven of its fifteen places land outside the arena at (-1500, 3500), (-2250, 1500), (-1250, 2000), (-750, 1000), (-1250, 0), (1500, -500) and (3000, -1000), and each stays there for all 381 ticks from tick 21 to the end of the run - 2667 troop ticks outside the arena. A formation's units are placed at their raw offsets from the deploy point with no test against the arena, and nothing brings a grid-driven troop back: the waypoint rules clamp every unit to the arena once per tick, but that clamp runs only over the troops the grid system does not own. Standing off the routing grid, the seven get no route at all, so from tick 42 each of them also breaks the run's hard invariant against walking toward a target with no route for two ticks running; those seven lines are pinned as well. Reproduce by playing `skeletonarmy` from BLUE's hand at (500, 1500) and running 401 ticks. The other six runs assert that no troop ever leaves the arena and pass.

## Test coverage

The search passes 877 cases covering returned routes, node states, parents, carried costs, priorities, heap order, counters and remaining budget. The cost function passes 98,308 cases over synthetic terrain and supplied entity properties.

Search coverage spans heuristic methods 0 through 3, positive synthetic costs from 1 through 100 or rejection, and bounded arithmetic. Overflow behavior is not covered. The cost tests do not exercise real arena maps or obstacle generation. Neither set establishes end-to-end trajectory agreement.

## What remains unvalidated

The integration requirements this document previously listed are now implemented: default destinations, lane initialization and combat-lock transitions; the active configuration and the real terrain and dynamic cost maps; endpoint adjustment, cached-route retention and waypoint acceptance; movement rounding, collision, pushing and recovery after displacement; and the update cadence. What is left is the comparison itself and the cases outside the current scope.

- **Complete trajectories against captures of real matches.** No recordings exist. The golden files are model output, so agreement with them is agreement with the model.
- **Air units, jump-enabled units, 2v2 and event maps** are out of scope. Air units are never grid-managed, so both water permissions are false for every managed unit. The five jump-enabled units wait on the jump target setter.
- **Blocks the entity state visit does not carry**: the buff a unit gets while it is not attacking, the self-damage of a kamikaze unit, elixir generation, the hide handling, the morph timer with its growth scale, and the live spawner. Each is gated by a configuration column belonging to a part of the simulation outside movement and routing, and none of them changes a state or a position that routing reads.
- **The dash and jump branches, and negative speeds**, are implemented but unexercised. Nothing on the current paths produces a negative speed budget.
- **The in-game pathfind end state.** Whether arriving at a mid-match destination ends in the deploy state or the moving state is a configuration column whose value is not established; it is answered as the moving state and is not reached on any current path.
- **Side-list compaction after a tower dies.** Assumed, not established; it affects default selection once a tower has been destroyed.
- **Tile map bits above 6.** Ignored, which means their meaning is not established. The bridge-centre and marker cells are not given a meaning.

## How to check

- `core/src/test/java/org/crforge/core/pathfinding/GridGoldenTrajectoryTest.java` replays the three Knight cases through `GameEngine` and compares every tick.
- `core/src/test/java/org/crforge/core/pathfinding/GridSmokeScenariosTest.java` runs several units at once, 600 ticks or a hundred ticks past the first attack lock, and checks the per-tick invariants on every grid-driven troop; the four disabled tests at the bottom hold the anomalies above.
- The debug visualizer's `M`, `G`, `N`, `S` and `E` keys flip the mode, paint the cell costs, draw routes and references, run the golden scenarios and export trajectories. See the controls table and the grid pathfinding overlays section in [architecture.md](architecture.md).
- `E` writes one file per recorded ground troop to `build/trajectories`, sampled once per tick from the tick the troop appeared in: `{"card": ..., "deploy": [x, y], "side": 0, "samples": [{"tick": 0, "x": ..., "y": ...}]}`, with positions in game units and a troop's ticks starting at 0 on the tick it first appeared.
