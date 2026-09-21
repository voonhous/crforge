package org.crforge.core.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.crforge.core.pathfinding.GridEntity;
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
 * <p>In {@link org.crforge.core.match.PathfindingMode#WAYPOINTS} matches no troop ever gets one, so
 * the field on the troop stays null and nothing here is allocated.
 *
 * <p>This is a plain mutable holder with no behaviour of its own; the grid system drives it.
 */
@Getter
@RequiredArgsConstructor
public class GridUnitState {

  /** The troop as the routing grid, the spatial index and the overlay see it. */
  private final GridEntity entity;

  /** The working state of the troop's movement component: its route, timers and accumulators. */
  private final MovementState movement;

  /** The working state of the troop's targeting component: its reference and attack timing. */
  private final TargetingState targeting;

  /** The countdowns the per-entity state visit owns, such as the pending-damage duration. */
  private final StateTimers timers;

  /** The troop's movement configuration columns. */
  private final MovementConfig movementConfig;

  /** The troop's speed columns. */
  private final SpeedConfig speedConfig;

  /** The troop's state-visit configuration columns. */
  private final StateVisitConfig stateConfig;

  /** The chain that turns the spatial index and the tower list into one chosen target. */
  private final SelectionChain selection;

  /** The troop's own view, registered as a candidate of every other unit's query. */
  private final TargetView view;
}
