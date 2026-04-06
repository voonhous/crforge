package org.crforge.core.physics.astar;

import java.util.List;
import org.crforge.core.component.Position;

/**
 * Converts an A* waypoint chain into per-tick movement angles.
 *
 * <p>Each waypoint is a tile-center coordinate. The follower advances past waypoints that the
 * entity has already reached (within a threshold), then returns the angle toward the next waypoint.
 */
public final class WaypointFollower {

  /** Distance (in tiles) at which we consider a waypoint "reached" and advance to the next. */
  private static final float WAYPOINT_REACH_THRESHOLD = 0.5f;

  private WaypointFollower() {}

  /**
   * Advances the waypoint index past any waypoints the entity has already reached.
   *
   * @return the updated waypoint index (capped at waypoints.size() - 1)
   */
  public static int advanceWaypoints(Position currentPos, List<int[]> waypoints, int currentIndex) {
    if (waypoints.isEmpty()) {
      return 0;
    }
    int idx = currentIndex;
    int lastIdx = waypoints.size() - 1;
    float posX = currentPos.getX();
    float posY = currentPos.getY();

    while (idx < lastIdx) {
      float distSqCurrent = distSqToWaypoint(posX, posY, waypoints.get(idx));

      // Advance if we are within the reach threshold of the current waypoint
      if (distSqCurrent <= WAYPOINT_REACH_THRESHOLD * WAYPOINT_REACH_THRESHOLD) {
        idx++;
        continue;
      }

      // Also advance if the NEXT waypoint is closer than the current one
      // (handles the case where we've already passed the current waypoint)
      float distSqNext = distSqToWaypoint(posX, posY, waypoints.get(idx + 1));
      if (distSqNext < distSqCurrent) {
        idx++;
      } else {
        break;
      }
    }
    return idx;
  }

  private static float distSqToWaypoint(float posX, float posY, int[] wp) {
    float wpX = wp[0] + 0.5f;
    float wpY = wp[1] + 0.5f;
    float dx = posX - wpX;
    float dy = posY - wpY;
    return dx * dx + dy * dy;
  }

  /**
   * Returns the movement angle (radians) toward the current waypoint.
   *
   * @param currentPos entity's current sub-tile position
   * @param waypoints the waypoint chain from A*
   * @param waypointIndex the index of the waypoint currently being navigated toward
   * @return angle in radians
   */
  public static float getAngleToNextWaypoint(
      Position currentPos, List<int[]> waypoints, int waypointIndex) {
    if (waypoints.isEmpty()) {
      return 0f;
    }
    int idx = Math.min(waypointIndex, waypoints.size() - 1);
    int[] wp = waypoints.get(idx);
    float wpX = wp[0] + 0.5f;
    float wpY = wp[1] + 0.5f;
    return (float) Math.atan2(wpY - currentPos.getY(), wpX - currentPos.getX());
  }
}
