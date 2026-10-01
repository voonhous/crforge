package org.crforge.core.battle.spawn;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Where the spawner places each child around the point it is handed.
 *
 * <p>With a radius, the children stand evenly on a ring around the point: child {@code i} of {@code
 * n} at angle {@code (n - 1 - i) * 360 / n}, so the last one sits at angle 0, straight along the
 * width; a character source whose row sets an angle shift turns the whole ring by that shift plus
 * the angle it faces. With no radius, the children stand in front of the source: at an offset of
 * the source's collision radius plus the child's along the length, toward the enemy - negated for
 * the top team - and mirrored along the width right of the arena's middle. The in-front test is
 * asked of the offset turned by each quarter turn in order, and the first point it accepts is the
 * child's; when it refuses all four, the child stands one unit right of the source. A single child
 * has no offset, so it stands on the point itself whenever the test accepts it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded placements: the ring for a source that is not a"
            + " character, a single child on the point or one unit right of it, and children in"
            + " front of a character source by its collision radius and theirs, held by"
            + " tombstone_life and goblin_hut_life; the ring turned by a character source's angle"
            + " shift and facing, held by night_witch; the ring turned over by the lane and the"
            + " team, held by skeleton_barrel_tower and skeleton_barrel_shot_down. Not modelled:"
            + " the in-front offset of a source that is not a character; a character source's"
            + " minimum radius on a ring; and the step back a unit without hit points takes.")
public final class SpawnPlacement {

  /** The in-front test, asked of a point. */
  @FunctionalInterface
  public interface Passable {

    /** True when a child may stand at the point. */
    boolean test(int x, int y);
  }

  /** The quarter turns the in-front offset is tried at, in order. */
  private static final int[] TURNS = {0, 90, 180, 270};

  /** The in-front reach of a source whose collision radius the placement does not read. */
  public static final int NO_REACH = -1;

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
    return position(x, y, index, count, noOffset, radius, NO_REACH, 0, 0, passable);
  }

  /**
   * Where one child stands, before its creation keeps it inside the arena, for a source whose
   * in-front reach is known.
   *
   * @param x the point along the width
   * @param y the point along the length
   * @param index the child's index
   * @param count how many children the spawn makes
   * @param noOffset true for no in-front offset
   * @param radius the ring's radius, or 0 to place in front of the point
   * @param reach the in-front offset: the source's collision radius plus the child's, or {@link
   *     #NO_REACH} for a source that is not a character
   * @param team the source's team, 0 or 1
   * @param arenaWidth the arena's width in game units
   * @param passable the in-front test, asked only with no radius
   * @return the child's position as {x, y}
   */
  public static int[] position(
      int x,
      int y,
      int index,
      int count,
      boolean noOffset,
      int radius,
      int reach,
      int team,
      int arenaWidth,
      Passable passable) {
    return position(x, y, index, count, noOffset, radius, 0, reach, team, arenaWidth, passable);
  }

  /**
   * Where one child stands, for a source whose ring is turned: a character source whose row sets an
   * angle shift turns every ring angle by that shift plus the angle it faces.
   *
   * @param x the point along the width
   * @param y the point along the length
   * @param index the child's index
   * @param count how many children the spawn makes
   * @param noOffset true for no in-front offset
   * @param radius the ring's radius, or 0 to place in front of the point
   * @param turn the degrees every ring angle is turned by; the in-front offset is not turned
   * @param reach the in-front offset: the source's collision radius plus the child's, or {@link
   *     #NO_REACH} for a source that is not a character
   * @param team the source's team, 0 or 1
   * @param arenaWidth the arena's width in game units
   * @param passable the in-front test, asked only with no radius
   * @return the child's position as {x, y}
   */
  public static int[] position(
      int x,
      int y,
      int index,
      int count,
      boolean noOffset,
      int radius,
      int turn,
      int reach,
      int team,
      int arenaWidth,
      Passable passable) {
    if (radius != 0) {
      int angle = (count - 1 - index) * 360 / count + turn;
      int ox = towardZero(FixedMath.sine1024(angle + 90) * radius);
      int oy = towardZero(FixedMath.sine1024(angle) * radius);
      return new int[] {ox + x, oy + y};
    }
    if (!noOffset && reach == NO_REACH) {
      throw new UnsupportedOperationException(
          "children with no radius stand in front of a source that is not a character by its"
              + " own offset, which is not established");
    }
    int half = arenaWidth >> 1;
    for (int degrees : TURNS) {
      int[] offset = {0, noOffset ? 0 : reach};
      FixedMath.rotate1024(offset, degrees);
      // Mirrored right of the arena's middle, and toward the enemy for the top team.
      if (x > half) {
        offset[0] = -offset[0];
      }
      if (team == 1) {
        offset[1] = -offset[1];
      }
      int px = offset[0] + x;
      int py = offset[1] + y;
      if (passable.test(px, py)) {
        return new int[] {px, py};
      }
    }
    return new int[] {x + 1, y};
  }

  /**
   * A ring position turned over as a spawn that gives its children a fixed priority turns it: its
   * offset from the point is negated across the arena's width when the point lies in lane 1, and
   * along its length unless the source is on team 0.
   *
   * @param at the ring position {@link #position} gave
   * @param x the point along the width
   * @param y the point along the length
   * @param lane the lane of the point
   * @param team the source's team, 0 or 1
   * @return the turned position as {x, y}
   */
  public static int[] mirrored(int[] at, int x, int y, int lane, int team) {
    int ox = at[0] - x;
    int oy = at[1] - y;
    if (team != 0) {
      oy = -oy;
    }
    if (lane == 1) {
      ox = -ox;
    }
    return new int[] {ox + x, oy + y};
  }

  /** A value over 1024, rounded toward zero. */
  private static int towardZero(int value) {
    return (value < 0 ? value + 1023 : value) >> 10;
  }
}
