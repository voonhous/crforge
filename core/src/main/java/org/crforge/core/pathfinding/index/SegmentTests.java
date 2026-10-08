package org.crforge.core.pathfinding.index;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;

/**
 * The geometry of a query along a segment: whether an entity lies within a width of the segment,
 * and the segment's point nearest a point.
 *
 * <p>Every value is a 32-bit int as the game keeps it: the differences, the dot products and the
 * squares wrap, the squared lengths and distances are compared as unsigned words, and the divisions
 * are 64-bit and truncate toward zero.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: a building's square against the segment widened by the width,"
            + " anything else its circle within the width and its radius of the segment, its ends"
            + " clamped, and the nearest point in thousandths of the segment; held by the"
            + " reference battle ability_goblinstein and by unit tests of each case.")
public final class SegmentTests {

  /** The segment's length is cut into this many parts when a point is projected onto it. */
  private static final int THOUSANDTHS = 1000;

  private SegmentTests() {
    // Utility class
  }

  /**
   * Whether the entity lies within a width of the segment: a building by its square, the centre
   * plus and minus its collision radius each way, anything else by its circle.
   */
  public static boolean withinSegment(
      GridEntity entity, int ax, int ay, int bx, int by, int width) {
    int x = entity.getX();
    int y = entity.getY();
    int r = entity.getCollisionRadius();
    if (entity.isBuilding()) {
      return boxNearSegment(x - r, y - r, x + r, y + r, ax, ay, bx, by, width);
    }
    return circleNearSegment(x, y, r, ax, ay, bx, by, width);
  }

  /**
   * Whether a circle comes within a width of the segment: the segment's point nearest the centre,
   * the projection clamped to the segment, against the width plus the radius, both squared.
   */
  static boolean circleNearSegment(
      int cx, int cy, int r, int ax, int ay, int bx, int by, int width) {
    int dx = bx - ax;
    int dy = by - ay;
    int lengthSquared = dx * dx + dy * dy;
    int nx = ax;
    int ny = ay;
    if (lengthSquared != 0) {
      int t = dx * (cx - ax) + dy * (cy - ay);
      // A signed clamp to the squared length, then to 0.
      if (t >= lengthSquared) {
        t = lengthSquared;
      }
      t = Math.max(t, 0);
      long length = Integer.toUnsignedLong(lengthSquared);
      nx = (int) ((long) t * dx / length) + ax;
      ny = (int) ((long) t * dy / length) + ay;
    }
    int distanceSquared = (cy - ny) * (cy - ny) + (cx - nx) * (cx - nx);
    int reach = (width + r) * (width + r);
    return Integer.compareUnsigned(distanceSquared, reach) <= 0;
  }

  /**
   * Whether a box comes within a width of the segment: true when an end lies in the box; else the
   * segment's point for the box's centre, its projection in thousandths clamped to an end when it
   * falls outside, is clamped into the box and the squared distance compared with the width
   * squared.
   */
  static boolean boxNearSegment(
      int left, int bottom, int right, int top, int ax, int ay, int bx, int by, int width) {
    if (!(ay > top) && !(ay < bottom) && !(ax < left) && !(ax > right)) {
      return true;
    }
    if (!(by > top) && !(by < bottom) && !(bx < left) && !(bx > right)) {
      return true;
    }
    int dx = bx - ax;
    int dy = by - ay;
    int lengthSquared = dx * dx + dy * dy;
    // Past the far end the far end is kept.
    int px = bx;
    int py = by;
    if (lengthSquared == 0) {
      px = ax;
      py = ay;
    } else {
      int mx = (right + left) / 2;
      int my = (top + bottom) / 2;
      int dot = dx * (mx - ax) + dy * (my - ay);
      long s = (long) dot * THOUSANDTHS / Integer.toUnsignedLong(lengthSquared);
      if (s < 0) {
        px = ax;
        py = ay;
      } else if (s <= THOUSANDTHS) {
        px = (int) (s * dx / THOUSANDTHS) + ax;
        py = (int) (s * dy / THOUSANDTHS) + ay;
      }
    }
    int qx = px > left ? (px < right ? px : right) : left;
    int qy = py > bottom ? (py < top ? py : top) : bottom;
    int distanceSquared = (px - qx) * (px - qx) + (py - qy) * (py - qy);
    return Integer.compareUnsigned(distanceSquared, width * width) <= 0;
  }

  /**
   * The segment's point nearest a point: its projection in thousandths of the segment, the start
   * below 0, the end above 1000, else the point that many thousandths along, truncated.
   *
   * @return the point, along the width then the length
   */
  public static int[] nearestPoint(int ax, int ay, int bx, int by, int px, int py) {
    int dx = bx - ax;
    int dy = by - ay;
    int lengthSquared = dx * dx + dy * dy;
    if (lengthSquared == 0) {
      return new int[] {ax, ay};
    }
    int dot = (px - ax) * dx + (py - ay) * dy;
    long s = (long) dot * THOUSANDTHS / Integer.toUnsignedLong(lengthSquared);
    if (s < 0) {
      return new int[] {ax, ay};
    }
    if (s > THOUSANDTHS) {
      return new int[] {bx, by};
    }
    return new int[] {(int) (s * dx / THOUSANDTHS) + ax, (int) (s * dy / THOUSANDTHS) + ay};
  }
}
