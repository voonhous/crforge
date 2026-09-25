package org.crforge.core.pathfinding.move;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Sums the pushes a unit takes from the units standing next to it into its movement component.
 *
 * <p>Each neighbour within reach contributes a vector pointing away from it, whose magnitude grows
 * as the two overlap more, is capped at 300 units and is scaled by the ratio of the neighbour's
 * mass to the unit's own. The displacement then averages the accumulated vector over the count and
 * adds it to its step.
 *
 * <p>Reach is the neighbour's collision radius plus the unit's own, except that a neighbour with no
 * movement component of its own - a building or a tower - only ever reaches as far as 500 units of
 * the unit's radius. Two units sharing a position separate along the arena's length, each toward
 * its own side.
 *
 * <p>A crown tower is such a neighbour. Its mass is 0, so its share of a push is the smallest one,
 * a single unit before the per-axis division, but it still counts in the push count the
 * displacement divides by. A tower is never pushed itself: it has no movement visit.
 *
 * <p>The unit is not pushed at all when it has no collision radius, when it takes no part in
 * contact ({@link ContactRule#collides}), or when it carries the flags that take it out of physical
 * interaction entirely. Individual neighbours are skipped when they are on a different height
 * layer, take no part in contact, are not alive, or carry the flag that forbids pushing this unit
 * from their side.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: early outs, skip order, the box and"
            + " circle rejects, the coincident case, the magnitude chain and the write order."
            + " Held: two and three equal units pushing apart, and the multi-unit parity"
            + " scenes. Not held by any fixture: unequal masses, a radius above 500, the"
            + " height layers, the no-pushed-by flags, edge separation and the single-axis"
            + " copy. A crown tower as a static, massless neighbour is held by the tower-contact"
            + " run, the walks past a unit's own tower and the placement runs.")
public final class PushPass {

  /** Extra reach, in game units, the neighbour query adds to the unit's collision radius. */
  public static final int QUERY_MARGIN = 20;

  /** Largest collision radius a neighbour without a movement component reaches with. */
  private static final int STATIC_RADIUS_CLAMP = 500;

  /** Largest overlap, in game units, that contributes to one push. */
  private static final int MAX_OVERLAP = 300;

  /** Sideways nudge, in game units, applied when a building sits on the arena's edge. */
  private static final int EDGE_SEPARATION = 256;

  private PushPass() {
    // Utility class
  }

  /**
   * Accumulates this visit's pushes into the movement component.
   *
   * @param component the movement component receiving the push vector and its count
   * @param owner the entity being pushed
   * @param others the neighbours to consider, in the order the neighbour query returned them; the
   *     entity itself may appear and is skipped
   * @param queries the answers the pass pulls from the rest of the simulation
   * @param chain the chain that records what the pass announced
   */
  public static void pushPass(
      MovementState component,
      GridEntity owner,
      List<GridEntity> others,
      MovementQueries queries,
      MovementChain chain) {
    chain.mark("owner_radius");
    int radius = owner.getCollisionRadius();
    if (radius == 0) {
      return;
    }
    chain.mark("owner_collides");
    if ((ContactRule.collides(owner) & 1) == 0) {
      return;
    }
    if ((owner.getFlags() & EntityFlags.DISABLE_PHYSICAL) != 0) {
      return;
    }
    if ((owner.getFlags() & EntityFlags.AVOIDANCE_AS_OBSTACLE) != 0) {
      return;
    }
    int ownerX = owner.getX();
    int ownerY = owner.getY();
    int ownerHeight = owner.getZTotal();
    chain.mark("neighbour_query");
    chain.mark("owner_side");
    int side = owner.getSide();
    if (others.isEmpty()) {
      chain.mark("release");
      return;
    }
    int staticRadiusTerm = Math.min(radius, STATIC_RADIUS_CLAMP);
    boolean noEnemyPush = (owner.getFlags() & EntityFlags.NO_PUSHED_BY_ENEMY) != 0;
    boolean noAllyPush = (owner.getFlags() & EntityFlags.NO_PUSHED_BY_ALLY) != 0;
    int staticNeighbours = 0;

    for (GridEntity other : others) {
      if (other == owner) {
        continue;
      }
      if ((ownerHeight > 0) == (other.getZTotal() < 1)) {
        continue;
      }
      chain.mark("neighbour_collides");
      if ((ContactRule.collides(other) & 1) == 0) {
        continue;
      }
      if ((other.getFlags() & EntityFlags.DISABLE_PHYSICAL) != 0) {
        continue;
      }
      chain.mark("neighbour_alive");
      if (!other.isAlive()) {
        continue;
      }
      chain.mark("neighbour_mass");
      int otherMass = other.getMass();
      chain.mark("neighbour_side");
      long mask;
      if (other.getSide() != side) {
        if (noEnemyPush) {
          continue;
        }
        mask = EntityFlags.NO_PUSHED_BY_ENEMY;
      } else {
        if (noAllyPush) {
          continue;
        }
        mask = EntityFlags.NO_PUSHED_BY_ALLY;
      }
      int massTerm = (other.getFlags() & mask) != 0 ? -1 : otherMass;
      boolean neighbourMoves = other.isMovementActive();
      int radiusTerm = neighbourMoves ? radius : staticRadiusTerm;
      if (!neighbourMoves) {
        staticNeighbours++;
      }
      chain.mark("neighbour_radius");
      int otherRadius = other.getCollisionRadius();
      int otherX = other.getX();
      int otherY = other.getY();
      chain.mark("touchdown_mode");
      if ((queries.touchdownModeActive() & 1) != 0
          && !neighbourMoves
          && queries.touchdownEdgeSeparate() != 0) {
        int otherCellX = FixedMath.divOrZero(otherX, TileMap.CELL_UNITS);
        if (otherCellX == 0) {
          component.setPushX(component.getPushX() + EDGE_SEPARATION);
          component.setPushCount(component.getPushCount() + 1);
        }
        if (otherCellX == queries.gridWidth() - 1) {
          component.setPushX(component.getPushX() - EDGE_SEPARATION);
          component.setPushCount(component.getPushCount() + 1);
        }
      }
      int dx = ownerX - otherX;
      int dy = ownerY - otherY;
      int reach = otherRadius + radiusTerm;
      if (Math.abs(dx) > reach || Math.abs(dy) > reach) {
        continue;
      }
      int squared = dx * dx + dy * dy;
      if (squared == 0) {
        chain.mark("owner_side_zero");
        dy = (queries.ownerSideZero() & 1) != 0 ? -1 : 1;
        squared = 1;
      }
      if (squared > reach * reach) {
        continue;
      }
      int distance = FixedMath.isqrt(squared);
      int safeDistance = Math.max(distance, 1);
      chain.mark("owner_mass");
      int ownMass = owner.getMass();
      int magnitude = Math.max(Math.min(reach - distance, MAX_OVERLAP), 0);
      if (massTerm != -1) {
        magnitude = FixedMath.divOrZero(magnitude * massTerm, ownMass);
      }
      magnitude = Math.min(magnitude, MAX_OVERLAP - 1) + 1;
      component.setPushY(component.getPushY() + FixedMath.divOrZero(magnitude * dy, safeDistance));
      component.setPushCount(component.getPushCount() + 1);
      component.setPushX(component.getPushX() + FixedMath.divOrZero(magnitude * dx, safeDistance));
    }

    chain.mark("release");
    if (staticNeighbours > 0) {
      chain.mark("single_axis_push_copy");
      if ((queries.singleAxisPushCopyAllowed() & 1) != 0) {
        int pushX = component.getPushX();
        int pushY = component.getPushY();
        if (pushX != 0 && pushY != 0) {
          return;
        }
        if (pushX != 0) {
          component.setPushY(pushX);
        } else {
          component.setPushX(pushY);
        }
      }
    }
  }
}
