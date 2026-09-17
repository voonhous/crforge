package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Whether a route leads past the entity the unit is heading for.
 *
 * <p>The answer is 1 when some node of the route sits strictly farther from the <b>reference</b>
 * than the entity itself does. Every node is measured from the reference, not from the entity, and
 * the baseline is the entity-to-reference distance; the scan runs from the route's next waypoint
 * back to its goal and stops at the first node that is farther.
 *
 * <p>A route of fewer than two nodes always answers 0.
 *
 * <p>Every vector it measures is written into the movement component's scratch pair, so the pair
 * ends holding the last one measured. That is a side effect the routine's callers see, not an
 * accident.
 */
public final class RouteBeyondReference {

  private RouteBeyondReference() {
    // Utility class
  }

  /**
   * Answers 1 when some route node lies farther from the reference than the entity does, 0
   * otherwise.
   *
   * @param component the movement component holding the route and receiving the scratch vector
   * @param owner the entity the baseline distance is measured from
   * @param reference the position every node is measured from
   * @param width number of columns of the routing grid, which turns a node into a cell
   */
  public static int routeBeyondReference(
      MovementState component, GridEntity owner, ReferencePoint reference, int width) {
    Route route = component.getRoute();
    if (route.size() < 2) {
      return 0;
    }
    component.setScratch(reference.x() - owner.getX(), reference.y() - owner.getY());
    int baseline = FixedMath.guardedDistance(component.getScratch()[0], component.getScratch()[1]);
    for (int index = route.size() - 1; index >= 0; index--) {
      int node = route.get(index);
      int row = FixedMath.divOrZero(node, width);
      int col = node - row * width;
      component.setScratch(
          col * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2 - reference.x(),
          row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2 - reference.y());
      if (FixedMath.guardedDistance(component.getScratch()[0], component.getScratch()[1])
          > baseline) {
        return 1;
      }
    }
    return 0;
  }
}
