package org.crforge.core.physics.astar;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.physics.Pathfinder;

/**
 * Game-accurate A* pathfinder implementing the {@link Pathfinder} interface.
 *
 * <p>Uses tile-grid A* with cost parameters from the reverse-engineered spec (see
 * scratch/pathfinding/PATHFINDING_SPEC.md). Caches paths per entity and only re-runs A* when the
 * target moves more than {@link CostTable#SAMEPATH_EPSILON} tiles.
 *
 * <p>This pathfinder coexists with {@link org.crforge.core.physics.BasePathfinder} and can be
 * swapped in via {@link org.crforge.core.physics.PhysicsSystem}'s constructor injection.
 *
 * <p>Air units bypass A* entirely and move in a straight line (same as BasePathfinder). Ground
 * units get full A* routing with bridge preference and building avoidance.
 */
public class AStarPathfinder implements Pathfinder {

  private final GameState gameState;
  private final PathCache pathCache;
  private final AStarSearch search;
  private final PathGrid grid;

  public AStarPathfinder(GameState gameState) {
    this.gameState = gameState;
    this.pathCache = new PathCache();
    this.search = new AStarSearch();
    this.grid = new PathGrid();
  }

  /** Stateless fallback for callers that don't provide an Entity. Uses straight-line movement. */
  @Override
  public float getNextMovementAngle(
      Position startPos, MovementType moveType, float targetX, float targetY, Arena arena) {
    // Without entity identity, we can't cache. Fall back to straight line.
    return (float) Math.atan2(targetY - startPos.getY(), targetX - startPos.getX());
  }

  /** Full A* pathfinding with per-entity caching. */
  @Override
  public float getNextMovementAngle(
      Position startPos,
      MovementType moveType,
      float targetX,
      float targetY,
      Arena arena,
      Entity entity) {

    // Air units fly straight (no grid pathfinding needed)
    if (moveType == MovementType.AIR) {
      return (float) Math.atan2(targetY - startPos.getY(), targetX - startPos.getX());
    }

    int startTileX = toTile(startPos.getX(), arena.WIDTH);
    int startTileY = toTile(startPos.getY(), arena.HEIGHT);
    int goalTileX = toTile(targetX, arena.WIDTH);
    int goalTileY = toTile(targetY, arena.HEIGHT);

    long entityId = entity.getId();

    // Check path cache
    CachedPath cached = pathCache.get(entityId, goalTileX, goalTileY);
    if (cached == null) {
      // Build cost grid from current arena state
      grid.buildFromArena(arena, moveType);

      // Run A*
      List<int[]> waypoints = search.findPath(grid, startTileX, startTileY, goalTileX, goalTileY);

      if (waypoints.isEmpty()) {
        // No path found -- fall back to straight line
        return (float) Math.atan2(targetY - startPos.getY(), targetX - startPos.getX());
      }

      cached = new CachedPath(waypoints, goalTileX, goalTileY, startTileX, startTileY);
      pathCache.put(entityId, cached);
    }

    // Follow waypoints
    int wpIndex = pathCache.getWaypointIndex(entityId);
    wpIndex = WaypointFollower.advanceWaypoints(startPos, cached.waypoints(), wpIndex);
    pathCache.setWaypointIndex(entityId, wpIndex);

    return WaypointFollower.getAngleToNextWaypoint(startPos, cached.waypoints(), wpIndex);
  }

  /** Clears all cached paths (call on match reset). */
  public void clearCache() {
    pathCache.clear();
  }

  private static int toTile(float worldCoord, int gridSize) {
    return Math.max(0, Math.min((int) worldCoord, gridSize - 1));
  }
}
