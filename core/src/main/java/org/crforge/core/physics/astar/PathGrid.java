package org.crforge.core.physics.astar;

import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
import org.crforge.core.entity.base.MovementType;

/**
 * 18x32 integer cost grid for A* pathfinding.
 *
 * <p>Built from the Arena tile types each time a path is computed. Static tile costs follow the
 * game's cost model from the pathfinding spec. Building and unit occlusions are overlaid
 * separately.
 */
public class PathGrid {

  private final int width;
  private final int height;
  private final int[] costs; // flat array: costs[y * width + x]

  public PathGrid(int width, int height) {
    this.width = width;
    this.height = height;
    this.costs = new int[width * height];
  }

  /** Creates a standard 18x32 grid. */
  public PathGrid() {
    this(Arena.WIDTH, Arena.HEIGHT);
  }

  /**
   * Builds static tile costs from the Arena's tile types.
   *
   * @param arena the game arena
   * @param moveType GROUND units treat river as blocked; AIR units treat it as WATER_COST
   */
  public void buildFromArena(Arena arena, MovementType moveType) {
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        TileType type = arena.getTile(x, y).type();
        costs[y * width + x] = tileCostFor(type, moveType);
      }
    }
  }

  /** Returns the cost at the given tile coordinates. Out-of-bounds returns BLOCKED_COST. */
  public int getCost(int x, int y) {
    if (!isInBounds(x, y)) {
      return CostTable.BLOCKED_COST;
    }
    return costs[y * width + x];
  }

  /** Sets the cost at the given tile coordinates. */
  public void setCost(int x, int y, int cost) {
    if (isInBounds(x, y)) {
      costs[y * width + x] = cost;
    }
  }

  /** Returns true if the tile is passable (cost less than BLOCKED_COST). */
  public boolean isPassable(int x, int y) {
    return getCost(x, y) < CostTable.BLOCKED_COST;
  }

  public boolean isInBounds(int x, int y) {
    return x >= 0 && x < width && y >= 0 && y < height;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  private static int tileCostFor(TileType type, MovementType moveType) {
    return switch (type) {
      case GROUND, BLUE_ZONE, RED_ZONE -> CostTable.DEFAULT_COST;
      case BRIDGE -> CostTable.ROAD_COST;
      case RIVER -> moveType == MovementType.AIR ? CostTable.WATER_COST : CostTable.BLOCKED_COST;
      case TOWER, BANNED -> CostTable.BLOCKED_COST;
    };
  }
}
