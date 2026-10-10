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
 * from the direction of the flight. The damage carries a fresh hit id and the projectile's group id
 * as its dedupe id: 0 for a projectile of no group, so a second projectile lands on the same target
 * again; the projectiles of one group - a projectile and those its impacts spawn - land on an
 * entity once, whatever reaches it.
 *
 * <p>The impact of a row with a radius does not look at the target at all: everything the area
 * damage collects in the circle around the aim takes the damage, or the crown-tower share, and the
 * launcher's own side is spared only when the row says so; the victims are pushed the row's
 * pushback away from the aim.
 *
 * <p>A hopping row, under its count, then hops on: to the nearest character strictly inside its hop
 * radius of where it landed, of the other side, not untargetable, with hit points, accepting an
 * attacker and not hit by it yet - the first of equals in the order they joined; it is launched
 * again at it, waits 150 ms and flies on, so a hop within one step of its speed lands four ticks
 * after the impact.
 *
 * <p>A homing projectile hands the damage it registered on its target back as it arrives, before
 * its impact, so the damage lands on a target that no longer carries it as pending.
 *
 * <p>A row with a target buff buffs the same circle, or the one target of a projectile without a
 * radius, after the damage, or before it when the row says so. A row that spawns characters then
 * makes them in formation around the aim, and a row that spawns an area effect then makes it at the
 * aim, before the projectiles it spawns fly on.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the countdowns' order, the homing re-aim, the remaining distance, the arrival"
            + " test against the speed, the advance along the line with the arc height, the"
            + " release and the placement at the aim on arrival, the single impact with the"
            + " crown-tower choice, the area impact around the aim, and that a projectile whose"
            + " target left lands on nothing. Held by the projectile positions and impacts every"
            + " reference battle observes, among them card_Musketeer, musketeer_s1 and"
            + " card_Wizard, and by BattleProjectileFlightTest; the area impact's pushback by the"
            + " reference battles card_Fireball and spell_fireball_into_push and the impact's"
            + " character spawn by card_GoblinBarrel and spell_goblinbarrel_into_push; the delay,"
            + " the ring point and the chain by card_Arrows, arrows_s0_tower and"
            + " spell_arrows_into_push; a flying body's pass after every step, and the projectile"
            + " the impact spawns beyond the aim, by card_Log, spell_log_into_push, card_BarbLog"
            + " and spell_barblog_into_push, the pass at the arrival held by no run; the"
            + " limited-time homing re-aim by card_EliteArcher, the spawned fan by"
            + " card_Firecracker, and the landing at the constant height by card_RoyalGiant; the"
            + " target buff on the circle after the damage by card_Snowball and"
            + " spell_snowball_into_push, and on the one target before it by card_WitchMother,"
            + " after it by card_ElectroDragon and evo_electrodragon_chain_vs_musketeer_giant,"
            + " whose chained hop is held there too, and the hop after its launcher left by"
            + " random_battle_s0003 and random_battle_s0019; the circle's before the damage by no"
            + " run; the pingpong sweep, its halfway forgetting, its landing at the start and its"
            + " launcher's release by card_AxeMan, the sweep's step under a buff by no run; the"
            + " random delay, the stop at the first landed hit and the arrival without an impact"
            + " of a projectile that stops at collisions by card_Hunter and"
            + " cg_hunter_evo_net_hogrider_giant; the hand-back of the damage registered on the"
            + " target at the arrival, before the impact, by every tower's re-lock after its"
            + " arrow's kill. The deflection pass after each move, at the new position and"
            + " height, which turns an arrow around after the move that brings it within the"
            + " Monk's deflection, held by ability_monk and random_battle16_s0048; the pass at"
            + " the arrival, before the hand-back, by no run. A projectile with a deflect radius"
            + " of its own measured within the Monk's radius widened by it, and refused there, by"
            + " tv_replay_010; a spell passing outside it by the same run. The pass over a body's"
            + " cells beside a deflecting area effect the index does not list, by"
            + " random_battle16_s0048. The pass over a pingpong projectile's body on its sweep"
            + " turning it around, after which it flies on its speed to its aim and lands there"
            + " without coming back, by BattlePingpongDeflectTest. Supplied, not settled: the"
            + " projectile's own radius is zero, and the row's target limit, which is not carried, is none. The on-impact"
            + " area effect at the impact point, after the spawned characters and before the"
            + " spawned projectiles, by card_Heal and spell_heal_into_push. The first step's"
            + " collision check along the segment from the owner to the projectile, by the hero"
            + " Elite Archer's arrow, which finds nothing there. Not modelled: the area impact of"
            + " one that only heals, the height toward a moving target under the z-distance"
            + " column, the drag-back hook, and the hit effects.")
final class ProjectileFlight {

  private static final int PERCENT = 100;

  private ProjectileFlight() {
    // Utility class
  }

  /**
   * Runs one flight step for the projectile.
   *
   * <p>A pingpong projectile sweeps out to its aim and back on the sine of its time, and arrives
   * once its time is up: back at its start, on the ground, where it lets its launcher's targeting
   * go on. Its hits are its body's on the way, each entity once out and once back; its impact hits
   * nothing at its aim.
   */
  static void fly(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    // The first step would spawn a following area effect, which no row carried here has, and runs
    // the initial collision check of a row that has one.
    if (p.takeFirstVisit()) {
      world.initialCollisionCheck(p);
    }
    // A projectile with a custom movement is moved by a run on it: its visit ends here.
    if (data.useCustomMovement()) {
      return;
    }
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
    // A projectile with a delay left counts it down and does not move: a chained hop's wait, or the
    // random delay its launch drew. A second countdown after it is set by nothing carried here.
    if (p.getDelayMs() >= 1) {
      p.stepDelay();
      return;
    }
    boolean drags = data.dragBackSpeed() >= 1;
    if (drags && (world.ownerLost(p) || world.hookTargetLost(p))) {
      // A hooking projectile whose owner has left, or can no longer act, ends here, and so does
      // one whose target is gone: no step, no release where it stands and no impact. The target it
      // pulled stays where it is; an owner waiting for the pull resumes.
      world.hookEnded(p);
      p.release();
      return;
    }
    // A hooked projectile no longer pins its aim onto its target: it flies back to its owner.
    if (!p.isHooked()) {
      snapToTarget(p);
    }
    int remaining = FixedMath.guardedDistance(p.getX() - p.getAimX(), p.getY() - p.getAimY());
    // Under the z-distance column the height left to the aim counts too.
    if (data.considerZDistance()) {
      remaining = Math.abs(p.getAimZ() - p.getZ()) + remaining;
    }
    int speed = p.isHooked() ? data.dragBackSpeed() : data.speed();
    // A speed its launch gave it stands in for the row's, whatever it would be.
    if (p.getSpeedOverride() > 0) {
      speed = p.getSpeedOverride();
    }
    boolean dragsOwner = false;
    // Whether an arrival on this step may hook: not when the target keeps out of the hook.
    boolean hookable = true;
    if (drags) {
      world.refuseLockedHook(p);
      WorldEntity target = p.getTarget();
      if (p.isHooked()) {
        if (p.getOwner().getView().getState() == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
          // A hook on a building stands and drags its owner instead.
          speed = 0;
          dragsOwner = true;
        } else if (target != null && data.dragBackAsAttractor()) {
          speed = attracted(target, speed, BattleWorld.HOOK_PULL_SPEED_FLOOR);
        }
      }
      // A dashing target the untouchable test passes by keeps out of the hook: the projectile
      // still flies at it, but arriving on this step it does not hook.
      hookable = !world.hookKeptOff(p);
    }
    if (data.pingpongVisualTimeMs() >= 1 && p.getDeflections() == 0) {
      // A pingpong projectile sweeps on its time, not its speed, and arrives on the step after
      // its time is up. Once deflected it flies on its speed like any other.
      if (p.getPingpongTimeMs() >= data.pingpongVisualTimeMs()) {
        arrive(p, world, hookable);
      } else {
        sweep(p, world);
      }
      return;
    }
    if (remaining <= speed) {
      arrive(p, world, hookable);
    } else if (dragsOwner) {
      dragOwner(p, world);
    } else {
      advance(p, remaining, speed);
      // A projectile that flies to a point hits what its body passes after every step; anything
      // else asks the deflection pass at its new position and height, unless it is measured only
      // at its target's point and has not been deflected yet.
      if (data.homingLike()) {
        world.cellPass(p, p.getX(), p.getY(), 0);
      } else if ((data.deflectBehaviour() & ProjectileData.CHECK_ONLY_TARGET_POSITION) == 0
          || p.getDeflections() != 0) {
        world.deflectPass(p, p.getX(), p.getY(), p.getZ());
      }
    }
  }

  /**
   * The step of a hook that follows what it hooked: the step scaled by the target's own speed
   * column, at least the floor, as a percentage.
   */
  private static int attracted(WorldEntity target, int step, int floor) {
    return Math.max(target.getData().speed(), floor) * step / PERCENT;
  }

  /**
   * One step of a hook on a building: the projectile stands and moves its owner toward itself, on
   * the ground, until the owner is within both radii of it; then it is released and the owner is
   * asked to move on.
   */
  private static void dragOwner(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    WorldEntity owner = p.getOwner();
    WorldEntity target = p.getTarget();
    GridEntity o = owner.getView();
    int ox = o.getX();
    int oy = o.getY();
    int gap = FixedMath.guardedDistance(p.getX() - ox, p.getY() - oy);
    int reach = owner.getTargetView().radius();
    if (target != null) {
      reach += target.getTargetView().radius();
    }
    if (gap <= reach) {
      p.release();
      world.hookRequest(p, owner, GridEntityState.MOVING);
      return;
    }
    int step = data.dragBackSpeed();
    if (target != null && data.dragBackAsAttractor()) {
      step =
          target.getTargetView().building()
              ? data.dragSelfSpeed()
              : attracted(target, step, BattleWorld.MIN_HOOK_SPEED_FLOOR);
    }
    int nx = FixedMath.divOrZero(step * (p.getX() - ox), gap) + ox;
    int ny = FixedMath.divOrZero(step * (p.getY() - oy), gap) + oy;
    world.dragTo(owner, nx, ny);
  }

  /**
   * The hook, on a hooking projectile's first arrival at its target: the owner's targeting holds
   * it, it turns around from where it stands, on the ground, toward its owner, and hooks. A
   * building it hooked drags the owner to it; anything else is pulled to the owner, which waits.
   * Its aim is the owner's position, pulled in along the line by the margin and both radii. Then it
   * impacts.
   */
  private static void hook(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    WorldEntity owner = p.getOwner();
    WorldEntity target = p.getTarget();
    GridEntity o = owner.getView();
    p.setAim(o.getX(), o.getY(), p.getAimZ());
    p.setStart(p.getX(), p.getY());
    p.moveTo(p.getX(), p.getY(), 0);
    p.hook();
    if (target.getTargetView().building()) {
      world.hookRequest(p, owner, GridEntityState.FOLLOWING_REMOVED_BUILDING);
      world.follow(owner, p);
    } else {
      world.putDown(p, target);
      world.hookRequest(p, target, GridEntityState.FOLLOWING_REMOVED);
      world.follow(target, p);
      world.hookRequest(p, owner, GridEntityState.COMPONENTS_DISABLED);
    }
    int[] vec = {p.getAimX() - p.getStartX(), p.getAimY() - p.getStartY()};
    int radii = target.getTargetView().radius() + owner.getTargetView().radius();
    int length = FixedMath.guardedDistance(vec[0], vec[1]);
    FixedMath.normalize(vec, length - data.dragMargin() - radii);
    p.setAim(vec[0] + p.getStartX(), vec[1] + p.getStartY(), p.getAimZ());
    impact(p, world);
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

  /**
   * One step of a pingpong sweep: the time moves on by the step, and the projectile stands at the
   * start plus the sine of 180 degrees times the share of the time gone of the way to the aim, so
   * it reaches the aim halfway and is back at the start when the time is up; its height does not
   * change. On the step that crosses the halfway time it does not move but forgets what it has hit,
   * so it hits all of it again on the way back. A projectile that flies to a point hits what its
   * body covers where it stood before the step.
   */
  private static void sweep(ProjectileEntity p, BattleWorld world) {
    int total = p.getData().pingpongVisualTimeMs();
    int before = p.getPingpongTimeMs();
    int half = total >> 1;
    int after = before + p.getPingpongStepMs();
    p.setPingpongTimeMs(after);
    if (after >= half && before < half) {
      p.getHitIds().clear();
      return;
    }
    int x = p.getX();
    int y = p.getY();
    int sine = FixedMath.sine1024(FixedMath.div(after * 180, total));
    int nx = p.getStartX() + shiftTowardZero(sine * (p.getAimX() - p.getStartX()));
    int ny = p.getStartY() + shiftTowardZero(sine * (p.getAimY() - p.getStartY()));
    // The distance from the start before the step is stored, which an expression on it reads.
    p.setPingpongDistance(
        FixedMath.isqrt(FixedMath.guardedSumOfSquares(x - p.getStartX(), y - p.getStartY())));
    if (p.getData().homingLike()) {
      world.cellPass(p, x, y, 0);
    }
    p.moveTo(nx, ny, p.getZ());
  }

  /** A value scaled by 1024 brought back down, truncating toward zero. */
  private static int shiftTowardZero(int value) {
    return (value + (value < 0 ? 1023 : 0)) >> 10;
  }

  /**
   * One step of the speed along the line to the aim, at the height the arc gives there; under the
   * z-distance column the height instead moves its share of the step straight toward the aim's.
   */
  private static void advance(ProjectileEntity p, int remaining, int speed) {
    int x = p.getX();
    int y = p.getY();
    int nx = FixedMath.divOrZero((p.getAimX() - x) * speed, remaining) + x;
    int ny = FixedMath.divOrZero((p.getAimY() - y) * speed, remaining) + y;
    if (p.getData().considerZDistance()) {
      int z = p.getZ();
      // No row that homes is carried under the column, so the height it steps toward is the aim's.
      p.moveTo(nx, ny, FixedMath.divOrZero((p.getAimZ() - z) * speed, remaining) + z);
      return;
    }
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

  /**
   * The arrival: first the deflection pass at the aim and its height, and a projectile it turns
   * around flies on from where it is; otherwise the projectile is released and impacts. One that
   * stops at collisions is only released, where it stands. A pingpong projectile lands back at its
   * start, on the ground, and lets its launcher's targeting go on, at once or, for a launcher whose
   * targeting is off, at its removal; one whose launcher left has only its death effect, which is
   * presentation. A hooking projectile arrives as a hook only when its step let it hook; otherwise,
   * at a target that kept out of the hook, its arrival is the ordinary one.
   */
  private static void arrive(ProjectileEntity p, BattleWorld world, boolean hookable) {
    if (world.deflectPass(p, p.getAimX(), p.getAimY(), p.getAimZ())) {
      return;
    }
    if (p.getData().checkCollisions()) {
      // A projectile that stops at collisions and reached its aim without one is released where it
      // stands, without an impact; only its hit effect, which is presentation, is shown.
      p.release();
      return;
    }
    // A homing projectile hands the damage registered on its target back before its impact, so the
    // target no longer carries it as the damage lands. A hook's second arrival hands back nothing:
    // no hooking row deals damage.
    if (p.getData().homing() && p.getTarget() != null) {
      p.handBackPending();
    }
    if (p.getData().dragBackSpeed() >= 1 && hookable) {
      if (p.isHooked() || p.getTarget() == null) {
        // Back short of its owner, or with nothing hooked: released where it stands, and it
        // impacts again.
        p.release();
        impact(p, world);
      } else {
        hook(p, world);
      }
      return;
    }
    p.release();
    if (p.getPingpongTimeMs() < 1) {
      // It lands at the aim, at the row's constant height, or on the ground without one.
      p.moveTo(p.getAimX(), p.getAimY(), Math.max(p.getData().constantHeight(), 0));
    } else {
      p.moveTo(p.getStartX(), p.getStartY(), 0);
      if (p.getOwner() != null) {
        p.getOwner().pingpongReturned(p);
      }
    }
    impact(p, world);
  }

  /**
   * The impact: the amounts at the projectile's level, one hit id, and the hit on the target, or
   * the area around its aim - around its ring point for a chain that lands on ring points.
   */
  private static void impact(ProjectileEntity p, BattleWorld world) {
    ProjectileData data = p.getData();
    int hitId = world.nextHitId();
    // The enchanting copies it carries change both damages, after the hit id is taken.
    int damage = p.listenedDamage(p.damage(), hitId, false, p.getTarget());
    int towerDamage = p.listenedDamage(p.towerDamage(), hitId, true, p.getTarget());
    ProjectileChain chain = p.getChain();
    boolean onRing = chain != null && chain.isRingPoints();
    int px = onRing ? p.getRingX() : p.getAimX();
    int py = onRing ? p.getRingY() : p.getAimY();
    // The row's on-hit action goes onto the target before the hit, the projectile as its cause.
    if (data.onHitTargetAction() != null && p.getTarget() != null) {
      world.onHitTarget(p, p.getTarget());
    }
    // The target buff goes before the damage when the row says so, else after it: after, a victim
    // the damage killed has run its death already; before, it dies carrying the buff. The buff
    // after the damage is not applied here: the game lands the hit at the damage drain, and the
    // buff goes there with the hit, to each victim the drain lets it through to.
    boolean buffFirst = data.applyBuffBeforeDamage() && data.targetBuff() != null;
    if (data.radius() >= 1) {
      if (buffFirst) {
        world.projectileAreaBuff(p, px, py);
      }
      areaImpact(p, world, px, py, damage, towerDamage, hitId);
    } else {
      WorldEntity target = p.getTarget();
      if (target != null) {
        if (buffFirst) {
          world.projectileTargetBuff(p, target);
        }
        singleImpact(p, world, target.getTargetView(), damage, towerDamage, hitId);
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
    // The area effect it spawns, made at the impact point once the hit is over.
    if (data.spawnAreaEffectObject() != null) {
      world.impactAreaEffect(p, px, py);
    }
    // The projectiles it spawns fly on beyond the aim, fanned about the line it came.
    if (data.spawnProjectile() != null && p.getSpawnChain() >= 1) {
      world.impactProjectile(p);
    }
    // A hopping projectile under its count hops on to the nearest character it may hit, whether
    // its launcher is still in the battle or not; at its count, or with nobody in reach, it stays
    // released.
    if (data.chainedHitRadius() >= 1 && p.getChainedHits() < data.chainedHitCount()) {
      WorldEntity next = world.chainTarget(p);
      if (next != null) {
        p.hop(next);
      }
    }
  }

  /**
   * The impact of a projectile with a radius: everything in the circle around the aim takes the
   * damage, or the crown-tower damage, and the launcher's own side too unless the row spares it.
   * Each victim's share lands at the damage drain, after every post-hook, while its push starts at
   * once.
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
      // once more at its aim; a pingpong one, back at its start, does not.
      if (data.pingpongVisualTimeMs() < 1) {
        world.cellPass(p, p.getAimX(), p.getAimY(), 0);
      }
      return;
    }
    if (damage <= 0 && !(data.pushback() > 0 && data.alwaysApplyPushback())) {
      // Without damage only a heal is delivered to the area; no row carried here heals. A row that
      // always pushes still pushes, with no damage dealt.
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
            // The row's target limit is not carried: it is supplied as none.
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
          // A hidden victim, and one the area's state gate refuses as it collects.
          @Override
          public boolean untouchable(TargetView victim) {
            return world.entityOf(victim.getEntity()).passedByProjectileArea();
          }

          @Override
          public DamageResult damage(TargetView victim, int dealt, int id) {
            // The area hands the damage on without a direction, queued for the damage drain.
            return world.dealProjectileAreaDamage(p, world.entityOf(victim.getEntity()), dealt, id);
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
    // Queued for the damage drain.
    world.dealProjectileHit(p, p.getTarget(), dealt, hitId, directionX, directionY);
  }
}
