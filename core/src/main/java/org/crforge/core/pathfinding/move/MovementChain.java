package org.crforge.core.pathfinding.move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.CellGrid;

/**
 * The wiring between the movement visit and the four passes it drives, plus the record of what it
 * drove.
 *
 * <p>The movement visit does not call route preparation, the follower, the push pass or the
 * displacement helper directly: it announces each of them, in the order it reaches them, and the
 * chain runs the matching pass immediately on the state every pass shares. Running them at the
 * moment they are announced is what makes the passes see each other's writes, which several of them
 * rely on: the follower reads the position a displacement just produced, and the displacement reads
 * the route direction the initializer just wrote.
 *
 * <p>Everything the chain does not run is recorded by name in {@link #markers()}, in order. Those
 * are the points where the standard game notifies something outside the movement pass; a tick
 * driver may act on them or ignore them, and tests read them to check the order of a visit.
 *
 * <p>One chain covers one entity's visit. It is not thread safe.
 */
public final class MovementChain {

  private final MovementState component;
  private final GridEntity owner;
  private final CellGrid grid;
  private final MovementConfig config;
  private final MovementGlobals globals;
  private final ReferencePoint reference;
  private final NeighbourQuery neighbours;
  private final MovementQueries queries;
  private final List<String> markers = new ArrayList<>();

  /** Largest number of pushes the push pass accumulated in one run during this visit. */
  private int pushContributions;

  /** Largest step, in game units, any displacement of this visit was allowed to spend. */
  private int largestDisplacementStep;

  /**
   * Creates a chain over the state one movement visit works on, with a fixed neighbour list.
   *
   * <p>Both the push pass and the avoidance handler then see that same list whatever they ask for,
   * which is what a single-unit case wants: hand in an empty list and neither pass finds anything.
   *
   * @param component the entity's movement component
   * @param owner the entity being moved
   * @param grid the arena's routing grid and cost overlay
   * @param config the entity's movement configuration columns
   * @param globals the match-wide movement settings
   * @param reference the position the entity is heading for, or null when it has no reference
   * @param others the neighbours both passes consider, empty when the entity stands alone
   * @param queries the answers the movement pass pulls from the rest of the simulation
   */
  public MovementChain(
      MovementState component,
      GridEntity owner,
      CellGrid grid,
      MovementConfig config,
      MovementGlobals globals,
      ReferencePoint reference,
      List<GridEntity> others,
      MovementQueries queries) {
    this(component, owner, grid, config, globals, reference, NeighbourQuery.fixed(others), queries);
  }

  /**
   * Creates a chain over the state one movement visit works on, with the neighbours looked up at
   * the moment each pass asks for them.
   *
   * @param component the entity's movement component
   * @param owner the entity being moved
   * @param grid the arena's routing grid and cost overlay
   * @param config the entity's movement configuration columns
   * @param globals the match-wide movement settings
   * @param reference the position the entity is heading for, or null when it has no reference
   * @param neighbours the query that answers which entities are near a point
   * @param queries the answers the movement pass pulls from the rest of the simulation
   */
  public MovementChain(
      MovementState component,
      GridEntity owner,
      CellGrid grid,
      MovementConfig config,
      MovementGlobals globals,
      ReferencePoint reference,
      NeighbourQuery neighbours,
      MovementQueries queries) {
    this.component = component;
    this.owner = owner;
    this.grid = grid;
    this.config = config;
    this.globals = globals;
    this.reference = reference;
    this.neighbours = neighbours;
    this.queries = queries;
  }

  /** The position the entity is heading for, or null when it has none. */
  public ReferencePoint reference() {
    return reference;
  }

  /** The names of the announcements this chain recorded rather than ran, in order. */
  public List<String> markers() {
    return Collections.unmodifiableList(markers);
  }

  /** Records an announcement the chain does not act on itself. */
  public void mark(String name) {
    markers.add(name);
  }

  /** Runs route preparation on the shared state. */
  public void prepareRoute() {
    RoutePreparation.prepareRoute(component, owner, grid, globals, reference, queries, this);
  }

  /** Runs the route follower on the shared state. */
  public void follow() {
    RouteFollower.follow(component, owner, config, globals, grid, queries, this);
  }

  /**
   * Runs the avoidance handler on the shared state, over the entities within the entity's collision
   * radius - capped at 500 game units - of the point one facing vector ahead of it.
   */
  public void avoidance() {
    int radius = Math.min(owner.getCollisionRadius(), AvoidanceHandler.QUERY_RADIUS_CLAMP);
    List<GridEntity> near =
        neighbours.near(owner.getX() + owner.getDirX(), owner.getY() + owner.getDirY(), radius);
    try {
      AvoidanceHandler.avoidance(component, owner, near, queries, this);
    } finally {
      neighbours.release(near);
    }
  }

  /**
   * Runs the push pass on the shared state, over the entities within the entity's collision radius
   * plus the query margin of its own position.
   */
  public void pushPass() {
    List<GridEntity> near =
        neighbours.near(
            owner.getX(), owner.getY(), owner.getCollisionRadius() + PushPass.QUERY_MARGIN);
    try {
      PushPass.pushPass(component, owner, near, queries, this);
    } finally {
      neighbours.release(near);
    }
    pushContributions = Math.max(pushContributions, component.getPushCount());
  }

  /**
   * The largest number of neighbours the push pass accumulated a push from in one run during this
   * visit. The displacement zeroes the accumulators as it spends them, so this is the only record
   * left of whether the entity was pushed at all this tick.
   */
  public int pushContributions() {
    return pushContributions;
  }

  /**
   * Runs one displacement on the shared state and hands back what it left behind.
   *
   * @param targetX destination along the arena's width, in game units
   * @param targetY destination along the arena's length, in game units
   * @param budget the largest step the entity may take, in game units
   * @param updateFacing 1 when the entity may turn to face its direction of travel
   * @param attackFlag 1 when the step is an attack pushback
   */
  public MovementOutcome displace(
      int targetX, int targetY, int budget, int updateFacing, int attackFlag) {
    int step =
        Displacement.displace(
            component,
            owner,
            grid,
            config,
            globals,
            queries,
            this,
            targetX,
            targetY,
            budget,
            updateFacing,
            attackFlag);
    largestDisplacementStep = Math.max(largestDisplacementStep, step);
    return new MovementOutcome(owner.getX(), owner.getY(), component.getWaypointReached());
  }

  /**
   * The largest step, in game units, any displacement of this visit was allowed to spend: the
   * smaller of the visit's budget, the distance left to the waypoint and the 250-unit substep cap.
   * It does not include whatever a push added on top of that step.
   */
  public int largestDisplacementStep() {
    return largestDisplacementStep;
  }

  /** Recomputes the route direction pair from the route's next waypoint. */
  public void directionInit() {
    DirectionInitializer.directionInit(component, owner, grid.getWidth());
    markers.add("direction_init");
  }
}
