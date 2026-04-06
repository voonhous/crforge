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
  private final Map<Long, Integer> stepCounters = new HashMap<>();

  /**
   * Returns the cached path if it is still valid for the given target position and arena state.
   * Also enforces the forced re-path after MAX_STEPS ticks of following the same path.
   *
   * @param currentBuildingCount current number of alive buildings (for staleness detection)
   */
  public CachedPath get(
      long entityId, int currentTargetX, int currentTargetY, int currentBuildingCount) {
    CachedPath cached = cache.get(entityId);
    if (cached == null) {
      return null;
    }
    // Forced re-path after MAX_STEPS ticks on the same path (per spec: step counter = 1000)
    int steps = stepCounters.getOrDefault(entityId, 0);
    if (steps >= CostTable.MAX_STEPS) {
      return null;
    }
    if (!cached.isValidFor(currentTargetX, currentTargetY, currentBuildingCount)) {
      return null;
    }
    return cached;
  }

  /** Stores a computed path for the given entity and resets its waypoint index and step counter. */
  public void put(long entityId, CachedPath path) {
    cache.put(entityId, path);
    waypointIndices.put(entityId, 0);
    stepCounters.put(entityId, 0);
  }

  /** Increments the step counter for the given entity (called once per tick while following). */
  public void incrementSteps(long entityId) {
    stepCounters.merge(entityId, 1, Integer::sum);
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
    stepCounters.remove(entityId);
  }

  /** Clears all cached paths (e.g. on match reset). */
  public void clear() {
    cache.clear();
    waypointIndices.clear();
    stepCounters.clear();
  }

  /** Returns the number of cached paths (for diagnostics). */
  public int size() {
    return cache.size();
  }
}
