# Troop pathfinding

`org.crforge.core.pathfinding` holds the movement, target-acquisition and state rules the battle core runs: the routing grid and its cell costs, the route search, the movement and push passes, the targeting visit and the entity state visit, with the hit points and the damage chain beside them in `org.crforge.core.pathfinding.combat`. The battle core drives them inside its entity tick (see [The battle core](battle-core.md#one-step)). Troop walks are held by the recorded reference battles, Knight walks among them (`smoke-c1/knight`, `smoke-c1/knight_centre_s0`, `smoke-c1/knight_behind_king_s0`, `golden-gaps-v1/walk_knight_left_inner`, `golden-gaps-v1/walk_knight_right_rear`).

## Movement states

Movement without a nearby enemy, movement without combat lock, and movement with no target reference are distinct cases and are handled separately. Default movement can carry a destination target reference. A change in direction after acquiring an enemy does not by itself imply a separate pursuit planner, so the design does not assume one.

The four items once listed here as unresolved are implemented. Default destination selection is seeded with the opposing side's king tower and ranks that side's registered towers in placement order. A troop's lane is assigned once when it is created, from the road id of the nearest road cell, and is never recomputed. The combat lock is the targeting visit putting the entity into the attacking state. Losing a reference raises a resume request, which returns the entity to the moving state and lets route preparation pick a new destination on the next visit.

`GridEntityState` names seventeen states. A ground troop on the ordinary path uses four of them: 4 while it is being placed, 1 while it walks, 2 while it attacks, 0 while it stands.

## Where the rules run

The battle core's entity tick visits every entity of a snapshot in ascending id, which is creation order within a kind; the towers of a standard battle are created first. Before any component runs, the holder's pre-pass rebuilds the spatial index and the building footprint overlay from the snapshot and copies the per-side change flags. Then:

1. The targeting pass (component slot 0), per entity: the targeting visit, then the resume tail when the visit asked for one.
2. The movement pass (component slot 1), per entity: the movement visit. It announces route preparation, the route follower, the avoidance handler, the push pass and each displacement, and `MovementChain` runs each pass at the point it is announced so that the passes see each other's writes.
3. The post-hooks, per character: the entity state visit.
4. The post-pass: the overlay is rotated and the index cleared.

All targeting visits run before any movement visit, and all movement visits before any state visit. The full order of the tick, with the actions between the passes, is in [One step](battle-core.md#one-step).

The targeting visit steps its countdowns, decides whether the reference it holds is still worth keeping, re-selects one when it is not, and decides whether an attack happens this tick. Candidate selection is a circle query around the unit with king towers ordered last; validation drops a reference that is dead, out of range or no longer valid; the range test decides whether the unit can attack from where it stands; default selection supplies a tower when nothing else qualifies; an attack puts the entity into the attacking state.

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

The active cost constants are: default terrain 8, road 5, a road whose id matches the unit's lane 5, water 7 when the unit is permitted on water and 50 when it is not, and the building overlay 50. These rules alone do not predict which bridge a troop will choose.

The overlay is rebuilt each tick before any component pass. Every entity of the routing type that answers that it occludes has a square box stamped around it at the building cost, sized from its own collision radius, and its id is folded into a running hash of its side. Each side's change flag then says whether that side's hash differs from the previous build's, which is what route retention consults before keeping a cached route. The flags therefore react to which occluders exist and in what order, not to where they are.

## Endpoint, relocation and lane

The route search is not asked to reach the target itself but a cell near it. The endpoint scan covers a square around the target's cell reaching `range / 500 + 1` cells each way, clipped to the map; rows run low to high, and within a row columns run left to right while the unit stands strictly left of the arena's centre line and right to left otherwise. A cell qualifies when its centre is within `range` of the target. Qualifying cells are ranked: an ordinary cell ranks 2, a cell the unit would rather not stop on ranks 1. For a ground unit water ranks 1, and so does a cell under a building footprint. **Rank 1 is a preference, not a rejection**: the acceptance test is asked with the flag that accepts every in-bounds cell, so water and building cells only change the rank. A higher rank wins; within a rank the cell closest to the unit wins, and the first cell reached wins a tie.

Relocation moves a point off water when a unit is pushed or placed onto the river. The point is clamped 250 units inside every arena edge; if its cell is not water it is returned as it stands. Otherwise eleven rows 500 units apart around its own y, and columns from 2250 units left to 2750 units right, are scanned at cell centres for the nearest dry point. A reference y narrows the rows to the side of the river the unit came from. If nothing qualifies the clamped point is returned unchanged.

A unit's lane is the road id of the road cell nearest to its own cell, in squared cell distance, with ties going to the first cell in column-major order. It is written once when the unit is created and never recomputed.

## Speed convention

The speed column of the unit data is game units per tick (`SpeedConfig`). A Knight's 60 is 60 units per tick, so 1200 units per second, so 1.2 tiles per second; the walks of the reference battles hold it. The buffs' speed percents scale it (`SpeedBudget`).

## Reference walks

The Knight walks named at the top cover a whole deployment on the standard arena with nothing on it but the six crown towers: a Knight in the left lane, one deployed at the centre, one inside the left lane near the middle, and two deployed right beside one of their own towers - behind the right princess tower and behind the king tower - so that the two passes which look at a unit's neighbours, the push pass and the avoidance handler, are covered with a tower as the neighbour. A unit deployed behind its king is pushed by it while it deploys, because a deploying unit is visited by the movement pass with a speed of zero.

The default target is chosen among the other side's princess towers, with the king as the seed and never a candidate itself (`DefaultTargetSelection`). The chosen candidate has to beat the seed: its squared approximate distance is compared with the seed's true squared distance, so a tower nearer only by the approximation leaves the unit walking at the king. For its first ten walking ticks a unit only considers the towers of its own lane; with both princess towers standing, a unit in its own half always finds its lane's tower the closer in x.

## Assumptions

Each of these is a supplied answer or a deliberate choice, not something the reference walks demonstrate.

- **Entity order.** Entities are indexed once per tick, in creation order, before any component runs. Creation order is ascending entity id, so the towers come first. Index membership is fixed for the tick while the shape tests read live positions.
- **End of deployment.** A unit with a movement component leaves the deployment countdown in the moving state; a unit without one stands.
- **Default target candidates.** Default selection starts from the opposing side's king tower as the seed and ranks that side's princess towers in placement order, left then right; the king is never a candidate, and ordinary troops and the buildings placed during a battle never enter the list.
- **Game-mode answers for the standard mode.** The alternate-seed branch, the alternate-goal branch and the special-object branch of default selection are all inactive; the x-position rule is the live path.
- **Selector query.** Candidates come from a circle query around the unit whose radius is the sight range plus the 2000-unit crown-tower sight bonus plus the unit's own collision radius, with crown towers ordered last. Every tower is a crown tower for the sight bonus, the ordering and the crown-tower damage; only the king fills its side's tower slot, which the tower filters of the validator read.
- **Endpoint acceptance.** The endpoint scan accepts every in-bounds cell; water and building overlays only change a cell's preference rank and never reject it.
- **Movement answers.** No game-mode goal, no touchdown edge separation, no charge-range alternative and no facing suppression. The touchdown mode and the pushed-ground branch of the displacement are settled as off: both are mode settings that none of the standard game's modes sets, the second one opened only by a mode with a capturable building on the arena. That branch is the only thing that tests a pushed unit's cell for water and nudges it away from the river line. The stuck mark and the lifted push cap are written only by an attracting buff's pull, which the battle core runs for the Tornado: it arms the grid move's water test for a ground unit it pulls, and lifts the 150 cap on the averaged push. Without a pull the water test is never armed.
- **The aligned static neighbours check** the push pass asks after it has counted a static neighbour is settled (`AlignedStaticCheck`): it answers yes at the second static entity, one without a movement component, standing exactly on the unit's x or y within 1000 of it, and a yes copies a push along one axis onto the other. With the towers alone it always answers no; it takes placed buildings lined up with the unit.
- **Targeting switches.** The switches the targeting visit reads carry their published values. A lone unit walking to a tower reaches none of six of them, so the Knight walks constrain none of those six. What they do for units that reach them - bursts, special loads, wind-up-before-first-hit units - is therefore held only by the targeting visit's own tests; pending damage is held by the battle core's reference runs, where towers and projectile units fight (see [Battle Core](battle-core.md)).
- **Deployment lane flag.** The flag that lets a deploy position push a unit into the far lane is not modelled; every lane is the plain nearest-road answer. On the standard map both forms give the same answer.
- **Dead towers.** A destroyed tower leaves the candidate list at the end of the tick in which it dies: its view, its registration, the seed and any held reference to it are all dropped. The side lists' own compaction is not established, so this is a decision, not an observation.
- **Contact** is settled (`ContactRule`): every entity takes part, towers and other buildings included, except under the no-check-collisions flag and in the dashing, jumping and first following-removed states; the avoidance handler also drops a neighbour in either following-removed state or under the no-check-avoidance flag. A tower has no movement component, so both passes treat it as a static neighbour, and its mass is 0, so its share of a push is a single unit that still counts in the push count. Supplied: no entity is attached to a parent, no dash-time column is positive, no deploying unit is an unset clone, and no building is of the placeable kind whose answer depends on the entities near it.
- **The follower advances to the next node** as soon as the remaining distance projected on its route direction drops to 1000 units, so a unit effectively aims two nodes ahead and clips the corner of the cell it enters a bridge through. Whether the shipped game does exactly this is not settled. The model produces it and the golden files encode it, so the simulator matches the files but not necessarily the game.

## Several units at once

Three behaviours show only with several units at once. They were first found in the earlier engine's grid movement, and each was settled as the standard game's behaviour against recorded battles; nothing was changed to hide them.

1. **A walking troop with a target has no route for one tick.** The follower pops the last route node when a displacement reports arrival, and the movement visit prepares the route before the follower runs, so a route the follower empties stays empty until the next visit. Two Knights walking at each other show it. The route is never empty for two ticks running.
2. **A pushed troop walks on water.** A swarm pushes its own members sideways off the bridge, and nothing stops them at the water's edge or nudges them back. The displacement has a branch that tests a pushed ground unit's cell for water and nudges it away from the river line, but it is opened only by a mode with a capturable building on the arena, which none of the standard modes is; even with the branch open the first water entries of the recorded battles are the same.
3. **A pushed troop ends up inside a tower footprint.** A crowd of skeletons attacking a tower: the push pass sums what surrounds one of them and the displacement applies the average with no test against a building footprint. The routing overlay keeps a *route* out of a building's cells; a push is not routed. The tower pushes back, but its mass is 0, so its share is a single unit averaged with the crowd's; the aligned static neighbours check the push pass asks there answers no, since only one static entity stands near.

## Test coverage

The search passes 877 cases covering returned routes, node states, parents, carried costs, priorities, heap order, counters and remaining budget. The cost function passes 98,308 cases over synthetic terrain and supplied entity properties.

Search coverage spans heuristic methods 0 through 3, positive synthetic costs from 1 through 100 or rejection, and bounded arithmetic. Overflow behavior is not covered. The cost tests do not exercise real arena maps or obstacle generation. Neither set establishes end-to-end trajectory agreement.

## What remains unvalidated

The integration requirements this document previously listed are now implemented: default destinations, lane initialization and combat-lock transitions; the active configuration and the real terrain and dynamic cost maps; endpoint adjustment, cached-route retention and waypoint acceptance; movement rounding, collision, pushing and recovery after displacement; and the update cadence. What is left are the cases outside the current scope and the pieces below.

- **2v2 and event maps** are out of scope: the battle core plays the standard arena only ([The battle core](battle-core.md)).
- **Blocks the entity state visit does not carry**: the buff a unit gets while it is not attacking, the self-damage of a kamikaze unit, elixir generation, the hide handling, the morph timer with its growth scale, and the live spawner; the battle core runs the not-attacking section and the hide handler where the visit reaches them, and a hiding row's targeting visit at its deploy end. Each is gated by a configuration column belonging to a part of the simulation outside movement and routing, and none of them changes a state or a position that routing reads. They are not inert for the blocks around them, though: two of them end the visit early for an entity with a running growth or morph timer, so a ported block that sits after one of them is reachable here where the standard game would already have returned.
- **The dash branch and negative speeds** are implemented but unexercised here; the jump branch is exercised by the battle core's river jump. Nothing on the current paths produces a negative speed budget. Two pieces of a dash's contact hits are known to be incomplete rather than merely untested, and are documented on `TargetingVisit` itself: the dash pushback is applied to every entity in reach where the standard game skips one without a movement component, and the dash damage index is read from the first slot rather than from the dashing entity's own. No row the battle accepts has contact damage. The battle core drives the dash itself.
- **Six state-visit and state-setter timers have no writer.** The entity state setter carries its interrupt guard and the actions a plain ground unit reaches (the route emptied when the unit stops, prepared the moment it resumes, the target-lost timer cleared when it stops attacking, the deploy countdown cleared when it leaves deployment): the entry and exit actions that would seed the deploy countdown, the morph countdown, the two ability countdowns and the dash flags are not ported, and neither are the state visit's growth, spawner, ability and clone blocks. The blocks that read those timers are ported and correct but inert, and because the omitted blocks also carry early returns, a ported block's reachability is not exactly the standard game's either. The removal-request flag has the same shape: the only ported writer is the lifetime block, so a kamikaze unit that cannot run its removal block, or a spawner past its limit, never asks to be removed. The dash-landing block likewise announces the landing without doing the targeting work the standard game does (the battle core does it on the announcement), and the ability block does not remove the buff the cast leaves behind. None of this is reachable from a plain ground troop.
- **The movement gates are answered from live state, not from a per-tick snapshot.** `GridMovementQueries.speedInputs()` rebuilds the speed and gate inputs from the entity's current state, flags and charge progress every time one of them is asked for, while the standard game freezes them once before the visit. Inside a visit, a jump's state request writes the state and a completed charge the charge progress, but of these inputs only the budget reads the charge progress, and it is asked once, at the head of the visit; and every row that jumps walks under 250 a visit, so no gate is asked again after the jump starts in the same visit. The two agree today; a unit that jumps or charges at 250 or more a visit would make them differ.
- **The in-game pathfind end state.** Whether arriving at a mid-match destination ends in the deploy state or the moving state is a configuration column whose value is not established; it is answered as the moving state and is not reached on any current path.
- **Side-list compaction after a tower dies.** Assumed, not established; it affects default selection once a tower has been destroyed.
- **Tile map bits above 6.** Ignored, which means their meaning is not established. The bridge-centre and marker cells are not given a meaning.

## How to check

- `./gradlew :conformance:referenceTest` plays every recorded reference battle of the lock's data version on the battle core, the Knight walks among them, and compares every tick with the recording (see [conformance/README.md](../conformance/README.md)).
- The unit tests under `core/src/test/java/org/crforge/core/pathfinding` hold each rule on its own: the route search, its heap and the cost field (`grid`), the movement and push passes (`move`), the targeting visit and default selection (`target`), the state visit (`state`), and the hit points and damage chain (`combat`).
- The debug visualizer runs the battle core: its `G` and `N` keys paint the battle's routing cell costs and draw routes and references. See the controls table and the overlays section in [architecture.md](architecture.md#overlays).
