/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The geometry of a query along a segment, against answers the references' translation gives. */
class SegmentTestsTest {

  @Test
  @DisplayName(
      "a circle is within the width when the segment's nearest point, the projection clamped to"
          + " the ends, lies within the width and its radius")
  void aCircleNearTheSegment() {
    assertThat(SegmentTests.circleNearSegment(5000, 5000, 500, 0, 0, 10000, 10000, 2000)).isTrue();
    assertThat(SegmentTests.circleNearSegment(5000, 8000, 500, 0, 0, 10000, 10000, 2000)).isTrue();
    assertThat(SegmentTests.circleNearSegment(5000, 9000, 500, 0, 0, 10000, 10000, 2000)).isFalse();
    // Beyond either end the end itself is the nearest point; the reach counts as within.
    assertThat(SegmentTests.circleNearSegment(-3000, 0, 500, 0, 0, 10000, 0, 2000)).isFalse();
    assertThat(SegmentTests.circleNearSegment(12500, 0, 500, 0, 0, 10000, 0, 2000)).isTrue();
    assertThat(SegmentTests.circleNearSegment(12600, 0, 500, 0, 0, 10000, 0, 2000)).isFalse();
    // A segment of no length is its start.
    assertThat(SegmentTests.circleNearSegment(100, 100, 300, 500, 500, 500, 500, 0)).isFalse();
  }

  @Test
  @DisplayName("the squared reach is compared unsigned: one past the int range still reaches")
  void theReachIsComparedUnsigned() {
    assertThat(SegmentTests.circleNearSegment(0, 40000, 500, 0, 0, 10000, 0, 46500)).isTrue();
  }

  @Test
  @DisplayName(
      "a box is within the width when an end lies in it, or when the segment's point for its"
          + " centre, clamped to the far end past it, lies within the width of it")
  void aBoxNearTheSegment() {
    assertThat(SegmentTests.boxNearSegment(4000, 4000, 6000, 6000, 5000, 5000, 20000, 20000, 0))
        .isTrue();
    assertThat(SegmentTests.boxNearSegment(4000, 4000, 6000, 6000, 0, 0, 10000, 0, 4000)).isTrue();
    assertThat(SegmentTests.boxNearSegment(4000, 4000, 6000, 6000, 0, 0, 10000, 0, 3999)).isFalse();
    assertThat(SegmentTests.boxNearSegment(-1000, 3000, 1000, 5000, -10000, 0, 10000, 0, 3000))
        .isTrue();
    assertThat(SegmentTests.boxNearSegment(-1000, 3000, 1000, 5000, -10000, 0, 10000, 0, 2999))
        .isFalse();
    // The centre projects past the far end, which is kept: (1000, 0) is 5000 from the corner.
    assertThat(SegmentTests.boxNearSegment(4000, 4000, 6000, 6000, 0, 0, 1000, 0, 5000)).isTrue();
    assertThat(SegmentTests.boxNearSegment(4000, 4000, 6000, 6000, 0, 0, 1000, 0, 4999)).isFalse();
  }

  @Test
  @DisplayName("a building is tested by its square, anything else by its circle")
  void aBuildingByItsSquare() {
    GridEntity building = new GridEntity();
    building.setX(5000);
    building.setY(3000);
    building.setCollisionRadius(1000);
    building.setBuilding(true);
    // The square's corner is 2000 from the segment along y = 0; its circle would reach 2000 too.
    assertThat(SegmentTests.withinSegment(building, 0, 0, 10000, 0, 1999)).isFalse();
    assertThat(SegmentTests.withinSegment(building, 0, 0, 10000, 0, 2000)).isTrue();
    // Past the segment's end the square's near corner is 500 from it, the circle 1803 off.
    GridEntity beyond = new GridEntity();
    beyond.setX(5500);
    beyond.setY(1500);
    beyond.setCollisionRadius(1000);
    beyond.setBuilding(true);
    assertThat(SegmentTests.withinSegment(beyond, 0, 0, 4500, 0, 500)).isTrue();
    beyond.setBuilding(false);
    assertThat(SegmentTests.withinSegment(beyond, 0, 0, 4500, 0, 500)).isFalse();
    GridEntity unit = new GridEntity();
    unit.setX(5000);
    unit.setY(3000);
    unit.setCollisionRadius(1000);
    assertThat(SegmentTests.withinSegment(unit, 0, 0, 10000, 0, 1999)).isFalse();
    assertThat(SegmentTests.withinSegment(unit, 0, 0, 10000, 0, 2000)).isTrue();
  }

  @Test
  @DisplayName(
      "the nearest point is the projection in thousandths of the segment, truncated, the start"
          + " below it and the end past it")
  void theNearestPoint() {
    assertThat(SegmentTests.nearestPoint(3775, 18529, 3269, 22574, 3499, 22000))
        .containsExactly(3344, 21979);
    assertThat(SegmentTests.nearestPoint(0, 0, 1000, 0, -500, 7)).containsExactly(0, 0);
    assertThat(SegmentTests.nearestPoint(0, 0, 1000, 0, 1500, 7)).containsExactly(1000, 0);
    assertThat(SegmentTests.nearestPoint(0, 0, 1000, 0, 333, 7)).containsExactly(333, 0);
    assertThat(SegmentTests.nearestPoint(0, 0, 0, 0, 5, 5)).containsExactly(0, 0);
    assertThat(SegmentTests.nearestPoint(0, 0, 3, 7, 2, 2)).containsExactly(1, 2);
  }
}
