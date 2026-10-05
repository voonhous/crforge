package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.state.StateSetter;

/**
 * Puts an entity into a dash toward a point, as its targeting visit does when its dash wind-up runs
 * out.
 *
 * <p>In order: an entity under the no-dash flag does nothing. With a movement component the dash
 * target is the point pulled back toward the entity until the entity's collision radius plus the
 * given radius separate them - or the entity's own position, when it is already closer - clamped
 * into the arena, and the route becomes that target's single cell. The entity then faces the point,
 * unless it stands on the point's row or column, and asks for the dashing state, whose entry resets
 * its charge, starts the dash timer and clears its landing hold; the dashing flag is raised once
 * more after that.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line for a dash with no fixed distance, no chained"
            + " dash and no contact damage; held by bandit_knight and mega_knight_group. Not"
            + " carried: the chained dash's counter, first direction and hit list, a fixed dash"
            + " distance and the hit list a contact-damage dash empties, whose columns are refused"
            + " with their rows.")
public final class DashStart {

  private DashStart() {
    // Utility class
  }

  /**
   * Starts a dash.
   *
   * @param owner the dashing entity, whose facing and pending flags this writes
   * @param movement its movement component, whose route this sets, or null when it has none
   * @param config its movement columns, of which the jump height is read
   * @param x the point dashed toward, along the arena's width
   * @param y the point dashed toward, along the arena's length
   * @param radius the radius the dash stops short of the point by, beyond the entity's own
   * @param stopsInRange 1 when the dash may stop as its reference comes into range
   * @param width the grid's width in cells
   * @param height the grid's height in cells
   * @param setter the entity's state setter, which applies the dashing state
   */
  public static void start(
      GridEntity owner,
      MovementState movement,
      MovementConfig config,
      int x,
      int y,
      int radius,
      int stopsInRange,
      int width,
      int height,
      StateSetter setter) {
    if ((owner.getFlags() & owner.getFlagBits().noDash()) != 0) {
      return;
    }
    if (movement != null) {
      int[] back = {owner.getX() - x, owner.getY() - y};
      int reach = owner.getCollisionRadius() + radius;
      if (FixedMath.guardedDistance(back[0], back[1]) > reach) {
        FixedMath.normalize(back, reach);
      }
      int targetX = clamp(back[0] + x, width * TileMap.CELL_UNITS - 1);
      int targetY = clamp(back[1] + y, height * TileMap.CELL_UNITS - 1);
      SingleNodeRoute.set(
          movement, owner, config, targetX, targetY, stopsInRange & 1, width, height);
    }
    if (owner.getX() != x && owner.getY() != y) {
      int[] facing = {x - owner.getX(), y - owner.getY()};
      FixedMath.normalize(facing, MovementState.DIRECTION_SCALE);
      owner.setDirX(facing[0]);
      owner.setDirY(facing[1]);
    }
    setter.setState(owner, GridEntityState.DASHING);
    owner.setPendingFlags(owner.getPendingFlags() | owner.getFlagBits().dashing());
  }

  /** One axis clamped into the arena: a coordinate at or below 0 gives 0. */
  private static int clamp(int value, int top) {
    return value > 0 ? Math.min(value, top) : 0;
  }
}
