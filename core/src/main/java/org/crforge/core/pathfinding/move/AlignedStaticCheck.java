package org.crforge.core.pathfinding.move;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;

/**
 * Whether a unit stands in line with two static entities: the question the push pass asks after it
 * has counted a static neighbour, whose yes copies a push along one axis onto the other.
 *
 * <p>Over the entities the index lists within {@link #QUERY_RADIUS} of the unit, in the order it
 * lists them, an entity counts when it is not the unit, is on the unit's layer, has no movement
 * component at all - a unit waiting to deploy keeps its switched-off one and does not count - and
 * stands exactly on the unit's x or exactly on its y. The answer is 1 at the second entity that
 * counts, and 0 when there is no second one.
 *
 * <p>A crown tower is a static entity, but it is one: the king and a princess tower of a side stand
 * too far apart for one query to list both, so with the towers alone the answer is always 0. It
 * takes placed buildings, two of them or one beside a tower, lined up with the unit.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line and held by 1500 recorded cases: owner skipped, layer test, no"
            + " movement component, exact x or y, the second match. The query is the index's own"
            + " circle test at the unit's live position.")
public final class AlignedStaticCheck {

  /** Radius, in game units, of the query around the unit. */
  public static final int QUERY_RADIUS = 1000;

  private AlignedStaticCheck() {
    // Utility class
  }

  /**
   * The answer over the entities the query listed.
   *
   * @param owner the unit the push pass moves
   * @param listed the entities the query around the unit listed, in order; the unit itself may be
   *     among them and is skipped
   * @return 1 when a second static entity stands on the unit's x or y, else 0
   */
  public static int answer(GridEntity owner, List<GridEntity> listed) {
    int ownerHeight = owner.getZTotal();
    int matches = 0;
    for (GridEntity other : listed) {
      if (other == owner) {
        continue;
      }
      if ((ownerHeight > 0) == (other.getZTotal() < 1)) {
        continue;
      }
      if (other.isMovementComponent()) {
        continue;
      }
      if (other.getX() == owner.getX() || other.getY() == owner.getY()) {
        if (matches > 0) {
          return 1;
        }
        matches++;
      }
    }
    return 0;
  }
}
