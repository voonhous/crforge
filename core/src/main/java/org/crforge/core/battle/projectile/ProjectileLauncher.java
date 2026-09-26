package org.crforge.core.battle.projectile;

import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * The hit application's projectile path: how many projectiles one hit launches, where each starts
 * and what it is aimed at, and the hand-over to the holder.
 *
 * <p>One hit launches as many projectiles as the unit's column says, at least one. The first is
 * aimed at where the reference stood at the start of the visit; every further one is aimed at a
 * point of the spread around it, rotated by an equal share of the circle. Each starts the unit's
 * start radius along the line from the unit to its aim, shifted by the unit's height offset and its
 * lengthwise offset, which the top side mirrors. A hit of a burst or a multi-target attack fans the
 * starts out sideways. Every projectile has its id from this tick and enters the holder's live list
 * at the tick's closing cleanup.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the count, the spread and the angle base, the start from the launch columns,"
            + " the aim at the stored reference position, the level and the hand-over in the attack"
            + " tick. Held by the Musketeer run's launch ticks and start positions. Supplied, not"
            + " settled: the draw that places every projectile after the first within the spread"
            + " answers zero, and the start radius and height are the unit's columns without an"
            + " attack sequence step's override. Not modelled: the first-projectile and special"
            + " projectile columns, the projectile a buff substitutes, a building target's edge"
            + " adjustment, a burst that keeps its aim and the fan of a scattering first"
            + " projectile. The pushback a launch gives its owner is asked for after each launch.")
public final class ProjectileLauncher {

  private ProjectileLauncher() {
    // Utility class
  }

  /**
   * Launches the projectiles of one hit.
   *
   * @param launcher the unit whose hit this is
   * @param t the launcher's targeting component
   * @param target what the hit is aimed at, or null when the launcher has given it up
   * @param sequenceIndex which hit of the attack this is, -1 for a single-target attack
   * @param world the battle, whose holder the projectiles are handed to
   */
  public static void launch(
      WorldEntity launcher,
      TargetingState t,
      TargetView target,
      int sequenceIndex,
      BattleWorld world) {
    UnitData unit = launcher.getData();
    ProjectileData data = unit.projectile();
    if (data == null) {
      return;
    }
    WorldEntity targetEntity = target == null ? null : world.entityOf(target.getEntity());
    int count = Math.max(unit.multipleProjectiles(), 1);
    int spread = unit.areaDamageRadius();
    int step = 360 / count;
    int quarter = spread >> 2;
    int angleBase = sequenceIndex == -1 ? 0 : sequenceIndex * 90 - 45;
    int half = count >>> 1;
    for (int k = 0; k < count; k++) {
      int[] offset = {0, 0};
      if (k != 0) {
        // The standard game draws a distance between a quarter of the spread and the spread; the
        // draw is supplied as zero here, so every further projectile starts a quarter out.
        offset[0] = quarter;
        rotate(offset, step * k);
      }
      ProjectileEntity projectile = new ProjectileEntity(world, data, launcher.side());
      int hx = t.getLastReferenceX() + offset[0];
      int hy = t.getLastReferenceY() + offset[1];
      launchOne(projectile, launcher, unit, targetEntity, hx, hy, angleBase, k + half);
      world.launch(projectile);
      launcher.launched(hx, hy);
    }
  }

  /**
   * Places one projectile at its start and hands it to its own launch: the start is the launch
   * radius along the line from the launcher to the hit position, or to the target's centre for a
   * colliding projectile of a multi-projectile attacker, fanned sideways for a hit with an angle
   * base, then shifted by the launcher's lengthwise and height offsets.
   */
  private static void launchOne(
      ProjectileEntity projectile,
      WorldEntity launcher,
      UnitData unit,
      WorldEntity target,
      int hx,
      int hy,
      int angleBase,
      int fan) {
    GridEntity view = launcher.getView();
    int ox = view.getX();
    int oy = view.getY();
    int radius = unit.projectileStartRadius();
    int dx = hx - ox;
    int dy = hy - oy;
    if (unit.multipleProjectiles() >= 1
        && target != null
        && projectile.getData().checkCollisions()) {
      dx = target.getView().getX() - ox;
      dy = target.getView().getY() - oy;
    }
    int length = FixedMath.isqrt(dx * dx + dy * dy);
    if (length != 0) {
      dx = FixedMath.div(dx * radius, length);
      dy = FixedMath.div(dy * radius, length);
    }
    if (angleBase != 0) {
      int a = angleBase < 0 ? -dy : dy;
      int b = angleBase >= 0 ? -dx : dx;
      dx += halfTowardZero(a);
      dy += halfTowardZero(b);
    }
    int yOffset = unit.projectileYOffset();
    if (view.getSide() != 0) {
      yOffset = -yOffset;
    }
    int sx = ox + dx;
    int sy = yOffset + dy + oy;
    int sz = unit.projectileStartZ() + view.getZ();
    projectile.launch(launcher, target, sx, sy, sz, hx, hy);
  }

  /** Half of a value, rounded toward zero. */
  private static int halfTowardZero(int value) {
    return (value + (value < 0 ? 1 : 0)) >> 1;
  }

  /** Rotates a vector by whole degrees with the 1024-scaled sine table, truncating toward zero. */
  static void rotate(int[] vec, int degrees) {
    int c = FixedMath.sine1024(degrees + 90);
    int s = FixedMath.sine1024(degrees);
    int nx = c * vec[0] - s * vec[1];
    int ny = s * vec[0] + c * vec[1];
    vec[0] = (nx + (nx < 0 ? 1023 : 0)) >> 10;
    vec[1] = (ny + (ny < 0 ? 1023 : 0)) >> 10;
  }
}
