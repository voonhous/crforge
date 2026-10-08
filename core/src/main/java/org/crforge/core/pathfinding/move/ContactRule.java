package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * Whether an entity takes part in unit-to-unit contact: the answer the push pass asks of the unit
 * it moves and of every neighbour, and the one the avoidance handler asks of every neighbour.
 *
 * <p>The collision answer is 1 for every entity, crown towers and other buildings included, except
 * that an entity takes no part:
 *
 * <ul>
 *   <li>under the no-check-collisions flag;
 *   <li>while attached to a parent;
 *   <li>in the dashing state, the jumping state or the first following-removed state;
 *   <li>while deploying as a clone;
 *   <li>(not reachable here) for a building of the placeable-building kind, while another entity
 *       stands within 500 of it edge to edge.
 * </ul>
 *
 * <p>The avoidance answer is 0 in either following-removed state and under the no-check-avoidance
 * flag, and the collision answer otherwise.
 *
 * <p>A crown tower answers 1 on both, so it is a neighbour of the push pass and an obstacle of the
 * avoidance handler. It has no movement component, so both passes treat it as a static neighbour.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the no-check-collisions and no-check-avoidance flags, the three states without"
            + " collision, the two following-removed states the avoidance answer drops, and 1 for"
            + " every other entity, crown towers included; held by the six Knight walks"
            + " GridGoldenTrajectoryTest replays, two of which deploy beside the unit's own"
            + " tower, by golden-gaps-v1/walk_knight_right_rear and by the placements of the"
            + " reference battles. An attached entity taking no part rests on the translation and"
            + " a unit test; no run has a unit meet a rider. A dashing unit taking no part,"
            + " neither pushed nor pushing, is held by the reference battles"
            + " ability_golden_knight, grid_clone_over_bandit_dash and"
            + " grid_log_over_bandit_dash_end; the standard game exempts a row with"
            + " DashingDamage, which no row sets and the battle refuses. A clone deploying taking"
            + " no part, neither pushed nor pushing, is held by ability_skeleton_king and"
            + " card_SkeletonKing; an entity byte that would keep such a clone in collision,"
            + " unnamed, is supplied clear, as for every unit the standard game makes. Supplied:"
            + " no building is of the placeable-building kind whose answer depends on the"
            + " entities near it, which is not reachable from the units the grid drives.")
public final class ContactRule {

  /** The states in which an entity takes no part in collision: dashing, jumping, hooked away. */
  private static final int STATES_WITHOUT_COLLISION =
      (1 << GridEntityState.DASHING)
          | (1 << GridEntityState.JUMPING)
          | (1 << GridEntityState.FOLLOWING_REMOVED);

  private ContactRule() {
    // Utility class
  }

  /**
   * 1 when the entity takes part in collision: it pushes the units that overlap it and, when it
   * moves, is pushed by them.
   */
  public static int collides(GridEntity entity) {
    if ((entity.getFlags() & entity.getFlagBits().noCheckCollisions()) != 0
        || entity.isAttached()) {
      return 0;
    }
    int state = entity.getState();
    // The dashing state exempts a row with DashingDamage; no row sets it, and the battle refuses
    // one.
    if (state <= GridEntityState.FOLLOWING_REMOVED
        && ((1 << state) & STATES_WITHOUT_COLLISION) != 0) {
      return 0;
    }
    if (state == GridEntityState.DEPLOYING && entity.isClone()) {
      return 0;
    }
    return 1;
  }

  /** 1 when the entity is a neighbour the avoidance handler steers around. */
  public static int avoidable(GridEntity entity) {
    int state = entity.getState();
    if (state == GridEntityState.FOLLOWING_REMOVED
        || state == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
      return 0;
    }
    if ((entity.getFlags() & entity.getFlagBits().noCheckAvoidance()) != 0) {
      return 0;
    }
    return collides(entity);
  }
}
