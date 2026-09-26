package org.crforge.core.battle.spawn;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Where the spawner places each child around the point it is handed.
 *
 * <p>With a radius, the children stand evenly on a ring around the point: child {@code i} of {@code
 * n} at angle {@code (n - 1 - i) * 360 / n}, so the last one sits at angle 0, straight along the
 * width. With no radius, a single child stands on the point itself, unless the in-front test
 * refuses the point, when it stands one unit right of it. That test is asked four times, once for
 * each quarter turn of an offset that for a single child is nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded placements: the ring for a source that is not a"
            + " character, and a single child on the point or one unit right of it. Not modelled:"
            + " several children with no radius, which stand in front of the source by its own"
            + " offset; a character source's angle shift and minimum radius on a ring; the ring's"
            + " lane mirror; and the step back a unit without hit points takes.")
public final class SpawnPlacement {

  /** The in-front test, asked of a point. */
  @FunctionalInterface
  public interface Passable {

    /** True when a child may stand at the point. */
    boolean test(int x, int y);
  }

  /** Quarter turns the in-front offset is tried at. */
  private static final int ATTEMPTS = 4;

  private SpawnPlacement() {
    // Utility class
  }

  /**
   * Where one child stands, before its creation keeps it inside the arena.
   *
   * @param x the point along the width
   * @param y the point along the length
   * @param index the child's index
   * @param count how many children the spawn makes
   * @param noOffset true for a single child, which has no in-front offset
   * @param radius the ring's radius, or 0 to place in front of the point
   * @param passable the in-front test, asked only with no radius
   * @return the child's position as {x, y}
   */
  public static int[] position(
      int x, int y, int index, int count, boolean noOffset, int radius, Passable passable) {
    if (radius != 0) {
      int angle = (count - 1 - index) * 360 / count;
      int ox = towardZero(FixedMath.sine1024(angle + 90) * radius);
      int oy = towardZero(FixedMath.sine1024(angle) * radius);
      return new int[] {ox + x, oy + y};
    }
    if (!noOffset) {
      throw new UnsupportedOperationException(
          "several children with no radius stand in front of the source by its own offset,"
              + " which is not established");
    }
    // The offset is nothing, so every quarter turn tests the point itself.
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (passable.test(x, y)) {
        return new int[] {x, y};
      }
    }
    return new int[] {x + 1, y};
  }

  /** A value over 1024, rounded toward zero. */
  private static int towardZero(int value) {
    return (value < 0 ? value + 1023 : value) >> 10;
  }
}
