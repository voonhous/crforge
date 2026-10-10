/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.RollingProjectile;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One run of a roll on its projectile: the destination its start takes, the ids of what it has
 * buffed, and how far it has rolled. See {@link RollingProjectile}.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the destination with the distance across negated in the far half"
            + " of the width and the distance forward negated for side 1, walked back 100 at a"
            + " time while blocked; each step's query, tested by the square for a building, and"
            + " its buff once per id; the step of speed times the offset over the unguarded"
            + " distance, truncated, and the arrival's put, last buff, release and finish; held"
            + " by the reference battle evo_snowball_on_musketeer, the side and the walk back by"
            + " BattleSnowballEvoTest. Held by no run: the distance across, the arrival's last"
            + " buff on what the step before missed, and the square test of a building. Refused:"
            + " a deflection, which the run's deflection pass and a deflected roll would need.")
final class RollingRun extends ActionInstance {

  /** How far each walk back from a blocked destination goes. */
  private static final int WALK_BACK = 100;

  private final RollingProjectile.Columns columns;
  private final ProjectileEntity projectile;

  /** The ids of what the run has buffed, which it does not buff again. */
  private final List<Integer> hitIds = new ArrayList<>();

  private int destinationX;
  private int destinationY;

  /**
   * The start: the deflection pass at the projectile's point, then the destination.
   *
   * @param row the roll
   * @param projectile the projectile it rolls
   */
  RollingRun(RollingProjectile row, ProjectileEntity projectile) {
    super(row);
    this.columns = row.getColumns();
    this.projectile = projectile;
    projectile.world().rollDeflectionPass(projectile);
    destination(columns.distanceX(), columns.distanceY());
  }

  /** Where the roll ends, along the width. */
  int destinationX() {
    return destinationX;
  }

  /** Where the roll ends, along the length. */
  int destinationY() {
    return destinationY;
  }

  /**
   * The destination: the distance across, negated when the projectile stands in the far half of the
   * width, so it rolls toward the middle, and the distance forward, negated for side 1. From that
   * point the first, walking back toward the projectile 100 at a time, that is not blocked is the
   * destination; with none within the distance it is the projectile's own point.
   */
  private void destination(int dx, int dy) {
    int x = projectile.getX();
    int y = projectile.getY();
    int width = projectile.world().getTileMap().width();
    int half = (width + (width < 0 ? 1 : 0)) >> 1;
    int tx = (x / TileMap.CELL_UNITS >= half ? -dx : dx) + x;
    int ty = ((projectile.side() & 1) != 0 ? -dy : dy) + y;
    int[] step = {tx - x, ty - y};
    int distance = FixedMath.normalize(step, WALK_BACK);
    destinationX = x;
    destinationY = y;
    if (distance < 0) {
      return;
    }
    int walked = 0;
    while (true) {
      if (!projectile.world().rollBlocked(tx, ty)) {
        destinationX = tx;
        destinationY = ty;
        return;
      }
      walked += WALK_BACK;
      tx -= step[0];
      ty -= step[1];
      if (walked > distance) {
        return;
      }
    }
  }

  @Override
  protected void update(ActionHolder holder) {
    hitPass();
    int x = projectile.getX();
    int y = projectile.getY();
    int dx = destinationX - x;
    int dy = destinationY - y;
    // The distance left is the plain root of the wrapped sum, not the guarded one.
    int sum = dx * dx + dy * dy;
    int distance = sum < 0 ? 0 : FixedMath.isqrt(sum);
    if (columns.speed() < distance) {
      int sx = columns.speed() * dx / distance;
      int sy = columns.speed() * dy / distance;
      projectile.moveTo(x + sx, y + sy, projectile.getZ());
      projectile.world().rolled(projectile, false);
      projectile.world().rollDeflectionPass(projectile);
      return;
    }
    projectile.moveTo(destinationX, destinationY, projectile.getZ());
    hitPass();
    // Released with no impact: it leaves at this tick's cleanup.
    projectile.release();
    projectile.world().rolled(projectile, true);
    finish();
  }

  /** Buffs, once per id, what the query finds around the projectile, in the query's order. */
  private void hitPass() {
    for (WorldEntity found :
        projectile.world().rollQuery(projectile, columns.radius(), columns.targetFilter())) {
      if (hitIds.contains(found.getId())) {
        continue;
      }
      hitIds.add(found.getId());
      projectile.world().rollBuff(projectile, found, columns.buffOnHit(), columns.buffTimeMs());
    }
  }
}
