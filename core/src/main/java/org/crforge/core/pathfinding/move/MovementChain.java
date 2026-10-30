/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
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
 * driver may act on them or ignore them, and tests read them to check the order of a visit. A chain
 * built {@link #withoutMarkers()} records nothing, for a driver that ignores them.
 *
 * <p>One chain covers one entity's visit. It is not thread safe. A chain is built over the state
 * one movement visit works on, with the neighbours looked up at the moment each pass asks for them.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Runs each pass at the point the visit announces it, so later passes see earlier writes;"
            + " held by the walks of the reference battles. Hands the follower's state requests, a"
            + " completed charge and a dash's landing to the owner's requests when it has them,"
            + " held by the reference battles card_Prince, card_HogRider and card_Assassin;"
            + " without them they are recorded by name only, as are the end actions.")
@RequiredArgsConstructor
public final class MovementChain {

  /** The entity's movement component. */
  private final MovementState component;

  /** The entity being moved. */
  private final GridEntity owner;

  /** The arena's routing grid and cost overlay. */
  private final CellGrid grid;

  /** The entity's movement configuration columns. */
  private final MovementConfig config;

  /** The match-wide movement settings. */
  private final MovementGlobals globals;

  /** The position the entity is heading for, or null when it has no reference. */
  private final ReferencePoint reference;

  /** The query that answers which entities are near a point. */
  private final NeighbourQuery neighbours;

  /** The answers the movement pass pulls from the rest of the simulation. */
  private final MovementQueries queries;

  /**
   * The announcements recorded rather than run, in order, or null when the chain records none. A
   * visit with neighbours records dozens of them, so a driver that never reads them turns the
   * record off rather than grow a list on every visit.
   */
  private List<String> markers = new ArrayList<>();

  /** Where the pass's state requests and a completed charge go, or null to only record them. */
  private MovementRequests requests;

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
   * Hands the pass's state requests and a completed charge to the owner, as they are made.
   *
   * @param requests the owner's answers to them
   * @return this chain
   */
  public MovementChain withRequests(MovementRequests requests) {
    this.requests = requests;
    return this;
  }

  /**
   * Turns off the record of announcements: {@link #markers()} then stays empty. What the chain runs
   * and hands to the owner is unchanged, since nothing in the chain reads its own record.
   *
   * @return this chain
   */
  public MovementChain withoutMarkers() {
    this.markers = null;
    return this;
  }

  /**
   * Records a state request under its name and hands it to the owner, which applies it at once.
   *
   * @param name the name the request is recorded under
   * @param state the state asked for
   */
  public void requestState(String name, int state) {
    record(name);
    if (requests != null) {
      requests.requestState(state);
    }
  }

  /** Records that a dash just ended and has the owner land it. */
  public void dashLanded() {
    record("on_stop");
    if (requests != null) {
      requests.dashLanded();
    }
  }

  /** Records that the charge just completed and tells the owner. */
  public void startCharging() {
    record("start_charging");
    if (requests != null) {
      requests.chargeCompleted();
    }
  }

  /** The match-wide movement settings the passes of this visit read. */
  public MovementGlobals globals() {
    return globals;
  }

  /** The position the entity is heading for, or null when it has none. */
  public ReferencePoint reference() {
    return reference;
  }

  /**
   * The names of the announcements this chain recorded rather than ran, in order; empty for a chain
   * built {@link #withoutMarkers()}.
   */
  public List<String> markers() {
    return markers == null ? List.of() : Collections.unmodifiableList(markers);
  }

  /** Records an announcement the chain does not act on itself. */
  public void mark(String name) {
    record(name);
  }

  /** Adds a name to the record, unless the record is off. */
  private void record(String name) {
    if (markers != null) {
      markers.add(name);
    }
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
  }

  /**
   * Asks the aligned static neighbours check over the entities within {@link
   * AlignedStaticCheck#QUERY_RADIUS} of the entity's own position, as the push pass does after it
   * has counted a static neighbour.
   */
  public int alignedStaticNeighbours() {
    List<GridEntity> near =
        neighbours.near(owner.getX(), owner.getY(), AlignedStaticCheck.QUERY_RADIUS);
    try {
      return AlignedStaticCheck.answer(owner, near);
    } finally {
      neighbours.release(near);
    }
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
    return new MovementOutcome(owner.getX(), owner.getY(), component.getWaypointReached());
  }

  /** Recomputes the route direction pair from the route's next waypoint. */
  public void directionInit() {
    DirectionInitializer.directionInit(component, owner, grid.getWidth());
    record("direction_init");
  }
}
