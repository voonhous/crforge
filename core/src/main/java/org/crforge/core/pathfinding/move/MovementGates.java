package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntityState;

/**
 * The three yes-or-no questions the follower asks about an entity before it turns it, steers it
 * around a neighbour or lets it be pushed.
 *
 * <p>All three answer 1 for yes and 0 for no, which is how the movement code reads them. Each
 * starts from a different condition and they do not test the same things in the same order, so they
 * are written out separately rather than folded together.
 */
public final class MovementGates {

  /** Avoidance is disabled for this entity outright. */
  private static final long NO_CHECKAVOIDANCE = 1L << 15;

  /** Collision handling, and with it pushing, is disabled for this entity outright. */
  private static final long NO_CHECKCOLLISIONS = 1L << 14;

  private MovementGates() {
    // Utility class
  }

  /**
   * 1 when a displacement may turn the entity to face its direction of travel.
   *
   * <p>Shut in the states that hold still plus clone setup, while the targeting component winds up
   * a dash, and while the entity's movement-blocking countdown runs.
   */
  public static int facingGate(SpeedInputs entity) {
    if (GridEntityState.inMask(entity.state(), GridEntityState.FACING_GATE_MASK)) {
      return 0;
    }
    if (SpeedBudget.targetingActive(entity)) {
      return 0;
    }
    return entity.blockCountdownMs() < 1 ? 1 : 0;
  }

  /**
   * 1 when the follower runs the avoidance handler this visit.
   *
   * <p>Shut under the avoidance-disabled flag, in the states that hold still or follow a fixed arc,
   * while a special attack is loaded or a dash is winding up, and while the entity's
   * movement-blocking countdown runs.
   */
  public static int avoidanceGate(SpeedInputs entity) {
    if ((entity.flags() & NO_CHECKAVOIDANCE) != 0) {
      return 0;
    }
    if (GridEntityState.inMask(entity.state(), GridEntityState.AVOIDANCE_GATE_MASK)) {
      return 0;
    }
    if (entity.hasTargetingComponent()) {
      if (entity.specialLoadPending() != 0) {
        return 0;
      }
      if (SpeedBudget.targetingActive(entity)) {
        return 0;
      }
    }
    return entity.blockCountdownMs() < 1 ? 1 : 0;
  }

  /**
   * 1 when the follower runs the push pass this visit.
   *
   * <p>Shut while a dash winds up, under the collisions-disabled flag, while the entity's
   * movement-blocking countdown runs, and in spawn pathfinding, clone setup and the two
   * removed-and-following states. A unit routing to a mid-match destination is pushed only when its
   * configuration says it is visible there.
   */
  public static int pushGate(SpeedInputs entity, SpeedConfig config) {
    if (SpeedBudget.targetingActive(entity)) {
      return 0;
    }
    if ((entity.flags() & NO_CHECKCOLLISIONS) != 0) {
      return 0;
    }
    if (entity.blockCountdownMs() > 0) {
      return 0;
    }
    int state = entity.state();
    // The state is compared without a sign, so a negative value falls straight through to 1.
    if (Integer.compareUnsigned(state, GridEntityState.FOLLOWING_REMOVED_BUILDING) <= 0) {
      if (GridEntityState.inMask(state, GridEntityState.PUSH_GATE_MASK)) {
        return 0;
      }
      if (state == GridEntityState.INGAME_PATHFIND) {
        return config.ingamePathfindVisible() ? 1 : 0;
      }
    }
    return 1;
  }
}
