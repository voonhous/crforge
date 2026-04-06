package org.crforge.core.physics.astar;

import java.util.List;

/**
 * A cached A* path result with metadata for invalidation.
 *
 * <p>The path is invalidated when the target moves more than {@link CostTable#SAMEPATH_EPSILON}
 * tiles from the cached target position.
 */
public record CachedPath(
    List<int[]> waypoints,
    int targetTileX,
    int targetTileY,
    int startTileX,
    int startTileY,
    int buildingCount) {

  /**
   * Returns true if this cached path is still valid for the given target position and arena state.
   *
   * <p>Invalidated when: (a) target moves more than SAMEPATH_EPSILON tiles, or (b) the number of
   * alive buildings has changed (building placed, destroyed, or expired).
   */
  public boolean isValidFor(int currentTargetX, int currentTargetY, int currentBuildingCount) {
    if (currentBuildingCount != buildingCount) {
      return false;
    }
    int dx = Math.abs(currentTargetX - targetTileX);
    int dy = Math.abs(currentTargetY - targetTileY);
    return dx <= CostTable.SAMEPATH_EPSILON && dy <= CostTable.SAMEPATH_EPSILON;
  }
}
