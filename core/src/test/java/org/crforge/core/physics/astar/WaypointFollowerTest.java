package org.crforge.core.physics.astar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.crforge.core.component.Position;
import org.junit.jupiter.api.Test;

class WaypointFollowerTest {

  @Test
  void singleWaypoint_returnsDirectAngle() {
    Position pos = new Position(0f, 0f);
    List<int[]> waypoints = List.of(new int[] {5, 0});

    float angle = WaypointFollower.getAngleToNextWaypoint(pos, waypoints, 0);

    // Angle toward (5.5, 0.5) from (0, 0)
    float expected = (float) Math.atan2(0.5f, 5.5f);
    assertThat(angle).isCloseTo(expected, within(0.01f));
  }

  @Test
  void advanceWaypoints_skipsReachedWaypoints() {
    // Entity is at (2.5, 0.5) which is the center of tile (2, 0)
    Position pos = new Position(2.5f, 0.5f);
    List<int[]> waypoints =
        List.of(new int[] {0, 0}, new int[] {1, 0}, new int[] {2, 0}, new int[] {3, 0});

    int idx = WaypointFollower.advanceWaypoints(pos, waypoints, 0);

    // Should advance past waypoints 0, 1, 2 (all reached) and point at 3
    assertThat(idx).isEqualTo(3);
  }

  @Test
  void advanceWaypoints_stopsAtLastWaypoint() {
    Position pos = new Position(3.5f, 0.5f);
    List<int[]> waypoints = List.of(new int[] {0, 0}, new int[] {1, 0}, new int[] {3, 0});

    int idx = WaypointFollower.advanceWaypoints(pos, waypoints, 0);

    // Last waypoint (3, 0) is reached but index shouldn't go past it
    assertThat(idx).isEqualTo(2); // last valid index
  }

  @Test
  void advanceWaypoints_doesNotAdvancePastUnreachedWaypoint() {
    Position pos = new Position(0.5f, 0.5f);
    List<int[]> waypoints = List.of(new int[] {0, 0}, new int[] {5, 0});

    int idx = WaypointFollower.advanceWaypoints(pos, waypoints, 0);

    // First waypoint reached, but second is far away
    assertThat(idx).isEqualTo(1);
  }

  @Test
  void emptyWaypoints_returnsZeroAngle() {
    Position pos = new Position(5f, 5f);
    List<int[]> waypoints = List.of();

    float angle = WaypointFollower.getAngleToNextWaypoint(pos, waypoints, 0);
    assertThat(angle).isEqualTo(0f);

    int idx = WaypointFollower.advanceWaypoints(pos, waypoints, 0);
    assertThat(idx).isEqualTo(0);
  }

  @Test
  void multiWaypoint_anglePointsToCurrentWaypoint() {
    Position pos = new Position(0f, 0f);
    List<int[]> waypoints = List.of(new int[] {0, 3}, new int[] {3, 3}, new int[] {3, 0});

    // At waypoint index 1, should point toward (3, 3) -> center (3.5, 3.5)
    float angle = WaypointFollower.getAngleToNextWaypoint(pos, waypoints, 1);
    float expected = (float) Math.atan2(3.5f, 3.5f);
    assertThat(angle).isCloseTo(expected, within(0.01f));
  }
}
