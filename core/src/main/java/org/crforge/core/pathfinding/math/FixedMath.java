package org.crforge.core.pathfinding.math;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Integer arithmetic helpers shared by the grid routing, movement and targeting code.
 *
 * <p>Every routine here is pure integer arithmetic on 32-bit values. The standard game performs no
 * floating-point work on these paths, so the helpers must not either: results are defined by the
 * integer operations below and not by any real-valued approximation of them.
 *
 * <p>Two rules are easy to get wrong and are worth stating once:
 *
 * <ul>
 *   <li><b>Division truncates toward zero</b>, which is what Java's {@code /} already does. Some
 *       call sites divide by a value that can legitimately be zero and expect zero back rather than
 *       a failure; those use {@link #divOrZero(int, int)}. Call sites where a zero divisor would be
 *       a bug use {@link #div(int, int)}, which throws.
 *   <li><b>Shifts are not divisions.</b> Where the behaviour is a shift (flooring, so negative
 *       values round away from zero) the call site must write {@code >>} directly. Where it is a
 *       division by a power of two the call site must use {@link #div(int, int)}. The two differ
 *       for negative operands and both forms occur within a few lines of each other in the
 *       displacement code.
 * </ul>
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled against the 53 reference walks, which pass every distance, angle and"
            + " division of a walk through it, and its own boundary tests. The overflow guards"
            + " are held by the tests alone; no position on the arena reaches them.")
public final class FixedMath {

  /** Largest signed 32-bit value, used as the saturation result of the guarded helpers. */
  public static final int INT_MAX = Integer.MAX_VALUE;

  /**
   * Largest magnitude a single axis separation may have before the guarded helpers saturate. {@code
   * 46340 * 46340} is the largest square of a whole number that still fits in a signed 32-bit
   * value.
   */
  public static final int GUARD_LIMIT = 46340;

  /** Distance reported by {@link #guardedDistance(int, int)} when the squared sum saturates. */
  public static final int SATURATED_DISTANCE = 65535;

  private FixedMath() {
    // Utility class
  }

  /**
   * Signed division truncating toward zero. Throws {@link ArithmeticException} on a zero divisor;
   * use it where a zero divisor cannot occur and would indicate a bug.
   */
  public static int div(int a, int b) {
    return a / b;
  }

  /**
   * Signed division truncating toward zero that answers zero for a zero divisor. Several movement
   * call sites divide by a configured speed, an attack cooldown or a mass that may be zero and
   * expect zero rather than a failure.
   */
  public static int divOrZero(int a, int b) {
    return b == 0 ? 0 : a / b;
  }

  /**
   * Signed remainder matching {@link #div(int, int)}, so {@code mod(a, b)} takes the sign of {@code
   * a}. Throws on a zero divisor.
   */
  public static int mod(int a, int b) {
    return a % b;
  }

  /**
   * Wraps a wider value into a signed 32-bit result, discarding the bits above bit 31. Intermediate
   * products in the reference arithmetic are allowed to overflow and the low 32 bits are what the
   * following comparison sees.
   */
  public static int s32(long value) {
    return (int) value;
  }

  /**
   * Floor of the exact square root of a non-negative value, computed with integer arithmetic. A
   * plain {@code (int) Math.sqrt(n)} is not a safe substitute near the top of the 32-bit range
   * because the double conversion can round the root up; the correction loops below remove that.
   *
   * @throws IllegalArgumentException if {@code n} is negative
   */
  public static int isqrt(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("isqrt of a negative value: " + n);
    }
    int root = (int) Math.sqrt(n);
    while ((long) root * root > n) {
      root--;
    }
    while ((long) (root + 1) * (root + 1) <= n) {
      root++;
    }
    return root;
  }

  /**
   * Sum of the squares of a two-axis separation, saturating at {@link #INT_MAX}. The result
   * saturates when either axis leaves the range {@code -46340..46340} or when the sum itself would
   * not fit in a signed 32-bit value; it is never negative.
   */
  public static int guardedSumOfSquares(int dx, int dy) {
    if (dx < -GUARD_LIMIT || dx > GUARD_LIMIT || dy < -GUARD_LIMIT || dy > GUARD_LIMIT) {
      return INT_MAX;
    }
    int a = dx * dx;
    int b = dy * dy;
    return b < INT_MAX - a ? a + b : INT_MAX;
  }

  /**
   * Squared distance between two points, saturating at {@link #INT_MAX}. This is {@link
   * #guardedSumOfSquares(int, int)} over the separation, with the separation itself computed as a
   * wrapping 32-bit subtraction.
   */
  public static int squaredDistance(int px, int py, int x, int y) {
    return guardedSumOfSquares(s32((long) x - px), s32((long) y - py));
  }

  /**
   * Length of a two-axis separation: the floor of the square root of {@link
   * #guardedSumOfSquares(int, int)}, or {@link #SATURATED_DISTANCE} when that sum saturated.
   */
  public static int guardedDistance(int dx, int dy) {
    int sum = guardedSumOfSquares(dx, dy);
    return sum == INT_MAX ? SATURATED_DISTANCE : isqrt(sum);
  }

  /**
   * Rescales a two-element vector in place so that its length becomes {@code scale}, and returns
   * the vector's original length. A zero-length vector is left untouched and zero is returned.
   *
   * <p>Each component is scaled as {@code component * scale / length} with truncating division, so
   * the rescaled vector's own length is only approximately {@code scale}.
   *
   * @param vector a two-element array {@code {x, y}}, modified in place
   * @param scale the requested new length
   * @return the length of the vector before rescaling
   */
  public static int normalize(int[] vector, int scale) {
    if (vector.length != 2) {
      throw new IllegalArgumentException("normalize expects a two-element vector");
    }
    int length = guardedDistance(vector[0], vector[1]);
    if (length != 0) {
      vector[0] = div(vector[0] * scale, length);
      vector[1] = div(vector[1] * scale, length);
    }
    return length;
  }

  /**
   * Cheap approximate length of a two-axis separation: the larger absolute component plus {@code
   * 53/128} of the smaller. It overestimates a true diagonal by about four per cent and is used
   * where an ordering, not a precise length, is wanted.
   */
  public static int approxDistance(int dx, int dy) {
    int a = Math.abs(dx);
    int b = Math.abs(dy);
    int low = Math.min(a, b);
    int high = Math.max(a, b);
    return high + ((low * 53) >> 7);
  }

  /**
   * Sine of a whole number of degrees scaled by 1024, for any angle. The angle is reduced into
   * 0..359 first, so negative angles behave like their positive equivalents ({@code -90} gives the
   * same value as {@code 270}).
   */
  public static int sine1024(int degrees) {
    int angle = Math.floorMod(degrees, 360);
    int halfTurnAngle = angle >= 180 ? angle - 180 : angle;
    int lookupAngle = Math.min(halfTurnAngle, 180 - halfTurnAngle);
    int value = TrigTables.sine(lookupAngle);
    return angle >= 180 ? -value : value;
  }

  /**
   * Heading of a vector as a whole number of degrees in 0..359, measured counter-clockwise from the
   * positive x axis. The zero vector has heading 0.
   *
   * <p>The octant is decided first, then the minor axis is divided by the major axis to produce a
   * ratio index into the arc-tangent table. Inputs are assumed small enough that the minor
   * component shifted left by seven still fits in a signed 32-bit value.
   */
  public static int angleOfVector(int x, int y) {
    if ((x | y) == 0) {
      return 0;
    }
    if (x >= 1 && y >= 0) {
      return y < x ? atanRatio(y, x) : 90 - atanRatio(x, y);
    }
    int ax = Math.abs(x);
    if (x <= 0 && y >= 1) {
      return ax < y ? atanRatio(ax, y) + 90 : 180 - atanRatio(y, ax);
    }
    int ay = Math.abs(y);
    if (x < 0 && y <= 0) {
      if (ay < ax) {
        return atanRatio(ay, ax) + 180;
      }
      // Not reachable while ay >= ax > 0, but kept so the octant chain stays complete.
      return y == 0 ? 0 : 270 - atanRatio(ax, ay);
    }
    // x > 0 with y < 0, or x == 0 with y < 0.
    if (ax < ay) {
      return atanRatio(ax, ay) + 270;
    }
    if (x == 0) {
      return 0;
    }
    int degrees = atanRatio(ay, ax);
    int wrapped = 360 - degrees;
    // A full turn is the same heading as none: a vector so shallow that the ratio lookup answers
    // zero degrees points straight along the positive x axis and its heading is 0, not 360. This
    // keeps the documented 0..359 range, which the caller's unwrap around a parent's heading
    // depends on.
    if (wrapped == 360) {
      return 0;
    }
    return wrapped < 0 ? wrapped + 360 : wrapped;
  }

  /** Arc-tangent lookup for a minor/major pair, as a whole number of degrees between 0 and 45. */
  private static int atanRatio(int numerator, int denominator) {
    return TrigTables.atan(div(numerator << 7, denominator));
  }
}
