package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Turns the route's next waypoint into the world point a displacement aims at.
 *
 * <p>The ordinary answer is the centre of the cell named by the route's last node. Two cases
 * differ: a flying unit configured to walk direct paths heads for a point near its reference
 * instead of following a route at all, and a unit with an empty route aims at its own position,
 * which makes the displacement a no-op that still reports arrival.
 *
 * <p>While a touchdown mode restricts a defender to its own side of the arena, a moving unit with
 * no reference keeps its own x and only walks along the arena's length.
 *
 * <p>The answer is returned as a fresh {@code {x, y}} pair; the callers that need it kept decide
 * for themselves whether to copy it into the component's work vector.
 */
public final class WaypointSelector {

  private WaypointSelector() {
    // Utility class
  }

  /**
   * Returns the world point the next displacement should aim at, as {@code {x, y}} in game units.
   *
   * @param component the movement component holding the route
   * @param owner the entity being moved
   * @param config the entity's movement configuration columns
   * @param globals the match-wide movement settings
   * @param queries the answers the selector pulls from the rest of the simulation
   * @param chain the chain that records what the selector announced
   */
  public static int[] selectWaypoint(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementGlobals globals,
      MovementQueries queries,
      MovementChain chain) {
    if (config.flyingHeight() > 0 && config.flyDirectPaths()) {
      chain.mark("reference_available");
      if (queries.referenceAvailable() != 0) {
        chain.mark("special_waypoint");
        int[] supplied = queries.specialWaypoint();
        return new int[] {supplied[0], supplied[1]};
      }
    }
    Route route = component.getRoute();
    if (route.isEmpty()) {
      return new int[] {owner.getX(), owner.getY()};
    }
    int node = route.last();
    int width = globals.width();
    int row = FixedMath.divOrZero(node, width);
    int col = node - row * width;
    chain.mark("reference_available");
    int x = col * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
    if (queries.referenceAvailable() == 0 && globals.touchdownRestrictedSideMovement()) {
      chain.mark("touchdown_mode");
      if ((queries.touchdownModeActive() & 1) != 0 && owner.getState() == GridEntityState.MOVING) {
        x = owner.getX();
      }
    }
    return new int[] {x, row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2};
  }
}
