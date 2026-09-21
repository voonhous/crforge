package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Recomputes a movement component's route direction: the vector from the entity to the centre of
 * the route's next waypoint, rescaled to length 256.
 *
 * <p>This runs whenever the next waypoint changes, which is when route preparation stores a route
 * and every time the follower consumes a node. The displacement then projects the remaining
 * distance onto this pair to decide whether the entity has arrived, so it has to be kept current: a
 * stale direction would make the arrival test answer about the wrong waypoint.
 *
 * <p>An empty route clears the pair rather than leaving the old one behind.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Agrees with the reference line for line; held by its own tests and by the"
            + " facing of every reference walk's first step.")
public final class DirectionInitializer {

  private DirectionInitializer() {
    // Utility class
  }

  /**
   * Writes the route direction pair from the route's next waypoint.
   *
   * @param component the movement component holding the route and receiving the direction
   * @param owner the entity the direction is measured from
   * @param width number of columns of the routing grid, which turns a node into a cell
   */
  public static void directionInit(MovementState component, GridEntity owner, int width) {
    Route route = component.getRoute();
    if (route.size() < 1) {
      component.setRouteDirX(0);
      component.setRouteDirY(0);
      return;
    }
    int node = route.last();
    int row = FixedMath.div(node, width);
    int col = node - row * width;
    int[] vector = {
      col * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2 - owner.getX(),
      row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2 - owner.getY()
    };
    FixedMath.normalize(vector, MovementState.DIRECTION_SCALE);
    component.setRouteDirX(vector[0]);
    component.setRouteDirY(vector[1]);
  }
}
