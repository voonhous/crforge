package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One step of an entity toward a point: how far it goes, which way it ends up facing, and whether
 * it counts as having arrived.
 *
 * <p>The order of operations is fixed and several steps depend on it:
 *
 * <ol>
 *   <li>The step is the smallest of the budget, the remaining distance and 250 units.
 *   <li>The proposed offset is the direction to the target scaled by 256, truncated, multiplied by
 *       the step and divided back down by 256.
 *   <li>The facing follows the direction to the target, unless the facing gate is shut, a pushback
 *       is in flight or the target sits exactly on the entity.
 *   <li>An avoidance blend rotates the proposal sideways and renormalizes it to the step.
 *   <li>The accumulated push is averaged, clamped to 150 units unless left unclamped, added to the
 *       proposal, and then cleared.
 *   <li>The proposal goes through the cell-edge clamp of {@link GridMove}.
 *   <li>Arrival is the remaining distance projected on the route direction, compared with 1000
 *       units or, in the two pathfind states, with that state's own speed.
 * </ol>
 *
 * <p>Two divisions by 256 that look alike are not: the blend rotation shifts right, which rounds
 * down for a negative value, while the proposal and the arrival projection divide, which truncates
 * toward zero. Both forms occur here and neither may be rewritten as the other.
 *
 * <p>The arrival threshold of 1000 units is a full cell and a half of the entity's own step, so a
 * unit walking a route drops its next waypoint well before it reaches the cell centre and
 * effectively aims two nodes ahead. That is what makes a unit clip the corner of the cell it enters
 * a bridge through.
 */
public final class Displacement {

  /** Largest distance one displacement may cover, in game units. */
  public static final int MAX_STEP = 250;

  /** Scale the direction to the target is expressed in before it is multiplied by the step. */
  private static final int DIRECTION_SCALE = 256;

  /** Arrival threshold outside the two pathfind states, in game units. */
  private static final int REACHED_THRESHOLD = 1000;

  /**
   * Length an averaged push vector is clamped to once it grows past {@link #PUSH_CLAMP_SQUARED}.
   */
  private static final int PUSH_CLAMP_LENGTH = 150;

  /**
   * Squared length above which the averaged push vector is clamped, which is 150 squared plus one.
   */
  private static final int PUSH_CLAMP_SQUARED = 22501;

  /** Sideways nudge, in game units, a pushed ground unit standing on water takes off the river. */
  private static final int RIVER_NUDGE = 256;

  private Displacement() {
    // Utility class
  }

  /**
   * Moves the entity one step toward a point and returns the distance the step was allowed to be.
   *
   * @param component the entity's movement component, whose work vector, push accumulators, arrival
   *     bit and charge progress this writes
   * @param owner the entity, whose position, facing and pending flags this writes
   * @param grid the arena's routing grid
   * @param config the entity's movement configuration columns
   * @param globals the match-wide movement settings
   * @param queries the answers the displacement pulls from the rest of the simulation
   * @param chain the chain that records what the displacement announced
   * @param targetX destination along the arena's width, in game units
   * @param targetY destination along the arena's length, in game units
   * @param budget the largest step allowed, in game units
   * @param updateFacing 1 when the entity may turn to face its direction of travel
   * @param attackFlag 1 when the step is an attack pushback, which suppresses the charge build-up
   * @return the step size used, in game units
   */
  public static int displace(
      MovementState component,
      GridEntity owner,
      CellGrid grid,
      MovementConfig config,
      MovementGlobals globals,
      MovementQueries queries,
      MovementChain chain,
      int targetX,
      int targetY,
      int budget,
      int updateFacing,
      int attackFlag) {
    int startX = owner.getX();
    int startY = owner.getY();
    int distance = FixedMath.guardedDistance(targetX - startX, targetY - startY);
    int safeDistance = Math.max(distance, 1);
    int step = Math.min(Math.min(budget, safeDistance), MAX_STEP);
    int deltaX = targetX - startX;
    int deltaY = targetY - startY;
    int scaledX = FixedMath.divOrZero(deltaX << 8, safeDistance) * step;
    int scaledY = FixedMath.divOrZero(deltaY << 8, safeDistance) * step;

    chain.mark("modifier_component");
    boolean skipFacing = false;
    if (queries.hasModifierComponent()) {
      chain.mark("modifier_component");
      skipFacing = (queries.facingUpdateSuppressed() & 1) != 0;
    }
    if (!skipFacing && component.getPushbackInFlight() == 0 && (updateFacing & 1) != 0) {
      int oldDirX = owner.getDirX();
      int oldDirY = owner.getDirY();
      int[] facing = {deltaX, deltaY};
      if (FixedMath.normalize(facing, DIRECTION_SCALE) == 0) {
        owner.setDirX(oldDirX);
        owner.setDirY(oldDirY);
      } else {
        owner.setDirX(facing[0]);
        owner.setDirY(facing[1]);
      }
    }

    boolean groundPushed = false;
    if (component.getPushCount() > 0 || component.getAvoidanceBlend() >= 1) {
      chain.mark("push_displacement_enabled");
      if ((queries.pushDisplacementEnabled() & 1) != 0) {
        chain.mark("air");
        if ((queries.air() & 1) == 0) {
          chain.mark("hovering");
          groundPushed = (queries.hovering() & 1) == 0;
        }
      }
    }

    // Truncating division, not a shift: the proposal rounds toward zero on both axes.
    int[] proposal = {
      FixedMath.divOrZero(scaledX, DIRECTION_SCALE), FixedMath.divOrZero(scaledY, DIRECTION_SCALE)
    };
    if (component.getAvoidanceBlend() != 0) {
      int blend =
          Math.max(Math.min(component.getAvoidanceBlend(), DIRECTION_SCALE), -DIRECTION_SCALE);
      int forward = DIRECTION_SCALE - Math.abs(blend);
      // Arithmetic shifts, not divisions: a negative product rounds down here.
      component.setWorkVector(
          ((forward * proposal[0]) >> 8) + ((blend * proposal[1]) >> 8),
          ((forward * proposal[1]) >> 8) + ((-(proposal[0] * blend)) >> 8));
      FixedMath.normalize(component.getWorkVector(), step);
      proposal[0] = component.getWorkVector()[0];
      proposal[1] = component.getWorkVector()[1];
    }

    int stuck = 0;
    if (component.getPushCount() >= 1) {
      int count = component.getPushCount();
      component.setWorkVector(
          FixedMath.divOrZero(component.getPushX(), count),
          FixedMath.divOrZero(component.getPushY(), count));
      if (component.getPushUnclamped() == 0
          && FixedMath.guardedSumOfSquares(
                  component.getWorkVector()[0], component.getWorkVector()[1])
              >= PUSH_CLAMP_SQUARED) {
        FixedMath.normalize(component.getWorkVector(), PUSH_CLAMP_LENGTH);
      }
      stuck = component.getPushStuck() != 0 ? 1 : 0;
      proposal[0] += component.getWorkVector()[0];
      proposal[1] += component.getWorkVector()[1];
      component.clearPush();
    }

    if (stuck == 0 && groundPushed) {
      int cellX = FixedMath.divOrZero(startX, TileMap.CELL_UNITS);
      int cellY = FixedMath.divOrZero(startY, TileMap.CELL_UNITS);
      boolean inside =
          cellX >= 0 && cellX < grid.getWidth() && cellY >= 0 && cellY < grid.getHeight();
      stuck = inside && (grid.water(cellX, cellY) & 1) == 0 ? 0 : 1;
    }
    if (groundPushed
        && startX >= 0
        && startY >= 0
        && startX < grid.getWidth() * TileMap.CELL_UNITS
        && startY < grid.getHeight() * TileMap.CELL_UNITS
        && (grid.water(startX / TileMap.CELL_UNITS, startY / TileMap.CELL_UNITS) & 1) != 0) {
      int middle = grid.getHeight() * (TileMap.CELL_UNITS / 2);
      proposal[1] += startY >= middle ? RIVER_NUDGE : -RIVER_NUDGE;
    }

    int[] position = {startX, startY};
    GridMove.gridMove(grid, position, proposal[0], proposal[1], queries.entityView(), stuck & 1);
    owner.setX(position[0]);
    owner.setY(position[1]);

    int remainingX = targetX - owner.getX();
    int remainingY = targetY - owner.getY();
    // Truncating division, not a shift.
    int projection =
        FixedMath.divOrZero(component.getRouteDirX() * remainingX, DIRECTION_SCALE)
            + FixedMath.divOrZero(component.getRouteDirY() * remainingY, DIRECTION_SCALE);
    int threshold = REACHED_THRESHOLD;
    if (globals.spawnPathfindReachedRadiusFromSpeed()) {
      int state = owner.getState();
      if (state == GridEntityState.SPAWN_PATHFIND) {
        threshold = config.spawnPathfindSpeed();
      } else if (state == GridEntityState.INGAME_PATHFIND) {
        threshold = config.ingamePathfindSpeed();
      }
    }
    component.setWaypointReached(projection <= threshold ? 1 : 0);

    if (component.getChargeProgress() == MovementState.CHARGE_INACTIVE) {
      return step;
    }
    ChargeBookkeeping.postMove(component, owner, config, queries, chain, step, attackFlag & 1);
    return step;
  }

  /** Sets the charging flag on the entity once its charge progress is complete. */
  static void markCharging(GridEntity owner) {
    owner.setPendingFlags(owner.getPendingFlags() | EntityFlags.CHARGING);
  }
}
