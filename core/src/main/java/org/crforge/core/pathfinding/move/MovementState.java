package org.crforge.core.pathfinding.move;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.pathfinding.grid.Route;

/**
 * Working state of one entity's movement component: the route it follows, the timers the follower
 * counts, the push accumulators the push pass fills and the scratch pair the waypoint selector and
 * the displacement helper share.
 *
 * <p>Nothing that belongs to the entity itself lives here; positions, facing, state and flags are
 * on the entity. This class is a plain mutable holder with no behaviour, and is not thread safe.
 *
 * <p>Units: distances and offsets are integer game units (1000 per arena tile, 500 per routing
 * cell), timers are milliseconds advancing 50 per tick at 20 ticks per second, and the direction
 * pair is a vector scaled to length 256.
 *
 * <p>Three initial values are load bearing and are set by {@link #forSide(int, int, int)}: the
 * charge progress starts at -1 (no charge in progress, which is what stops the charge bookkeeping
 * from running at all), the dash stop-in-range enable starts at 1, and the route direction pair
 * starts pointing down the arena for side 0 and up it for side 1.
 */
@Getter
@Setter
public final class MovementState {

  /** Value of {@link #chargeProgress} meaning that no charge is in progress. */
  public static final int CHARGE_INACTIVE = -1;

  /** Value {@link #chargeProgress} reaches when the charge is complete. */
  public static final int CHARGE_COMPLETE = 10000;

  /** Length the route direction pair is normalized to. */
  public static final int DIRECTION_SCALE = 256;

  /** Destination, x, of the last displacement the pushback path asked for. */
  private int targetX;

  /** Destination, y, of the last displacement the pushback path asked for. */
  private int targetY;

  /** Remaining budget of an in-flight pushback, in game units; it drops by 25 per visit. */
  private int pushbackBudget;

  /**
   * Countdown in milliseconds that makes the movement visit replay the last displacement instead of
   * following the route. It is the movement component's own countdown, distinct from the entity's.
   */
  private int blockCountdown;

  /** 1 while a pushback is in flight, 0 otherwise. */
  private int pushbackInFlight;

  /**
   * 1 when the in-flight pushback came from an attack, which selects the end-of-pushback action.
   */
  private int attackPushback;

  /** Explicit destination, x, in game units, or -1 when the route follows a reference instead. */
  private int explicitX = -1;

  /** Explicit destination, y, in game units, or -1 when the route follows a reference instead. */
  private int explicitY = -1;

  /** The route being followed, goal first; the last node is the next waypoint. */
  private Route route = new Route();

  /**
   * 1 when some node of the route lies farther from the reference than the entity does, which is
   * what {@link RouteBeyondReference} answers after each replan.
   */
  private int routeLeadsAway;

  /**
   * Charge progress: {@link #CHARGE_INACTIVE} when no charge is in progress, otherwise a value that
   * grows toward {@link #CHARGE_COMPLETE} as the entity walks.
   */
  private int chargeProgress = CHARGE_INACTIVE;

  /**
   * Milliseconds of movement accumulated, against which the stop-movement-after limit is tested.
   */
  private int moveTimeMs;

  /** Copy of {@link #moveTimeMs} taken at the head of every ordinary follower visit. */
  private int moveTimeMsCopy;

  /** Sideways blend the avoidance handler writes, -200..200; the displacement rotates by it. */
  private int avoidanceBlend;

  /** 1 when a dash stops as soon as the reference is in range. */
  private int dashStopsInRange = 1;

  /** 1 when the last displacement arrived at its waypoint, which pops the route's last node. */
  private int waypointReached;

  /** Length of the jump arc in game units, the longest remaining distance seen during the jump. */
  private int jumpTotalDistance;

  /** Remaining dash time in milliseconds. */
  private int dashTimeMs;

  /** Heights sampled along a jump arc, in game units. */
  private final List<Integer> jumpHeights = new ArrayList<>();

  /** Heights sampled along a jump arc added to the entity's own height, in game units. */
  private final List<Integer> jumpAbsoluteHeights = new ArrayList<>();

  /** Accumulated push vector, x, summed over the neighbours of the last push pass. */
  private int pushX;

  /** Accumulated push vector, y, summed over the neighbours of the last push pass. */
  private int pushY;

  /** Number of neighbours that contributed to {@link #pushX} and {@link #pushY}. */
  private int pushCount;

  /**
   * Scratch pair shared by the waypoint selector, which writes the chosen waypoint into it, and the
   * displacement helper, which reuses it for the blended and averaged push vectors. The two uses
   * alias deliberately: keep one pair rather than giving each step its own.
   */
  private final int[] scratch = new int[2];

  /** Set by the push pass when the entity cannot be moved out of its cell. */
  private int pushStuck;

  /** Set by the push pass to leave the averaged push vector unclamped. */
  private int pushUnclamped;

  /**
   * Route direction, x, from the entity to the next waypoint, scaled to {@link #DIRECTION_SCALE}.
   */
  private int routeDirX;

  /**
   * Route direction, y, from the entity to the next waypoint, scaled to {@link #DIRECTION_SCALE}.
   */
  private int routeDirY;

  /**
   * Creates the movement state an entity is placed with: charge inactive, dash stop-in-range
   * enabled, no route, and a route direction pointing down the arena for side 0 and up it for side
   * 1.
   *
   * @param side which player the entity belongs to, 0 or 1
   * @param x the placement position along the arena's width, in game units
   * @param y the placement position along the arena's length, in game units
   */
  public static MovementState forSide(int side, int x, int y) {
    MovementState state = new MovementState();
    state.targetX = x;
    state.targetY = y;
    state.routeDirY = side == 0 ? DIRECTION_SCALE : -DIRECTION_SCALE;
    return state;
  }

  /** Overwrites the scratch pair with the given values. */
  public void setScratch(int x, int y) {
    scratch[0] = x;
    scratch[1] = y;
  }

  /** Clears the accumulated push vector, its count and the two push bits. */
  public void clearPush() {
    pushX = 0;
    pushY = 0;
    pushCount = 0;
    pushStuck = 0;
    pushUnclamped = 0;
  }
}
