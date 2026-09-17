package org.crforge.core.pathfinding.move;

import java.util.List;
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
 * movement component of its own - a building - only ever reaches as far as 500 units of the unit's
 * radius. Two units sharing a position separate along the arena's length, each toward its own side.
 *
 * <p>The unit is not pushed at all when it has no collision radius, when pushing is disabled for
 * it, or when it carries the flags that take it out of physical interaction entirely. Individual
 * neighbours are skipped when they are on a different height layer, are not alive, or carry the
 * flag that forbids pushing this unit from their side.
 */
public final class PushPass {

  /** Extra reach, in game units, the neighbour query adds to the unit's collision radius. */
  private static final int QUERY_MARGIN = 20;

  /** Largest collision radius a neighbour without a movement component reaches with. */
  private static final int STATIC_RADIUS_CLAMP = 500;

  /** Largest overlap, in game units, that contributes to one push. */
  private static final int MAX_OVERLAP = 300;

  /** Sideways nudge, in game units, applied when a building sits on the arena's edge. */
  private static final int EDGE_SEPARATION = 256;

  /** The entity takes no part in physical interaction with other objects. */
  private static final long DISABLE_PHYSICAL = 1L << 47;

  /** The entity is treated as an obstacle to steer around rather than a body to push. */
  private static final long AVOIDANCE_AS_OBSTACLE = 1L << 54;

  /** The entity may not be pushed by the other side. */
  private static final long NO_PUSHED_BY_ENEMY = 1L << 17;

  /** The entity may not be pushed by its own side. */
  private static final long NO_PUSHED_BY_ALLY = 1L << 53;

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
    chain.mark("owner_push_enabled");
    if (!queries.ownerPushEnabled()) {
      return;
    }
    if ((owner.getFlags() & DISABLE_PHYSICAL) != 0) {
      return;
    }
    if ((owner.getFlags() & AVOIDANCE_AS_OBSTACLE) != 0) {
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
    boolean noEnemyPush = (owner.getFlags() & NO_PUSHED_BY_ENEMY) != 0;
    boolean noAllyPush = (owner.getFlags() & NO_PUSHED_BY_ALLY) != 0;
    int staticNeighbours = 0;

    for (GridEntity other : others) {
      if (other == owner) {
        continue;
      }
      if ((ownerHeight > 0) == (other.getZTotal() < 1)) {
        continue;
      }
      chain.mark("neighbour_push_enabled");
      if (!other.isSlotE0()) {
        continue;
      }
      if ((other.getFlags() & DISABLE_PHYSICAL) != 0) {
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
        mask = NO_PUSHED_BY_ENEMY;
      } else {
        if (noAllyPush) {
          continue;
        }
        mask = NO_PUSHED_BY_ALLY;
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
