package org.crforge.core.pathfinding.move;

import java.util.List;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Steers a unit sideways around whatever stands in front of it, and drops a waypoint that something
 * is standing on.
 *
 * <p>The handler looks one facing vector ahead of the unit and considers the entities within the
 * unit's own collision radius, capped at 500 game units, of that point. Each neighbour it keeps is
 * either an obstacle or a blocker:
 *
 * <ul>
 *   <li>an <b>obstacle</b> is a neighbour with no active movement component - a building - or one
 *       flagged as an obstacle on purpose. When the route still holds at least two nodes and the
 *       obstacle's collision circle covers the next waypoint, that waypoint is dropped, so the unit
 *       heads for the node behind it instead of walking into the obstacle;
 *   <li>a <b>blocker</b> is a moving neighbour whose facing does not point the same way as the
 *       unit's. A neighbour that is standing, attacking, being set up as a clone or casting, or
 *       that has a special attack loaded, counts as facing nowhere and therefore always blocks. A
 *       charging unit ignores any blocker lighter than itself and runs it over instead.
 * </ul>
 *
 * <p>Every neighbour kept records which side of the unit's facing it lies on. If anything at all
 * was kept, the unit's avoidance blend - the sideways rotation the displacement applies to its
 * proposed step - is set: a blend of zero jumps straight to plus or minus 200 by the recorded side,
 * and a blend that is already non-zero moves 20 further toward the side of the last obstacle seen,
 * clamped to -200..200. A blend only ever moves by 20 when an obstacle was seen; blockers alone
 * leave a non-zero blend where it is.
 *
 * <p>The unit takes no part in any of this while it is flagged out of physical interaction or
 * flagged as an obstacle itself.
 */
public final class AvoidanceHandler {

  /** Largest radius, in game units, the neighbour query uses whatever the unit's own radius is. */
  public static final int QUERY_RADIUS_CLAMP = 500;

  /** Blend a unit with no blend yet jumps to, positive or negative by the side it chose. */
  private static final int INITIAL_BLEND = 200;

  /** How much an obstacle moves an existing blend toward its own side. */
  private static final int BLEND_STEP = 20;

  private AvoidanceHandler() {
    // Utility class
  }

  /**
   * Runs one avoidance pass.
   *
   * @param component the unit's movement component; its route may lose its next waypoint and its
   *     avoidance blend may be changed
   * @param owner the unit looking around
   * @param others the neighbours the query answered with, in the order it answered them; the unit
   *     itself may appear in the list and is skipped
   * @param queries the answers the handler pulls from the rest of the simulation, including the
   *     three per-neighbour ones it cannot read off a {@link GridEntity}
   * @param chain the chain that records what the handler announced
   */
  public static void avoidance(
      MovementState component,
      GridEntity owner,
      List<GridEntity> others,
      MovementQueries queries,
      MovementChain chain) {
    long flags = owner.getFlags();
    if ((flags & EntityFlags.DISABLE_PHYSICAL) != 0
        || (flags & EntityFlags.AVOIDANCE_AS_OBSTACLE) != 0) {
      return;
    }
    int dirX = owner.getDirX();
    int dirY = owner.getDirY();
    // The guarded sum of squares is never negative, so this test never stops the pass; it is kept
    // because the order of the reads around it is what the rest of the pass sees.
    if (FixedMath.guardedSumOfSquares(dirX, dirY) < 0) {
      return;
    }
    int ownerX = owner.getX();
    int ownerY = owner.getY();
    chain.mark("owner_radius");
    int radius = Math.min(owner.getCollisionRadius(), QUERY_RADIUS_CLAMP);
    chain.mark("owner_mass");
    int ownerMass = owner.getMass();
    int ownerHeight = owner.getZTotal();
    chain.mark("neighbour_query");

    int lastObstacleSide = 1;
    int obstacles = 0;
    int blockers = 0;
    int blockerSide = 1;
    Route route = component.getRoute();

    for (GridEntity other : others) {
      if (other == owner) {
        continue;
      }
      if ((ownerHeight > 0) == (other.getZTotal() < 1)) {
        continue;
      }
      chain.mark("neighbour_contact");
      if ((queries.neighbourAcceptsContact(other) & 1) == 0) {
        continue;
      }
      if ((other.getFlags() & EntityFlags.DISABLE_PHYSICAL) != 0) {
        continue;
      }
      // Positive when the neighbour lies to one side of the unit's facing, negative on the other.
      int cross = dirX * (ownerY - other.getY()) + dirY * (other.getX() - ownerX);

      boolean moving =
          other.isMovementActive() && (other.getFlags() & EntityFlags.AVOIDANCE_AS_OBSTACLE) == 0;
      if (moving) {
        int dot = other.getDirX() * dirX + other.getDirY() * dirY;
        chain.mark("neighbour_state_override");
        if ((other.getTargetable() & 1) != 0) {
          int state = other.getState();
          if (state == GridEntityState.CLONE_SETUP
              || state == GridEntityState.STANDING
              || state == GridEntityState.ATTACKING
              || state == GridEntityState.CASTING) {
            dot = 0;
          }
          chain.mark("neighbour_targeting");
          if (queries.neighbourSpecialLoadPending(other) != 0) {
            dot = 0;
          }
        }
        if (component.getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
          chain.mark("neighbour_mass");
          if (ownerMass > other.getMass()) {
            continue;
          }
        }
        if (dot > 0) {
          continue;
        }
        blockers++;
        int otherBlend = queries.neighbourAvoidanceBlend(other);
        blockerSide = otherBlend != 0 ? (otherBlend > 0 ? 1 : 0) : (cross < 0 ? 1 : 0);
        continue;
      }

      if (route.size() >= 2) {
        int node = route.last();
        int width = queries.gridWidth();
        int row = FixedMath.div(node, width);
        int waypointX = (node - row * width) * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
        int waypointY = row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
        int squared =
            FixedMath.guardedSumOfSquares(waypointX - other.getX(), waypointY - other.getY());
        chain.mark("neighbour_radius");
        int otherRadius = other.getCollisionRadius();
        if (squared < otherRadius * otherRadius) {
          route.pop();
        }
      }
      obstacles++;
      lastObstacleSide = cross < 0 ? 1 : 0;
    }

    int chosenSide = obstacles > 0 ? lastObstacleSide : blockerSide;
    if (obstacles + blockers <= 0) {
      chain.mark("release");
      return;
    }
    int blend = component.getAvoidanceBlend();
    if (blend == 0) {
      component.setAvoidanceBlend((chosenSide & 1) != 0 ? INITIAL_BLEND : -INITIAL_BLEND);
    } else if (obstacles >= 1) {
      blend += (lastObstacleSide & 1) != 0 ? BLEND_STEP : -BLEND_STEP;
      blend = Math.min(blend, INITIAL_BLEND);
      component.setAvoidanceBlend(Math.max(blend, -INITIAL_BLEND));
    }
    chain.mark("release");
  }
}
