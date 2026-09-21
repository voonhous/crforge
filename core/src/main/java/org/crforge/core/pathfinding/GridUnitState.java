package org.crforge.core.pathfinding;

import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.SpeedConfig;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * Everything one troop needs to be driven by the grid movement and targeting rules.
 *
 * <p>The component is attached to a troop the first time the grid system sees it, and lives as long
 * as the troop does. It holds the troop's view of itself on the routing grid, the working state of
 * its two component passes, the countdowns the per-entity state visit owns, the configuration
 * columns those passes read, and the selection chain that answers "which target should I have now"
 * for this troop.
 *
 * <p>This is a plain mutable holder with no behaviour of its own; whatever runs the grid rules
 * drives it.
 *
 * @param entity The troop as the routing grid, the spatial index and the overlay see it.
 * @param movement The working state of the troop's movement component: its route, timers and
 *     accumulators.
 * @param targeting The working state of the troop's targeting component: its reference and attack
 *     timing.
 * @param timers The countdowns the per-entity state visit owns, such as the pending-damage
 *     duration.
 * @param movementConfig The troop's movement configuration columns.
 * @param speedConfig The troop's speed columns.
 * @param stateConfig The troop's state-visit configuration columns.
 * @param selection The chain that turns the spatial index and the tower list into one chosen
 *     target.
 * @param view The troop's own view, registered as a candidate of every other unit's query.
 */
public record GridUnitState(
    GridEntity entity,
    MovementState movement,
    TargetingState targeting,
    StateTimers timers,
    MovementConfig movementConfig,
    SpeedConfig speedConfig,
    StateVisitConfig stateConfig,
    SelectionChain selection,
    TargetView view) {}
