package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * One step of a projectile's flight, and the arrival that ends it.
 *
 * <p>Each step: a limited-time homing projectile re-aims from its launcher at its homing target and
 * counts its time down; a homing projectile pins its aim onto its target's position; then the
 * distance left to the aim is measured. When it is no more than the projectile's speed, the
 * projectile arrives: it is placed at its aim, at the row's constant height or on the ground,
 * released, and its impact runs. Otherwise it moves its speed along the line to the aim, and takes
 * the height the arc gives at the new point: a straight interpolation from the start height to the
 * aim height plus a gravity parabola over the flight's time, where time is distance from the start
 * over speed.
 *
 * <p>The impact of a projectile that hits one target: the row's damage at the projectile's level,
 * or its crown-tower share for a crown tower, dealt once to a target that still has hit points,
 * from the direction of the flight. The damage carries a fresh hit id but no dedupe id, so a second
 * projectile lands on the same target again.
 *
 * <p>The impact of a row with a radius does not look at the target at all: everything the area
 * damage collects in the circle around the aim takes the damage, or the crown-tower share, and the
 * launcher's own side is spared only when the row says so; the victims are pushed the row's
 * pushback away from the aim.
 *
 * <p>A row with a target buff buffs the same circle, or the one target of a projectile without a
 * radius, after the damage, or before it when the row says so. A row that spawns characters then
 * makes them in formation around the aim.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the countdowns' order, the homing re-aim, the remaining distance, the arrival"
            + " test against the speed, the advance along the line with the arc height, the"
            + " release and the placement at the aim on arrival, the single impact with the"
            + " crown-tower choice, the area impact around the aim, and that a projectile whose"
            + " target left lands on nothing. Held by every projectile position and impact of the"
            + " Musketeer and Wizard runs; the area impact's pushback by fireball_knight_tower and"
            + " the impact's character spawn by goblin_barrel_tower; the delay, the ring point and"
            + " the chain by arrows_skeletons; a flying body's pass after every step, and the"
            + " projectile the impact spawns beyond the aim, by log_goblins and"
            + " barb_barrel_knight, the pass at the arrival held by no run; the limited-time homing"
            + " re-aim by elite_archer_knight, and the landing at the constant height by"
            + " royal_giant_tower; the target buff on the circle after the damage by"
            + " snowball_knights, and on the one target before it by witch_mother_skeletons; the"
            + " circle's before the damage and the one target's after it by no run. Supplied, not"
            + " settled:"
            + " the deflection pass answers nothing, the projectile's own radius is zero, and the"
            + " row's target limit, which is not carried, is none. Not modelled: the area impact"
            + " of one that only heals, the height toward a moving target under the"
            + " z-distance column, the random delays, the pingpong sweep, the drag-back hook, the"
            + " hit effects, and the on-impact area effect.")
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
    // A projectile with a delay left counts it down and does not move; the random and angular
    // delays before it would hold the projectile here too, and no row carried here has one.
    if (p.getDelayMs() >= 1) {
      p.stepDelay();
      return;
    }
    snapToTarget(p);
    int remaining = FixedMath.guardedDistance(p.getX() - p.getAimX(), p.getY() - p.getAimY());
    int speed = data.speed();
    if (remaining <= speed) {
      arrive(p, world);
    } else {
      advance(p, remaining, speed);
      // A projectile that flies to a point hits what its body passes after every step; anything
      // else asks the deflection pass, which finds nothing in a battle without deflecting area
      // effects.
      if (data.homingLike()) {
        world.cellPass(p, p.getX(), p.getY(), 0);
      }
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
    // It lands at the aim, at the row's constant height, or on the ground without one.
    p.moveTo(p.getAimX(), p.getAimY(), Math.max(p.getData().constantHeight(), 0));
    impact(p, world);
  }

  /**
   * The impact: the amounts at the projectile's level, one hit id, and the hit on the target, or
   * the area around its aim - around its ring point for a chain that lands on ring points.
   */
  private static void impact(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    int damage = p.damage();
    int towerDamage = p.towerDamage();
    int hitId = world.nextHitId();
    ProjectileChain chain = p.getChain();
    boolean onRing = chain != null && chain.isRingPoints();
    int px = onRing ? p.getRingX() : p.getAimX();
    int py = onRing ? p.getRingY() : p.getAimY();
    // The target buff goes before the damage when the row says so, else after it: after, a victim
    // the damage killed has run its death already; before, it dies carrying the buff.
    boolean buffFirst = data.applyBuffBeforeDamage();
    boolean buffs = data.targetBuff() != null;
    if (data.radius() >= 1) {
      if (buffs && buffFirst) {
        world.projectileAreaBuff(p, px, py);
      }
      areaImpact(p, world, px, py, damage, towerDamage, hitId);
      if (buffs && !buffFirst) {
        world.projectileAreaBuff(p, px, py);
      }
    } else {
      WorldEntity target = p.getTarget();
      if (target != null) {
        if (buffs && buffFirst) {
          world.projectileTargetBuff(p, target);
        }
        singleImpact(p, world, target.getTargetView(), damage, towerDamage, hitId);
        if (buffs && !buffFirst) {
          world.projectileTargetBuff(p, target);
        }
      }
    }
    // A projectile that flies to a point hits what its body covers at its aim once more.
    if (data.homingLike()) {
      world.cellPass(p, p.getX(), p.getY(), 0);
    }
    // The impact's character spawn: its children in formation around the impact point.
    if (data.spawnCharacterCount() >= 1) {
      world.impactSpawn(p, px, py);
    }
    // The projectile it spawns flies on beyond the aim, along the line it came.
    if (data.spawnProjectile() != null && p.getSpawnChain() >= 1) {
      world.impactProjectile(p, 2 * p.getAimX() - p.getStartX(), 2 * p.getAimY() - p.getStartY());
    }
  }

  /**
   * The impact of a projectile with a radius: everything in the circle around the aim takes the
   * damage, or the crown-tower damage, and the launcher's own side too unless the row spares it.
   */
  private static void areaImpact(
      ProjectileEntity p,
      BattleWorld world,
      int px,
      int py,
      int damage,
      int towerDamage,
      int hitId) {
    ProjectileData data = p.getData();
    if (data.homingLike()) {
      // A projectile that flies to a point has hit along its way already, and only runs that pass
      // once more at its aim.
      world.cellPass(p, p.getAimX(), p.getAimY(), 0);
      return;
    }
    if (damage <= 0) {
      // Without damage only a heal is delivered to the area; no row carried here heals.
      return;
    }
    List<TargetView> entities = new ArrayList<>();
    for (WorldEntity entity : world.present()) {
      entities.add(entity.getTargetView());
    }
    AreaDamage.Area area =
        new AreaDamage.Area(
            px,
            py,
            data.radius(),
            damage,
            towerDamage,
            hitId,
            // The row's target limit is not carried; no row the reference runs use has one.
            0,
            !data.onlyEnemies(),
            data.aoeToAir(),
            data.aoeToGround(),
            false,
            data.pushback(),
            px,
            py);
    // A chain's victims must also stand in its circle, and none is hit twice by it.
    ProjectileChain chain = p.getChain();
    AreaDamage.Chain shared =
        chain == null
            ? null
            : new AreaDamage.Chain(
                chain.getX(), chain.getY(), chain.getRadius(), chain.getHitIds());
    AreaDamage.damage(
        p.areaOwner(),
        entities,
        area,
        shared,
        ValidatorQueries.standard1v1(),
        new AreaDamage.Queries() {
          @Override
          public DamageResult damage(TargetView victim, int dealt, int id) {
            // The area hands the damage on without a direction.
            return world.dealProjectileDamage(
                p, world.entityOf(victim.getEntity()), dealt, id, 0, 0);
          }

          @Override
          public boolean push(TargetView victim, int fromX, int fromY, int distance) {
            // Its victims are pushed away from the impact point, as an area effect's are.
            return world.pushByArea(world.entityOf(victim.getEntity()), fromX, fromY, distance);
          }
        });
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
