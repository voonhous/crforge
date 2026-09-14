# Troop pathfinding

The simulator currently uses hand-authored bridge waypoints. This document specifies the grid-based planner intended to replace them. It describes the target design, not the behavior of the current simulator, and full troop trajectories are not yet verified end to end.

## Movement states

Movement without a nearby enemy, movement without combat lock, and movement with no target reference are distinct cases and are handled separately. Default movement can carry a destination target reference. A change in direction after acquiring an enemy does not by itself imply a separate pursuit planner, so the design does not assume one.

Default destination selection, lane initialization, combat-lock transitions and target-loss behavior are not yet specified.

## Grid and routes

Path cells span 500 game units. Ordinary cell-center waypoints use `(500 * column + 250, 500 * row + 250)`. Position-to-cell conversion truncates division toward zero.

Routes use row-major cell IDs and are stored destination-first. Following consumes the last node. An empty route uses the current position as its waypoint. Special movement states can override ordinary waypoint selection.

## Search behavior

The search uses eight neighbors in this order: up, down, left, right, upper-left, lower-left, lower-right, upper-right. Cardinal steps multiply destination-cell cost by 10; diagonal steps multiply it by 14.

The search does not itself require adjacent cardinal cells to be traversable before considering a diagonal. Whether terrain preparation should impose an equivalent restriction is still open.

For absolute cell differences `dx` and `dy`, heuristic method 0 uses `10 * max(dx, dy)`. Methods 1 and 2 use `10 * max(dx, dy) + 4 * min(dx, dy)`. Priority adds the weighted heuristic to the accumulated movement cost. An alternative mode instead carries the previous priority, accumulating heuristic contributions along the route. Which mode the simulator should use is not yet decided.

Open-node refresh and closed-node reopening have separate flags and require a strictly lower priority. Heap insertion preserves equal-priority order locally by avoiding equal swaps. During removal, equal lower children favor the right child. A generic priority queue can therefore produce different routes even at identical costs, so this heap behavior is part of the specification rather than an implementation detail.

The start is expanded before it is marked closed. A popped goal is expanded before the termination check. A positive search budget counts popped-node expansions; a nonpositive budget is unlimited.

Returned routes normally exclude the start. If the initial expansion leaves no open nodes, the result retains the start alone. Budget exhaustion can return a reconstructed route to a goal that has a parent but has not been closed. Callers must handle both cases explicitly.

## Cell costs

Cost evaluation distinguishes water permission, blocked cells, default terrain, roads, matching roads and dynamic overlays. A positive blocked-cell cost is a penalty, not automatic rejection. Out-of-bounds cells are rejected.

Some terrain and movement-state branches return their cost before dynamic overlays are applied. In the ordinary terrain branch, an active overlay uses the maximum of base cost and overlay cost. Road preference can depend on whether the road ID matches the unit's lane value.

Arena flags, cost values, building footprints and dynamic overlay generation are not yet specified. These rules alone do not predict which bridge a troop will choose.

## Test coverage

The search passes 733 cases covering returned routes, node states, parents, carried costs, priorities, heap order, counters and remaining budget. The cost function passes 98,308 cases over synthetic terrain and supplied entity properties.

Search coverage spans heuristic methods 0 through 2, positive synthetic costs from 1 through 100 or rejection, and bounded arithmetic. Method 3 and overflow behavior are not covered. The cost tests do not exercise real arena maps or obstacle generation. Neither set establishes end-to-end trajectory agreement.

## Remaining integration requirements

- Specify default destinations, lane initialization and combat-lock transitions.
- Settle the configuration and the terrain and dynamic cost maps.
- Verify endpoint adjustment, cached-route retention and waypoint acceptance.
- Verify movement rounding, collision, pushing and recovery after displacement.
- Settle the update cadence and compare complete trajectories, including bridge ties, flying units and target loss.

Until these are met, replacing the simulator's bridge rules would require assumptions beyond the planner behavior the tests cover.
