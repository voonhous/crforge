package org.crforge.core.pathfinding.index;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The four geometric acceptance tests a spatial query can apply to an entity.
 *
 * <p>Which one runs is decided by the query's half height and its building-aware flag, as {@link
 * SpatialIndex} documents. All four work on integer game units and use 32-bit arithmetic
 * throughout: the squares below are allowed to wrap, and two of them compare the squared values as
 * unsigned words, so {@link Integer#compareUnsigned(int, int)} is used where that applies.
 */
public final class ShapeTests {

  private ShapeTests() {
    // Utility class
  }

  /**
   * Squared distance from the entity's centre to a point, saturating at {@link FixedMath#INT_MAX}.
   */
  public static int squaredDistanceToCentre(GridEntity entity, int x, int y) {
    return FixedMath.guardedSumOfSquares(x - entity.getX(), y - entity.getY());
  }

  /**
   * Plain circle test: the point is strictly closer to the entity's centre than the entity's own
   * collision radius plus the query radius. This is the test the target selector's query uses.
   */
  public static boolean withinCircle(GridEntity entity, int x, int y, int radius) {
    int reach = entity.getCollisionRadius() + radius;
    return squaredDistanceToCentre(entity, x, y) < reach * reach;
  }

  /**
   * Clamps {@code value} into {@code [low, low + size]} and answers how far it had to move. The
   * upper bound is applied first, so a degenerate range with a negative size answers relative to
   * {@code low}.
   */
  static int clampedOffset(int value, int low, int size) {
    int clamped = Math.min(value, low + size);
    clamped = clamped > low ? clamped : low;
    return clamped - value;
  }

  /**
   * Box test: the entity's circle intersects the axis-aligned box {@code [left, left + width] x
   * [top, top + height]}. The final comparison of the squared offset against the squared radius is
   * unsigned.
   */
  public static boolean withinBox(GridEntity entity, int left, int top, int width, int height) {
    int r = entity.getCollisionRadius();
    int dx = clampedOffset(entity.getX(), left, width);
    if (dx > r || dx < -r) {
      return false;
    }
    int dy = clampedOffset(entity.getY(), top, height);
    if (dy > r || dy < -r) {
      return false;
    }
    return Integer.compareUnsigned(dx * dx + dy * dy, r * r) < 0;
  }

  /**
   * Building-aware circle test: a building clamps the query point into its own square and needs the
   * resulting offset to lie strictly inside the query radius; anything else is tested as {@link
   * #withinCircle(GridEntity, int, int, int)} does.
   */
  public static boolean withinCircleShape(GridEntity entity, int x, int y, int radius) {
    if (!entity.isBuilding()) {
      return withinCircle(entity, x, y, radius);
    }
    int r = entity.getCollisionRadius();
    int dx = clampedOffset(x, entity.getX() - r, 2 * r);
    if (dx > radius || dx < -radius) {
      return false;
    }
    int dy = clampedOffset(y, entity.getY() - r, 2 * r);
    if (dy > radius || dy < -radius) {
      return false;
    }
    return Integer.compareUnsigned(dx * dx + dy * dy, radius * radius) < 0;
  }

  /**
   * Box overlap: the entity's own square overlaps {@code [x - radius, x + radius] x [y -
   * halfHeight, y + halfHeight]}. The far sides are strict, the near sides are not.
   */
  public static boolean boxOverlap(GridEntity entity, int x, int y, int radius, int halfHeight) {
    int r = entity.getCollisionRadius();
    int ex = entity.getX();
    int ey = entity.getY();
    if (ex + r < x - radius) {
      return false;
    }
    if (ey + r < y - halfHeight) {
      return false;
    }
    if (ex - r >= x + radius) {
      return false;
    }
    return ey - r < y + halfHeight;
  }
}
