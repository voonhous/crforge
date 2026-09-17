package org.crforge.core.pathfinding.target;

import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The geometric test that decides whether an attacker may hit a target.
 *
 * <p>Distances are compared squared and in 32-bit integers throughout. The squared distance
 * saturates rather than overflowing, so a target far outside the arena simply reads as unreachable;
 * the squared thresholds are ordinary wrapping products, matching the arithmetic the rest of the
 * targeting code uses.
 */
public final class RangeTest {

  private RangeTest() {
    // Utility class
  }

  /**
   * Squared distance between two points, saturating at {@link Integer#MAX_VALUE} once either
   * separation leaves the range -46340..46340 or the sum would not fit in a signed 32-bit value.
   */
  public static int squaredDistance(int px, int py, int x, int y) {
    return FixedMath.squaredDistance(px, py, x, y);
  }

  /**
   * Tests a point against a target.
   *
   * <p>The point must lie within {@code target radius + range} of the target's centre and, when the
   * minimum is positive, at or beyond {@code target radius + minimum}. With {@code innerOnly} set,
   * only the minimum half of the test applies, and with no minimum an {@code innerOnly} test
   * accepts everything.
   *
   * @param target the entity being tested against
   * @param x position of the attacker along the arena's width
   * @param y position of the attacker along the arena's length
   * @param range attack range measured to the target's edge
   * @param minimumRange closest accepted distance to the target's edge, 0 for none
   * @param innerOnly true to drop the outer half of the test
   */
  public static boolean rangeTest(
      TargetView target, int x, int y, int range, int minimumRange, boolean innerOnly) {
    int radius = target.radius();
    int outer = (radius + range) * (radius + range);
    int squared = squaredDistance(target.x(), target.y(), x, y);
    if (minimumRange >= 1) {
      int inner = (radius + minimumRange) * (radius + minimumRange);
      boolean beyondInner = squared >= inner;
      return innerOnly ? beyondInner : squared <= outer && beyondInner;
    }
    return squared <= outer || innerOnly;
  }

  /**
   * Tests the owner's own position against a target with the component's current attack range, the
   * range widened by {@code extension}, and the component's minimum range applied.
   */
  public static boolean referenceInRange(TargetingState t, TargetView target, int extension) {
    int range = AttackRange.attackRange(t);
    int minimum = AttackRange.minRange(t);
    return rangeTest(
        target, t.getOwner().getX(), t.getOwner().getY(), range + extension, minimum, false);
  }
}
