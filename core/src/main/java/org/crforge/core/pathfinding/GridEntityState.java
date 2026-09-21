package org.crforge.core.pathfinding;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The seventeen states a {@link GridEntity} can be in, and the state masks that gate movement.
 *
 * <p>A state is a small integer between 0 and 16. It decides whether the entity asks for a route,
 * how fast it may move this tick, whether it may turn to face its direction of travel, and whether
 * it takes part in pushing and avoidance. The gate masks below are indexed by state: bit {@code n}
 * of a mask is set when state {@code n} is gated. They only cover states 0..13, because no gate
 * rejects the three states above that.
 *
 * <p>The names describe the observed behaviour of each state. Several states have no writer on the
 * paths this package covers; they are listed so the masks stay complete and readable.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The seventeen state numbers are settled. What several of the rarely used"
            + " states mean is inferred from what enters and leaves them.")
public final class GridEntityState {

  private GridEntityState() {
    // Constants holder
  }

  /** Holding position: no movement, but the entity may still attack from where it stands. */
  public static final int STANDING = 0;

  /** Walking along a route. */
  public static final int MOVING = 1;

  /** Attacking a target; the entity does not move while in this state. */
  public static final int ATTACKING = 2;

  /** Running a dash. */
  public static final int DASHING = 3;

  /** Counting down the deploy time after being placed; no component runs. */
  public static final int DEPLOYING = 4;

  /** Following a jump arc, for example over the river. */
  public static final int JUMPING = 5;

  /** Routing to a spawn destination, at the spawn pathfinding speed. */
  public static final int SPAWN_PATHFIND = 6;

  /** Routing to a mid-match destination, at the in-game pathfinding speed. */
  public static final int INGAME_PATHFIND = 7;

  /** Being set up as a clone; treated like a deploy countdown. */
  public static final int CLONE_SETUP = 8;

  /** Morphing into another character; both component visits return immediately. */
  public static final int MORPHING = 9;

  /** Casting an ability. */
  public static final int CASTING = 10;

  /** Staggered before deployment starts; only the stagger countdown runs. */
  public static final int WAITING_TO_DEPLOY = 11;

  /** Removed from play while following another entity's position. */
  public static final int FOLLOWING_REMOVED = 12;

  /** The building form of {@link #FOLLOWING_REMOVED}. */
  public static final int FOLLOWING_REMOVED_BUILDING = 13;

  /** Components disabled; nothing on the routing paths reaches this state. */
  public static final int COMPONENTS_DISABLED = 14;

  /**
   * A second route-following state. It routes like {@link #MOVING} but with the default cell cost
   * and without a push gate; what puts an entity into it is not established.
   */
  public static final int ROUTE_FOLLOWING_ALTERNATE = 15;

  /** Counting down the follow-up of an ability. */
  public static final int ABILITY_FOLLOW_UP = 16;

  /** Highest state value that exists. */
  public static final int MAX_STATE = ABILITY_FOLLOW_UP;

  /**
   * States whose movement budget is zero: standing, attacking, deploying, casting and the two
   * removed-and-following states.
   */
  public static final int SPEED_ZERO_STATES = 0x3415;

  /**
   * States in which displacement may not update the entity's facing: the speed-zero states plus
   * clone setup.
   */
  public static final int FACING_GATE_MASK = 0x3515;

  /** States in which the follower does not run the avoidance handler. */
  public static final int AVOIDANCE_GATE_MASK = 0x35ed;

  /** States in which the follower does not run the push pass. */
  public static final int PUSH_GATE_MASK = 0x3140;

  /**
   * States in which the state visit accumulates into the deployment delay timer instead of the
   * ordinary elapsed-time field: deploying, spawn pathfinding and waiting to deploy.
   */
  public static final int DELAY_SKIP_MASK = 0x850;

  /**
   * Returns true when the given state is one of the states selected by a gate mask. States above 13
   * are never selected, matching the masks' width.
   */
  public static boolean inMask(int state, int mask) {
    return state >= 0 && state <= 13 && ((mask >> state) & 1) != 0;
  }
}
