package org.crforge.core.battle.projectile;

import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * One step of a projectile's flight, and the arrival that ends it.
 *
 * <p>Each step: a limited-time homing projectile re-aims at its homing target and counts its time
 * down; a homing projectile pins its aim onto its target's position; then the distance left to the
 * aim is measured. When it is no more than the projectile's speed, the projectile arrives: it is
 * placed at its aim, released, and its impact runs. Otherwise it moves its speed along the line to
 * the aim, and takes the height the arc gives at the new point: a straight interpolation from the
 * start height to the aim height plus a gravity parabola over the flight's time, where time is
 * distance from the start over speed.
 *
 * <p>The impact of a projectile that hits one target: the row's damage at the projectile's level,
 * or its crown-tower share for a crown tower, dealt once to a target that still has hit points,
 * from the direction of the flight. The damage carries a fresh hit id but no dedupe id, so a second
 * projectile lands on the same target again.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the countdowns' order, the homing re-aim, the remaining distance, the arrival"
            + " test against the speed, the advance along the line with the arc height, the"
            + " release and the placement at the aim on arrival, the single impact with the"
            + " crown-tower choice, and that a projectile whose target left lands on nothing. Held"
            + " by every projectile position and impact of the Musketeer run. Supplied, not"
            + " settled: the deflection pass answers nothing, the projectile's own radius is zero."
            + " Not modelled: the area impact, the hits along a flying body's path, the height"
            + " toward a moving target under the z-distance column, the delays, the pingpong"
            + " sweep, the ring, the drag-back hook, the hit effects and the on-impact spawns.")
final class ProjectileFlight {

  private ProjectileFlight() {
    // Utility class
  }

  /** Runs one flight step for the projectile. */
  static void fly(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    // The first step would spawn a following area effect and run the initial collision check,
    // neither of which any row carried here has.
    p.takeFirstVisit();
    if (p.getHomingTimeMs() >= 1) {
      if (p.getHomingTarget() == null) {
        p.setHomingTimeMs(0);
      } else if (p.getHomingTarget().getView().getState() == GridEntityState.INGAME_PATHFIND) {
        p.forgetHomingTarget();
      } else {
        GridEntity homing = p.getHomingTarget().getView();
        p.aim(p.getStartX(), p.getStartY(), homing.getX(), homing.getY());
        p.setHomingTimeMs(p.getHomingTimeMs() - ProjectileEntity.STEP_MS);
      }
    }
    // The random and angular delays would hold the projectile here; no row carried here has one.
    snapToTarget(p);
    int remaining = FixedMath.guardedDistance(p.getX() - p.getAimX(), p.getY() - p.getAimY());
    int speed = data.speed();
    if (remaining <= speed) {
      arrive(p, world);
    } else {
      advance(p, remaining, speed);
    }
  }

  /** A homing projectile with a target pins its aim onto the target's position and height. */
  private static void snapToTarget(ProjectileEntity p) {
    TargetView target = p.targetView();
    if (target == null || !p.getData().homing()) {
      return;
    }
    // The aim height is the target's height plus half the projectile's own collision radius,
    // which is zero for every row carried here.
    p.setAim(target.x(), target.y(), target.z());
  }

  /** One step of the speed along the line to the aim, at the height the arc gives there. */
  private static void advance(ProjectileEntity p, int remaining, int speed) {
    int x = p.getX();
    int y = p.getY();
    int nx = FixedMath.divOrZero((p.getAimX() - x) * speed, remaining) + x;
    int ny = FixedMath.divOrZero((p.getAimY() - y) * speed, remaining) + y;
    p.moveTo(nx, ny, arcHeight(p, nx, ny));
    // A flying body would now hit what it passes; anything else asks the deflection pass, which
    // finds nothing in a battle without deflecting area effects.
  }

  /**
   * The height of the flight at a point: the start height plus the share of the climb to the aim
   * height that the point's time along the flight has covered, plus the gravity parabola.
   */
  static int arcHeight(ProjectileEntity p, int x, int y) {
    ProjectileData data = p.getData();
    TargetView target = p.targetView();
    int ax = p.getAimX();
    int ay = p.getAimY();
    if (target != null && data.homing()) {
      ax = target.x();
      ay = target.y();
    }
    int speed = data.speed() > 1 ? data.speed() : 1;
    int sx = p.getStartX();
    int sy = p.getStartY();
    int sz = p.getStartZ();
    int total = FixedMath.divOrZero(FixedMath.guardedDistance(ax - sx, ay - sy), speed);
    total = total > 1 ? total : 1;
    int t = FixedMath.divOrZero(FixedMath.guardedDistance(x - sx, y - sy), speed);
    int climb = p.getAimZ() - sz;
    int gravityHalf = FixedMath.divOrZero(data.gravity() * -500, 1000);
    int linear = FixedMath.divOrZero(climb * t, total);
    int parabola = (t - total) * t;
    return parabola * gravityHalf + linear + sz;
  }

  /** The arrival: the deflection pass finds nothing, the projectile is released and impacts. */
  private static void arrive(ProjectileEntity p, BattleWorld world) {
    // A homing projectile that has not hooked hands its pending damage back to the target here;
    // no pending damage is registered, so there is nothing to hand back.
    p.release();
    // The aim's height would be the constant-height column, which no row carried here has.
    p.moveTo(p.getAimX(), p.getAimY(), 0);
    impact(p, world);
  }

  /** The impact: the amounts at the projectile's level, one hit id, and the hit on the target. */
  private static void impact(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    int damage = p.damage();
    int towerDamage = p.towerDamage();
    int hitId = world.nextHitId();
    if (data.radius() >= 1) {
      // The area impact damages everything in the radius; it is not modelled yet, so a projectile
      // with a radius arrives and deals nothing.
      return;
    }
    TargetView target = p.targetView();
    if (target == null) {
      return;
    }
    singleImpact(p, world, target, damage, towerDamage, hitId);
  }

  /** The hit on the one target: nothing without damage or a target without hit points. */
  private static void singleImpact(
      ProjectileEntity p,
      BattleWorld world,
      TargetView target,
      int damage,
      int towerDamage,
      int hitId) {
    if (damage < 1 || !target.isHitPointsPresent()) {
      return;
    }
    int dealt = target.isCrownTowerTarget() ? towerDamage : damage;
    int directionX = p.getAimX() - p.getStartX();
    int directionY = p.getAimY() - p.getStartY();
    world.dealProjectileDamage(p, p.getTarget(), dealt, hitId, directionX, directionY);
  }
}
