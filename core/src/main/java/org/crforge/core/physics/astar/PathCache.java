package org.crforge.core.physics.astar;

import java.util.HashMap;
import java.util.Map;

/**
 * Entity-keyed path cache with epsilon-based invalidation.
 *
 * <p>Cached paths are reused when the target has not moved more than {@link
 * CostTable#SAMEPATH_EPSILON} tiles from where the path was originally computed. This avoids
 * re-running A* every tick while a unit chases a stationary or slow-moving target.
 */
public class PathCache {

  private final Map<Long, CachedPath> cache = new HashMap<>();
  private final Map<Long, Integer> waypointIndices = new HashMap<>();

  /**
   * Returns the cached path for the given entity if the target is still within epsilon of the
   * cached target. Returns null if no valid cached path exists.
   */
  /**
   * Returns the cached path if it is still valid for the given target position and arena state.
   *
   * @param currentBuildingCount current number of alive buildings (for staleness detection)
   */
  public CachedPath get(
      long entityId, int currentTargetX, int currentTargetY, int currentBuildingCount) {
    CachedPath cached = cache.get(entityId);
    if (cached != null && cached.isValidFor(currentTargetX, currentTargetY, currentBuildingCount)) {
      return cached;
    }
    return null;
  }

  /** Stores a computed path for the given entity and resets its waypoint index to 0. */
  public void put(long entityId, CachedPath path) {
    cache.put(entityId, path);
    waypointIndices.put(entityId, 0);
  }

  /** Returns the current waypoint index for the given entity (defaults to 0). */
  public int getWaypointIndex(long entityId) {
    return waypointIndices.getOrDefault(entityId, 0);
  }

  /** Updates the waypoint index for the given entity. */
  public void setWaypointIndex(long entityId, int index) {
    waypointIndices.put(entityId, index);
  }

  /** Removes the cached path for a specific entity. */
  public void invalidate(long entityId) {
    cache.remove(entityId);
    waypointIndices.remove(entityId);
  }

  /** Clears all cached paths (e.g. on match reset). */
  public void clear() {
    cache.clear();
    waypointIndices.clear();
  }

  /** Returns the number of cached paths (for diagnostics). */
  public int size() {
    return cache.size();
  }
}
