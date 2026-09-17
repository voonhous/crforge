package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.Route;

/**
 * Everything the movement pass asks of the rest of the simulation, pulled at the moment it is
 * needed rather than gathered up front.
 *
 * <p>The pass never reaches into the grid, the targeting component or the match rules itself: it
 * asks here, and the answer is computed then and there. That ordering matters, because several
 * answers depend on a position the pass has already changed during the same visit.
 *
 * <p>The methods without a body are the ones a real match must answer. The methods with a body are
 * the answers the recorded trajectories use; each one names the branch it holds open or shut, so an
 * integrator can see exactly what changes by overriding it.
 *
 * <p>Boolean-looking answers are returned as {@code int} wherever the movement code tests a single
 * bit of them, which is how the standard game reads them.
 */
public interface MovementQueries {

  // ---------------------------------------------------------------------------------------------
  // Answers a real match must supply
  // ---------------------------------------------------------------------------------------------

  /**
   * Searches a route from one cell to another and returns it goal first, or an empty route when no
   * route exists.
   *
   * @param startCol column of the cell the entity stands in
   * @param startRow row of the cell the entity stands in
   * @param goalCol column of the goal cell
   * @param goalRow row of the goal cell
   * @param adjust 1 when an unreachable goal may be moved to the nearest usable cell; route
   *     preparation always asks for 1
   */
  Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust);

  /**
   * Chooses the cell a unit should stop at to reach its reference, and returns it packed as {@code
   * (column << 16) | row}, or -1 when the scan found nothing.
   *
   * <p>A negative answer makes route preparation fall back to the reference's raw position.
   *
   * @param referenceCol column of the cell the reference stands in
   * @param referenceRow row of the cell the reference stands in
   * @param radius the unit's attack range, in game units, which sizes the scan window
   */
  int endpoint(int referenceCol, int referenceRow, int radius);

  /**
   * Moves a position off water to the nearest usable cell centre and returns it packed as {@code x
   * | (y << 16)} in game units.
   */
  int relocate(int x, int y);

  /** Answers 1 when the cell holding a world position may not be stood on, 0 otherwise. */
  int cellTest(int worldX, int worldY);

  /** The entity's movement budget for this visit, in game units. */
  int speedBudget();

  /** 1 when the displacement may turn the entity to face its direction of travel. */
  int facingGate();

  /** 1 when the follower runs the avoidance handler this visit. */
  int avoidanceGate();

  /** 1 when the follower runs the push pass this visit. */
  int pushGate();

  /** 1 when the entity asks for a route this visit; only the three routing states do. */
  int routeRequest();

  /** The entity's attack range in game units, which sizes the endpoint scan. */
  int attackRange();

  /**
   * 1 when some node of the current route lies farther from the reference than the entity does, as
   * {@link RouteBeyondReference} answers it.
   */
  int farther();

  /** Which player the entity belongs to; a value outside 0..1 makes the overlay test read false. */
  int ownerSide();

  /** 1 when the entity flies. */
  int air();

  /** 1 when the entity walks on the ground, which is what makes it route at all. */
  int ground();

  /** 1 when the entity currently holds a reference to head for. */
  int referenceAvailable();

  /** Number of columns of the routing grid. */
  int gridWidth();

  /** The view of the entity the grid move consults before letting a step cross a cell edge. */
  GridMoveEntity entityView();

  // ---------------------------------------------------------------------------------------------
  // Answers the recorded trajectories use
  // ---------------------------------------------------------------------------------------------

  /**
   * The destination a game mode forces on a unit with no reference, packed like the endpoint, or a
   * negative value for none. Supplied as all bits set, so the standard match has no forced goal and
   * route preparation returns without touching the route.
   */
  default int gameModeGoal() {
    return -1;
  }

  /** 1 when the entity hovers, which exempts it from the relocation off water. Supplied as 0. */
  default int hovering() {
    return 0;
  }

  /**
   * True when the entity carries the modifier component the charge range and the facing suppression
   * hang off. Supplied as false, so both of those alternatives stay shut.
   */
  default boolean hasModifierComponent() {
    return false;
  }

  /** True when the entity carries a targeting component at all. Supplied as true. */
  default boolean targetingPresent() {
    return true;
  }

  /** True when the charge bookkeeping may look the targeting component up. Supplied as true. */
  default boolean targetingLookup() {
    return true;
  }

  /** True when the follower may write the entity's movement byte. Supplied as true. */
  default boolean targetingSlotZero() {
    return true;
  }

  /** True when a dash may stop because its reference came into range. Supplied as true. */
  default boolean targetingActive() {
    return true;
  }

  /**
   * 1 when a building on the arena's edge pushes a unit away from that edge. Supplied as 0, so the
   * push pass never adds the edge separation.
   */
  default int touchdownEdgeSeparate() {
    return 0;
  }

  /**
   * The waypoint a flying unit with direct paths heads for instead of a route node, as {@code {x,
   * y}}. Supplied as the origin, and unreachable while no unit flies direct paths.
   */
  default int[] specialWaypoint() {
    return new int[] {0, 0};
  }

  /** True when the entity takes part in pushing at all. Supplied as true. */
  default boolean ownerPushEnabled() {
    return true;
  }

  /** True when the entity still has hit points. Supplied as true. */
  default boolean ownerAlive() {
    return true;
  }

  /**
   * 1 when a push along a single axis may be copied onto the other axis after the pass has seen a
   * static neighbour. Supplied as 0; what writes it is not documented.
   */
  default int singleAxisPushCopyAllowed() {
    return 0;
  }

  /**
   * 1 while the match runs a touchdown mode, which enables both the push pass's edge separation and
   * the waypoint selector's x override. Supplied as 0.
   */
  default int touchdownModeActive() {
    return 0;
  }

  /**
   * 1 when a pushed ground unit's displacement honours the push at all. Supplied as 0, so the
   * pushed-ground branch of the displacement stays shut.
   */
  default int pushDisplacementEnabled() {
    return 0;
  }

  /**
   * 1 when the entity's facing may not be updated by a displacement even though the facing gate
   * allows it. Supplied as 0.
   */
  default int facingUpdateSuppressed() {
    return 0;
  }

  /**
   * The charge range taken from the entity's modifiers when its configuration carries none.
   * Supplied as 0.
   */
  default int chargeRangeFromModifiers() {
    return 0;
  }

  /** A counter route preparation reports after each search. Supplied as 0. */
  default int searchCounter() {
    return 0;
  }

  /**
   * The buff-scaled length of a tick in milliseconds, of which the follower adds half to its
   * movement time. Supplied as 100, so the unscaled step is the 50 ms tick.
   */
  default int scaledTimeStep() {
    return 100;
  }

  /**
   * 1 when the entity's reference is within its attack range, which stops a dash. Supplied as 0.
   */
  default int referenceInRange() {
    return 0;
  }

  /** 1 when the entity belongs to side 0, which decides how coincident units separate. */
  default int ownerSideZero() {
    return ownerSide() == 0 ? 1 : 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Per-neighbour answers the avoidance handler pulls
  // ---------------------------------------------------------------------------------------------

  /**
   * A per-neighbour enable bit the avoidance handler reads before it considers that neighbour at
   * all: the neighbour is asked whether it accepts physical contact from the entity looking around,
   * and is dropped when it does not. Only the low bit is tested. Its writers are not documented, so
   * the name describes only where it is read; supplied as accepting.
   */
  default int neighbourAcceptsContact(GridEntity other) {
    return 1;
  }

  /**
   * The avoidance blend a moving neighbour has of its own, which decides the side the entity steers
   * to when nothing else does. Supplied as 0, which falls back to the side the neighbour stands on.
   */
  default int neighbourAvoidanceBlend(GridEntity other) {
    return 0;
  }

  /**
   * Non-zero when a moving neighbour has a special attack loaded, which makes it count as facing
   * nowhere and therefore block whatever direction it is pointing in. Supplied as 0, which also
   * covers a neighbour with no targeting component at all.
   */
  default int neighbourSpecialLoadPending(GridEntity other) {
    return 0;
  }
}
