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
 * <p>One hit launches as many projectiles as the unit's column says, at least one; the first is the
 * unit's custom first projectile when it has one. The first is aimed at where the reference stood
 * at the start of the visit; every further one is aimed at a point of the spread around it: a
 * battle random distance between a quarter of the spread and the spread, rotated by an equal share
 * of the circle. Under a custom first projectile without a radius, its scatter decides instead: the
 * circle turns that point by a battle random share of the circle, and the line throws the point
 * away and fans the further projectiles about the line from the unit to its target, alternately to
 * one side and the other, each step turned by the spread over the count in degrees. Each starts the
 * unit's start radius along the line from the unit to its aim, shifted by the unit's height offset
 * and its lengthwise offset, which the top side mirrors. A hit of a burst or a multi-target attack
 * fans the starts out sideways. Every projectile has its id from this tick and enters the holder's
 * live list at the tick's closing cleanup.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the count, the spread and the angle base, the start from the launch columns,"
            + " the aim at the stored reference position, the level and the hand-over in the attack"
            + " tick. Held by the Musketeer run's launch ticks and start positions; the custom first"
            + " projectile, the battle random draw of each further projectile and the line's fan by"
            + " hunter_point_blank and hunter_range. Held by no run: the circle's turn. Supplied, not"
            + " settled: the start radius and height are the unit's columns without an"
            + " attack sequence step's override. Not modelled: the special projectile"
            + " column, the projectile a buff substitutes, a building target's edge"
            + " adjustment, a burst that keeps its aim, and a line's fan without a target, which is"
            + " refused. The pushback a launch gives its owner is asked for after each launch.")
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
   * @param special true for a special hit, which fires the special projectile when there is one
   * @param world the battle, whose holder the projectiles are handed to
   */
  public static void launch(
      WorldEntity launcher,
      TargetingState t,
      TargetView target,
      int sequenceIndex,
      boolean special,
      BattleWorld world) {
    UnitData unit = launcher.getData();
    // The special projectile for a special hit; otherwise the attack sequence's entry at the index
    // in place of the row's, for a sequence of two or more.
    ProjectileData regular =
        special && unit.projectileSpecial() != null
            ? unit.projectileSpecial()
            : launcher.attackProjectile();
    if (regular == null) {
      return;
    }
    ProjectileData first = unit.customFirstProjectile();
    refuseUnmodelled(launcher, regular);
    if (first != null) {
      refuseUnmodelled(launcher, first);
    }
    WorldEntity targetEntity = target == null ? null : world.entityOf(target.getEntity());
    int count = Math.max(unit.multipleProjectiles(), 1);
    int spread = unit.areaDamageRadius();
    int step = 360 / count;
    int quarter = spread >> 2;
    int angleBase = sequenceIndex == -1 ? 0 : sequenceIndex * 90 - 45;
    int half = count >>> 1;
    for (int k = 0; k < count; k++) {
      ProjectileData data = first != null && k == 0 ? first : regular;
      int[] offset = {0, 0};
      int fan = 0;
      if (k != 0) {
        // A distance between a quarter of the spread and the spread, drawn from the battle's
        // random source, turned by this projectile's share of the circle.
        offset[0] = world.getRandom().next(spread - quarter) + quarter;
        FixedMath.rotate1024(offset, step * k);
        // A custom first projectile without a radius scatters the further ones by its pattern.
        if (first != null && first.radius() == 0) {
          if (first.circleScatter()) {
            FixedMath.rotate1024(offset, world.getRandom().next(step));
          } else if (first.lineScatter()) {
            fan = (k & 1) != 0 ? (k + 1) >> 1 : -((k + 1) >> 1);
            offset = lineOffset(launcher, targetEntity, fan * spread / count);
          }
        }
      }
      ProjectileEntity projectile = new ProjectileEntity(world, data, launcher.side());
      int hx = t.getLastReferenceX() + offset[0];
      int hy = t.getLastReferenceY() + offset[1];
      launchOne(projectile, launcher, unit, targetEntity, hx, hy, angleBase, fan + half);
      world.launch(projectile);
      launcher.launched(hx, hy);
    }
  }

  /**
   * Places a collector's projectile at its start and launches it at a friend: the same start as a
   * hit's single projectile, aimed at where the friend stands now.
   *
   * @param projectile the projectile, not yet launched
   * @param launcher the collecting unit
   * @param friend the friend it flies to
   */
  public static void launchAt(
      ProjectileEntity projectile, WorldEntity launcher, WorldEntity friend) {
    refuseUnmodelled(launcher, projectile.getData());
    GridEntity at = friend.getView();
    launchOne(projectile, launcher, launcher.getData(), friend, at.getX(), at.getY(), 0, 0);
  }

  /** Refuses a projectile row that sets columns its flight and impact do not model. */
  private static void refuseUnmodelled(WorldEntity launcher, ProjectileData data) {
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          launcher.name()
              + " fires "
              + data.name()
              + ", which sets columns its flight and impact do not model: "
              + data.unmodelledColumns());
    }
  }

  /**
   * A line's fan step, as the offset from the stored reference position: the line from the launcher
   * to its target where it stands now, turned by the step's angle, from the launcher, less the
   * target's position. With the reference where it was stored, the aim is the turned line's end.
   */
  private static int[] lineOffset(WorldEntity launcher, WorldEntity target, int degrees) {
    if (target == null) {
      // Without a target the line runs along the launcher's facing and moves the stored
      // reference position, which is not modelled.
      throw new UnsupportedOperationException(
          launcher.name() + " fans its projectiles without a target, not modelled");
    }
    GridEntity own = launcher.getView();
    GridEntity aimed = target.getView();
    int[] vec = {aimed.getX() - own.getX(), aimed.getY() - own.getY()};
    FixedMath.rotate1024(vec, degrees);
    return new int[] {vec[0] + own.getX() - aimed.getX(), vec[1] + own.getY() - aimed.getY()};
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
}
