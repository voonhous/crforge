package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.grid.Route;

/**
 * Whether a freshly searched route has to replace the one a unit already holds.
 *
 * <p>Replanning is cheap to run and expensive to act on: a unit that swaps its route every tick
 * wanders. So after a search the old route is kept unless the cost overlay changed in a way that
 * touches one of the two routes, which is exactly two cases:
 *
 * <ul>
 *   <li>the old route now crosses a cell that was free and has become occupied, or
 *   <li>the new route uses a cell that was occupied and has become free.
 * </ul>
 *
 * <p>An empty route on either side always counts as a change, so a failed search discards the route
 * the unit was holding rather than letting it walk on.
 *
 * <p>Node ids outside either overlay are ignored rather than treated as a change.
 */
public final class RouteRetention {

  private RouteRetention() {
    // Utility class
  }

  /**
   * True when the new route must replace the old one.
   *
   * @param current the cost overlay as it stands after this tick's build, row-major
   * @param previous the cost overlay as it stood after the previous build, row-major
   * @param oldRoute the route the unit was holding
   * @param newRoute the route the search just produced
   */
  public static boolean overlayChangeAffectsRoutes(
      int[] current, int[] previous, Route oldRoute, Route newRoute) {
    if (oldRoute.isEmpty() || newRoute.isEmpty()) {
      return true;
    }
    for (int index = 0; index < oldRoute.size(); index++) {
      int node = oldRoute.get(index);
      if (node < 0 || node >= previous.length || node >= current.length) {
        continue;
      }
      if (previous[node] != 0) {
        continue;
      }
      if (current[node] > 0) {
        return true;
      }
    }
    for (int index = 0; index < newRoute.size(); index++) {
      int node = newRoute.get(index);
      if (node < 0 || node >= previous.length || node >= current.length) {
        continue;
      }
      if (previous[node] < 1 || current[node] != 0) {
        continue;
      }
      return true;
    }
    return false;
  }
}
