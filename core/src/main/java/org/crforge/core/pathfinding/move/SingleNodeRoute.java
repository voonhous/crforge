package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * Replaces an entity's route with the single cell of one point, as a jump over the river and a dash
 * each set their destination.
 *
 * <p>The cell is the point's, clamped into the grid: a coordinate at or below 499 falls in cell 0,
 * and one past the grid in its last cell. The route direction is then pointed at that cell's
 * centre, and the dash's stop-in-range byte is set from the caller's flag, except for a unit with a
 * jump height, whose dash ends only by its time.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line; held by the river jumps of the reference"
            + " battles card_HogRider and grid_zap_on_hog_river_jump. A fixed dash distance,"
            + " which turns the stop-in-range byte off, is set by no row and not carried.")
public final class SingleNodeRoute {

  private SingleNodeRoute() {
    // Utility class
  }

  /**
   * Sets the route to the one cell of a point.
   *
   * @param component the entity's movement component, whose route, direction and stop-in-range byte
   *     this writes
   * @param owner the entity, whose position the direction is taken from
   * @param config the entity's movement columns, of which the jump height is read
   * @param x the point along the arena's width, in game units
   * @param y the point along the arena's length, in game units
   * @param stopsInRange 1 when a dash toward the point may stop as its reference comes into range
   * @param width the grid's width in cells
   * @param height the grid's height in cells
   */
  public static void set(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      int x,
      int y,
      int stopsInRange,
      int width,
      int height) {
    int column = clampedCell(x, width);
    int row = clampedCell(y, height);
    component.setRoute(Route.of(row * width + column));
    DirectionInitializer.directionInit(component, owner, width);
    // With no fixed dash distance, which no row sets, the flag decides.
    component.setDashStopsInRange(config.jumpHeight() != 0 ? 0 : stopsInRange & 1);
  }

  /**
   * One axis of the clamped cell: the unsigned quotient by the cell size, capped at the last cell
   * with a signed comparison, and cell 0 for a coordinate at or below 499.
   */
  private static int clampedCell(int coordinate, int size) {
    int cell = (int) (Integer.toUnsignedLong(coordinate) / TileMap.CELL_UNITS);
    cell = Math.min(cell, size - 1);
    return coordinate > TileMap.CELL_UNITS - 1 ? cell : 0;
  }
}
