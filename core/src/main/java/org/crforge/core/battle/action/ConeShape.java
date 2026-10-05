package org.crforge.core.battle.action;

import lombok.Builder;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * A target resolver's Cone shape, as the Minion Giant looks behind itself for a friendly Minion. A
 * Cone is a Circle with an angle: what the circle query collects around the point asked (a building
 * by its square, anything else strictly within the radius plus its collision radius) is then
 * narrowed to what lies within the cone.
 *
 * <p>The cone points along {@link #direction(int)}: AngleOffset plus, with UseGameObjectDirection,
 * the heading the owner faces, in whole degrees reduced into 0..359. Half its angle, toward zero,
 * is how far either side of that direction it reaches. An Angle above 359 keeps the whole circle.
 *
 * <p>Without CheckOrigin an object is kept when the heading from the point to its centre lies
 * within half the angle of the direction; else when the point lies within its collision radius;
 * else when it is at most 89 degrees past the edge and its collision radius reaches the edge line:
 * at least the sine of the degrees past the edge (the 1024-scaled table) times the floor square
 * root of its squared distance, over 1024. With CheckOrigin the centre must also lie within the
 * radius, the squared distance at most the squared radius, and the cone keeps an object at the
 * point itself or within half the angle, whatever its collision radius.
 *
 * @param radius the Circle's Radius
 * @param angle the cone's Angle, in degrees
 * @param angleOffset AngleOffset, the degrees the cone is turned by
 * @param useDirection UseGameObjectDirection: the cone turns with the owner's heading
 * @param checkOrigin CheckOrigin: the centre must lie within the radius, and the cone tests the
 *     centre alone
 */
@Builder
public record ConeShape(
    int radius, int angle, int angleOffset, boolean useDirection, boolean checkOrigin) {

  /** The widest angle that is still a cone; an Angle above it keeps the whole circle. */
  private static final int FULL_TURN = 359;

  /** The farthest past its edge, in degrees, an object may still reach the cone by its radius. */
  private static final int REACH_LIMIT = 89;

  /**
   * The direction the cone points along, in degrees in 0..359.
   *
   * @param heading the owner's heading in degrees, used only with UseGameObjectDirection
   */
  public int direction(int heading) {
    return Math.floorMod(angleOffset + (useDirection ? heading : 0), 360);
  }

  /**
   * Whether the cone keeps an object the circle query collected.
   *
   * @param direction the cone's direction, from {@link #direction(int)}
   * @param dx the object's centre less the point, along the width
   * @param dy the object's centre less the point, along the length
   * @param collisionRadius the object's collision radius
   */
  public boolean keeps(int direction, int dx, int dy, int collisionRadius) {
    int half = angle / 2;
    // The squared distance in 32 bits, compared without sign, as the shape compares it.
    int squared = dx * dx + dy * dy;
    if (checkOrigin) {
      if (Integer.compareUnsigned(squared, radius * radius) > 0) {
        return false;
      }
      if (angle > FULL_TURN || (dx | dy) == 0) {
        return true;
      }
      return Math.abs(offAxis(direction, dx, dy)) <= half;
    }
    if (angle > FULL_TURN) {
      return true;
    }
    int past = Math.abs(offAxis(direction, dx, dy)) - half;
    if (past < 1 || Integer.compareUnsigned(squared, collisionRadius * collisionRadius) <= 0) {
      return true;
    }
    if (past > REACH_LIMIT) {
      return false;
    }
    long across = (long) FixedMath.sine1024(past) * FixedMath.isqrt(squared);
    return collisionRadius >= (int) (across / 1024);
  }

  /** The heading from the point to the object less the cone's direction, reduced into -180..179. */
  private static int offAxis(int direction, int dx, int dy) {
    int turn = Math.floorMod(FixedMath.angleOfVector(dx, dy) - direction, 360);
    return turn > 179 ? turn - 360 : turn;
  }
}
