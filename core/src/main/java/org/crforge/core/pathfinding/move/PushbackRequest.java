package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Starting a pushback: the request that decides whether an entity may be pushed, and the setter
 * that aims its movement component's pushback, which the pushback visit then flies.
 *
 * <p>The request refuses when a pushback is already in flight, unless the caller asks to keep the
 * longer of the two; and, unless the caller lifts the gates, when the entity's row ignores
 * pushback, a buff refuses it, the entity carries the no-pushback flag or it is being dragged by a
 * hook. A hidden entity is refused unless the caller asks otherwise.
 *
 * <p>The setter pushes the entity directly away from the given point, one unit along the width when
 * it stands on the point itself, the way its id's parity says. The distance is capped at the
 * battle's longest pushback, and with the separation taken off it first when the caller asks, never
 * below zero. A longer pushback still in flight is kept when the caller asks. Otherwise a dash
 * wind-up is resumed, the attack flag set, and for a distance of at least one the budget becomes
 * the smallest multiple of 25 whose triangular sum of steps covers the distance, the target moves
 * that distance from the entity, and the pushback is in flight; a distance of zero ends one.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line and held by 2000 recorded cases: the gates and what lifts them, the"
            + " zero vector, the cap and the separation, the longer pushback kept, the dash"
            + " wind-up resumed, the budget, the target and the in-flight flags. Supplied: no buff"
            + " refuses a pushback, nothing is hidden and no ported unit dashes. Not modelled: the"
            + " direction told to the presentation and the source player's statistics.")
public final class PushbackRequest {

  /** The longest pushback the standard game allows, in game units. */
  public static final int MAX_PUSHBACK_LENGTH = 40_000;

  /** Growth of each successive step of a pushback's budget, in game units. */
  private static final int BUDGET_STEP = 25;

  private PushbackRequest() {
    // Utility class
  }

  /**
   * Asks for a pushback of an entity away from a point.
   *
   * @param m the entity's movement component
   * @param owner the entity
   * @param queries what the request asks of the entity
   * @param x the point pushed away from
   * @param y the point pushed away from
   * @param distance how far, in game units
   * @param liftGates true to push even an entity whose row, buffs, flags or state refuse it
   * @param attack true when the pushback comes from the entity's own attack
   * @param subtract true to take the current separation off the distance first
   * @param keepLonger true to accept the request with a pushback in flight, keeping the longer one
   * @param evenIfHidden true to push a hidden entity as well
   * @return 1 when the setter ran, else 0
   */
  public static int request(
      MovementState m,
      GridEntity owner,
      PushbackQueries queries,
      int x,
      int y,
      int distance,
      boolean liftGates,
      boolean attack,
      boolean subtract,
      boolean keepLonger,
      boolean evenIfHidden) {
    if (m.getPushbackInFlight() != 0 && !keepLonger) {
      return 0;
    }
    if (queries.ignoresPushback() && !liftGates) {
      return 0;
    }
    if (queries.buffRefusesPushback() && !liftGates) {
      return 0;
    }
    if ((owner.getFlags() & EntityFlags.NO_PUSHBACK) != 0 && !liftGates) {
      return 0;
    }
    if (queries.hidden() && !evenIfHidden) {
      return 0;
    }
    if (owner.getState() == GridEntityState.FOLLOWING_REMOVED_BUILDING && !liftGates) {
      return 0;
    }
    set(m, owner, queries, x, y, distance, attack, subtract, keepLonger);
    return 1;
  }

  /**
   * Aims the entity's pushback away from a point, as described on the class.
   *
   * @param m the entity's movement component
   * @param owner the entity
   * @param queries what the setter asks of the entity
   * @param x the point pushed away from
   * @param y the point pushed away from
   * @param distance how far, in game units
   * @param attack true when the pushback comes from the entity's own attack
   * @param subtract true to take the current separation off the distance first
   * @param keepLonger true to keep a longer pushback already in flight
   */
  public static void set(
      MovementState m,
      GridEntity owner,
      PushbackQueries queries,
      int x,
      int y,
      int distance,
      boolean attack,
      boolean subtract,
      boolean keepLonger) {
    int dx = owner.getX() - x;
    int dy = owner.getY() - y;
    int length = FixedMath.isqrt(dx * dx + dy * dy);
    if (length == 0) {
      length = 1;
      dx = (owner.getId() & 1) != 0 ? -1 : 1;
    }
    int cap = queries.maxPushbackLength();
    if (subtract) {
      int left = distance - length;
      distance = left > 0 ? Math.min(left, cap) : 0;
    } else {
      distance = Math.min(distance, cap);
    }
    if (m.getPushbackInFlight() != 0 && keepLonger && distance < m.getPushbackBudget()) {
      return;
    }
    if (queries.dashWindupMs() > 0) {
      queries.resumeDashWindup();
    }
    m.setAttackPushback(attack ? 1 : 0);
    m.setTargetX(owner.getX());
    m.setTargetY(owner.getY());
    if (length >= 1 && distance >= 1) {
      int step = 0;
      int total = 0;
      do {
        step += BUDGET_STEP;
        total += step;
      } while (total < distance);
      m.setPushbackBudget(step);
      m.setTargetX(m.getTargetX() + distance * dx / length);
      m.setTargetY(m.getTargetY() + distance * dy / length);
      m.setPushbackInFlight(1);
    } else {
      m.setPushbackInFlight(0);
      m.setAttackPushback(0);
    }
  }
}
