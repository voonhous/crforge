package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Moves a position by an offset and clamps it, one axis at a time, at the edge of the cell it
 * started in whenever the cell it would enter is off the grid or is water the entity may not cross.
 *
 * <p>The offset is applied first and the clamps are then considered independently for x and y, so a
 * diagonal step that is blocked on one axis still moves on the other. An axis clamps to {@code cell
 * * 500 + 499} when it was leaving the cell upwards in that axis and to {@code cell * 500} when it
 * was leaving it downwards.
 *
 * <p>Water blocks exactly one kind of entity: a character that is not air and is in the placing
 * state. An ordinary walking unit is not stopped by water here at all, which is why a unit can clip
 * the corner of a bridge cell. A character in the spawn-pathfinding state is instead governed by
 * its water permission, and everything else crosses freely.
 */
public final class GridMove {

  private GridMove() {
    // Utility class
  }

  /**
   * Applies an offset to a position and clamps it at the cell edges it may not cross.
   *
   * @param grid the arena's routing grid, which supplies its size and which cells hold water
   * @param position a two-element array {@code {x, y}} in game units, modified in place
   * @param dx offset along the arena's width, in game units
   * @param dy offset along the arena's length, in game units
   * @param entity the moving entity, or null when the step belongs to no entity and water never
   *     blocks it
   * @param flag 1 to force the water test on regardless of the entity's state
   */
  public static void gridMove(
      CellGrid grid, int[] position, int dx, int dy, GridMoveEntity entity, int flag) {
    int width = grid.getWidth();
    int height = grid.getHeight();
    int x = position[0];
    int y = position[1];
    int cellX = FixedMath.divOrZero(x, TileMap.CELL_UNITS);
    int cellY = FixedMath.divOrZero(y, TileMap.CELL_UNITS);
    int offsetX = x - cellX * TileMap.CELL_UNITS;
    int offsetY = y - cellY * TileMap.CELL_UNITS;
    position[0] = x + dx;
    position[1] = y + dy;

    int waterApplies = flag & 1;
    if (entity != null && entity.type() == GridMoveEntity.CHARACTER_TYPE && (flag & 1) == 0) {
      waterApplies = entity.state() == GridEntityState.DEPLOYING ? (entity.air() ? 0 : 1) : 0;
    }

    if (dx > 0 && offsetX + dx >= TileMap.CELL_UNITS) {
      int nextX = cellX + 1;
      if (nextX < 0
          || cellY < 0
          || width <= nextX
          || height <= cellY
          || tileBlocks(grid, entity, waterApplies, nextX, cellY)) {
        position[0] = cellX * TileMap.CELL_UNITS + TileMap.CELL_UNITS - 1;
      }
    } else if (dx < 0 && offsetX + dx < 0) {
      int nextX = cellX - 1;
      if (nextX < 0
          || cellY < 0
          || width < cellX
          || height <= cellY
          || tileBlocks(grid, entity, waterApplies, nextX, cellY)) {
        position[0] = cellX * TileMap.CELL_UNITS;
      }
    }

    if (dy > 0 && offsetY + dy >= TileMap.CELL_UNITS) {
      int nextY = cellY + 1;
      if (nextY < 0
          || cellX < 0
          || width <= cellX
          || height <= nextY
          || tileBlocks(grid, entity, waterApplies, cellX, nextY)) {
        position[1] = cellY * TileMap.CELL_UNITS + TileMap.CELL_UNITS - 1;
      }
    } else if (dy < 0 && offsetY + dy < 0) {
      int nextY = cellY - 1;
      if (nextY < 0
          || cellX < 0
          || width <= cellX
          || height < cellY
          || tileBlocks(grid, entity, waterApplies, cellX, nextY)) {
        position[1] = cellY * TileMap.CELL_UNITS;
      }
    }
  }

  /**
   * True when the cell the step would enter stops this entity. Only water is tested here; the
   * caller has already ruled out cells off the grid.
   */
  private static boolean tileBlocks(
      CellGrid grid, GridMoveEntity entity, int waterApplies, int col, int row) {
    if (entity != null
        && entity.type() == GridMoveEntity.CHARACTER_TYPE
        && entity.state() == GridEntityState.SPAWN_PATHFIND) {
      if (entity.waterPermission() || waterApplies == 0) {
        return false;
      }
      return (grid.water(col, row) & 1) != 0;
    }
    return waterApplies != 0 && (grid.water(col, row) & 1) != 0;
  }
}
