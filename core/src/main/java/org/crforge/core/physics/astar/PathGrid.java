package org.crforge.core.physics.astar;

import java.util.Collection;
import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

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
    buildFromArena(arena, moveType, false, false);
  }

  /**
   * Builds static tile costs with optional lane preference.
   *
   * <p>When lane preference is enabled (per spec: LOGIC_XPOS_BASED_TOWER_TARGETING = TRUE), bridge
   * tiles on the entity's preferred side get MATCHING_ROAD_COST instead of ROAD_COST. Since both
   * values happen to be 5 in the current game data, this has no cost difference yet, but the
   * mechanism is in place for potential future tuning.
   *
   * @param arena the game arena
   * @param moveType GROUND units treat river as blocked; AIR units treat it as WATER_COST
   * @param lanePreference whether to apply lane-based cost preference
   * @param preferLeftLane true if the entity prefers the left lane (deployed on left side)
   */
  public void buildFromArena(
      Arena arena, MovementType moveType, boolean lanePreference, boolean preferLeftLane) {
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        TileType type = arena.getTile(x, y).type();
        int cost = tileCostFor(type, moveType);

        // Apply lane preference: matching-lane bridge gets MATCHING_ROAD_COST
        if (lanePreference && type == TileType.BRIDGE) {
          boolean isLeftBridge = x < arena.WIDTH / 2;
          if (isLeftBridge == preferLeftLane) {
            cost = CostTable.MATCHING_ROAD_COST;
          }
        }
        costs[y * width + x] = cost;
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

  /**
   * Overlays building collision radii onto the cost grid.
   *
   * <p>Player-placed buildings (Inferno Tower, Tesla, Cannon, etc.) are not part of the static
   * Arena tile grid, so they need to be overlaid dynamically. Towers are already baked into the
   * Arena as TileType.TOWER, but this is idempotent for them (same cost).
   *
   * <p>Each building's collision radius determines which tiles it blocks. All tiles whose centers
   * fall within the radius are set to BUILDING_COST.
   */
  public void applyBuildingOcclusions(Collection<Entity> entities) {
    for (Entity entity : entities) {
      if (!(entity instanceof Building building) || !building.isAlive()) {
        continue;
      }
      float cx = building.getPosition().getX();
      float cy = building.getPosition().getY();
      float radius = building.getCollisionRadius();
      if (radius <= 0f) {
        continue;
      }

      // Scan tiles in the bounding box of the collision circle
      int minX = Math.max(0, (int) (cx - radius));
      int maxX = Math.min(width - 1, (int) (cx + radius));
      int minY = Math.max(0, (int) (cy - radius));
      int maxY = Math.min(height - 1, (int) (cy + radius));

      float radiusSq = radius * radius;
      for (int ty = minY; ty <= maxY; ty++) {
        for (int tx = minX; tx <= maxX; tx++) {
          // Tile center is at (tx + 0.5, ty + 0.5)
          float dx = (tx + 0.5f) - cx;
          float dy = (ty + 0.5f) - cy;
          if (dx * dx + dy * dy <= radiusSq) {
            setCost(tx, ty, CostTable.BUILDING_COST);
          }
        }
      }
    }
  }

  /**
   * Overlays friendly troop occlusions onto the cost grid.
   *
   * <p>Per the game spec (PATHFINDING_FRIENDLYONLY_OCCLUSIONS = TRUE), only units on the SAME team
   * as the pathfinding entity increase tile cost. Enemy units do not block pathing.
   *
   * <p>Occluded tiles get an elevated cost (BUILDING_COST) rather than being fully blocked, so A*
   * will still route through a friendly cluster if no better path exists, but will prefer going
   * around.
   *
   * @param entities all alive entities
   * @param selfTeam the team of the entity being pathfound
   * @param selfId the entity's own ID (excluded from occlusion)
   */
  public void applyFriendlyOcclusions(Collection<Entity> entities, Team selfTeam, long selfId) {
    for (Entity entity : entities) {
      if (!(entity instanceof Troop troop) || !troop.isAlive()) {
        continue;
      }
      if (troop.getTeam() != selfTeam || troop.getId() == selfId) {
        continue;
      }
      // Mark the tile the troop occupies
      int tx = (int) troop.getPosition().getX();
      int ty = (int) troop.getPosition().getY();
      if (isInBounds(tx, ty) && costs[ty * width + tx] < CostTable.BUILDING_COST) {
        costs[ty * width + tx] = CostTable.BUILDING_COST;
      }
    }
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
