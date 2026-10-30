/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.projectile.ProjectileChain;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileGroup;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.index.ShapeTests;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.ReferenceValidator;

/**
 * The deflection of projectiles by the area effects that deflect them: the deflecting area effects
 * of the tick as the spatial index lists them, the deflection pass over a flying projectile's
 * position and over its aim as it arrives, and the deflection itself, which deals the deflector's
 * parent the projectile's damage and sends the projectile back. The world runs the index at its
 * pre-pass and the passes from a projectile's flight and its cell pass.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a deflecting area effect indexed by its radius in the buckets of its square at"
            + " the head of the tick and listed by a building-aware index query at its live"
            + " position; the deflection pass at a flying projectile's position after each move"
            + " and at its aim as it arrives, a projectile measured only at its target's point"
            + " asked at its arrival alone until deflected and then gated on its chain's circle,"
            + " a box body measured as a box, a row that ignores the height measured flat, and an"
            + " area effect on its last step still deflecting; the deflection: the parent's hit"
            + " queued for the drain with a fresh hit id unless its group or chain lists the"
            + " parent, a spell-like projectile sent at the enemy crown tower nearest the area"
            + " effect or passed untouched near its owner once deflected, a king tower's cast"
            + " sent the same way, a rolling body sent back by its flight, a projectile without a"
            + " root owner turned at its start and finished, a rider's shot sent at its carrier,"
            + " a volley turned as one or each projectile on its own, a hook ended where it is,"
            + " and a turn past MAX_DEFLECTION_TIMES ending the flight; held by the reference"
            + " battles of the deflect corpora: arrows-deflect-v1, barbloghero-deflect-v1,"
            + " body-deflect-v1, bomber-evolved-deflect-v1, deflect-enchant-copy-v1,"
            + " deflect-end-poison-v1, deflect-no-root-v1, deflect-rider-shot-v1,"
            + " deflect-source-left-v1, firecracker-deflect-v1, fisherman-deflect-v1,"
            + " goblin-barrel-deflect-v1, hunter-deflect-v1, hunter-ev1-net-deflect-v1,"
            + " king-shot-deflect-captured-v1, log-deflect-v1, magic-archer-deflect-v1,"
            + " monk-deflect-buff-v1, the pingpong-deflect corpora, princess-deflect-v1,"
            + " princess-volley-redeflect-v1, royal-giant-deflect-v1, the"
            + " snowball-ev1-roll-deflect corpora, spell-deflect-v1, spell-king-deflect-v1,"
            + " tower-shot-deflect-v1 and wizard-hero-air-shot-deflect-v1. Refused: the"
            + " projectiles refuseDeflection names, a pingpong projectile met at its aim on its"
            + " arrival, a projectile measured at its target's point without a chain's circle or"
            + " of a volley's group, an area effect that follows nothing, a projectile without a"
            + " root owner that is spell-like or has an action on reaching its target, a Goblin"
            + " Barrel sent at a king tower, and a projectile an area effect launched that was not"
            + " dropped onto it.")
public final class ProjectileDeflection {

  private final BattleWorld world;

  ProjectileDeflection(BattleWorld world) {
    this.world = world;
  }

  /**
   * A deflecting area effect in the spatial index. The game's index holds every listed object whose
   * radius is at least 1, and an area effect answers its radius only when it deflects projectiles
   * (or has a collision behaviour, which no row carried here has), so a living Deflect is indexed
   * by its radius like a unit by its collision radius: in every bucket its square covers, without
   * the moving margin, as an area effect has no components. Java's index holds the arena entities'
   * views only; the area effect's buckets are kept here instead, and a query that would visit them
   * tests it on its live position and radius as the index would.
   *
   * @param deflector the area effect
   * @param xLow its first bucket along the width
   * @param xHigh its last bucket along the width
   * @param yLow its first bucket along the length
   * @param yHigh its last bucket along the length
   */
  private record IndexedDeflector(
      AreaEffectEntity deflector, int xLow, int xHigh, int yLow, int yHigh) {}

  /**
   * This tick's deflecting area effects as the spatial index holds them, in its insertion order:
   * each with the buckets its square covered where it stood at the head of the tick. See {@link
   * IndexedDeflector}.
   */
  private final List<IndexedDeflector> indexedDeflectors = new ArrayList<>();

  /**
   * The global for how near its owner an enemy deflecting area effect may be for a spell-like
   * projectile deflected before to pass it untouched.
   */
  private static final String DOUBLE_DEFLECT_SPELL_MIN_DISTANCE =
      "DOUBLEDEFLECT_SPELL_MIN_DISTANCE";

  /** Forgets the tick's deflecting area effects, at the post-pass. */
  void clear() {
    indexedDeflectors.clear();
  }

  /**
   * Puts this tick's deflecting area effects into the index's buckets, in the snapshot's order, as
   * the index rebuild inserts every listed object whose radius is at least 1: the buckets of its
   * square where it stands at the head of the tick. See {@link IndexedDeflector}.
   */
  void indexDeflectors(List<BattleEntity> snapshot) {
    indexedDeflectors.clear();
    for (BattleEntity entity : snapshot) {
      if (entity instanceof AreaEffectEntity areaEffect
          && areaEffect.getData().deflectsProjectiles()) {
        int radius = areaEffect.deflectRadius();
        if (radius < 1) {
          continue;
        }
        int ax = areaEffect.getX();
        int ay = areaEffect.getY();
        indexedDeflectors.add(
            new IndexedDeflector(
                areaEffect,
                (ax - radius) >> SpatialIndex.BUCKET_SHIFT,
                (ax + radius) >> SpatialIndex.BUCKET_SHIFT,
                (ay - radius) >> SpatialIndex.BUCKET_SHIFT,
                (ay + radius) >> SpatialIndex.BUCKET_SHIFT));
      }
    }
  }

  /**
   * The deflecting area effects a building-aware index query lists, in the index's insertion order:
   * one in a bucket the query visits whose circle, at its live position and radius, meets the
   * query's box - with a half height - or its circle. An area effect is no building.
   */
  List<AreaEffectEntity> listedDeflectors(int x, int y, int radius, int halfHeight) {
    List<AreaEffectEntity> out = new ArrayList<>();
    int xLow = (x - radius) >> SpatialIndex.BUCKET_SHIFT;
    int xHigh = (x + radius) >> SpatialIndex.BUCKET_SHIFT;
    int yLow = (y - radius) >> SpatialIndex.BUCKET_SHIFT;
    int yHigh = (y + radius) >> SpatialIndex.BUCKET_SHIFT;
    if (xLow > xHigh || yLow > yHigh) {
      return out;
    }
    for (IndexedDeflector indexed : indexedDeflectors) {
      int fromX = Math.max(Math.max(xLow, indexed.xLow()), 0);
      int toX = Math.min(Math.min(xHigh, indexed.xHigh()), world.getIndex().getWidth() - 1);
      int fromY = Math.max(Math.max(yLow, indexed.yLow()), 0);
      int toY = Math.min(Math.min(yHigh, indexed.yHigh()), world.getIndex().getHigh() - 1);
      if (fromX > toX || fromY > toY) {
        continue;
      }
      AreaEffectEntity deflector = indexed.deflector();
      int ex = deflector.getX();
      int ey = deflector.getY();
      int er = deflector.deflectRadius();
      boolean inside =
          halfHeight != 0
              ? ShapeTests.withinBox(
                  ex, ey, er, x - radius, y - halfHeight, 2 * radius, 2 * halfHeight)
              : ShapeTests.withinCircle(ex, ey, er, x, y, radius);
      if (inside) {
        out.add(deflector);
      }
    }
    return out;
  }

  /**
   * The deflection pass at a projectile's arrival: at its aim and the aim's height. A projectile
   * measured only at its target's point and not deflected yet, as Arrows' arrows are, is measured
   * at its ring point instead, the unjittered point of the spell's ring its cast gave it, at the
   * aim's height (see {@link #deflectPass}).
   *
   * @param projectile the arriving projectile
   * @return true when a deflecting area effect turned the projectile around
   */
  public boolean arrivalPass(ProjectileEntity projectile) {
    ProjectileChain chain = projectile.getChain();
    if ((projectile.getData().deflectBehaviour() & ProjectileData.CHECK_ONLY_TARGET_POSITION) != 0
        && projectile.getDeflections() == 0
        && chain != null
        && chain.isRingPoints()) {
      return deflectPass(
          projectile, projectile.getRingX(), projectile.getRingY(), projectile.getAimZ());
    }
    return deflectPass(
        projectile, projectile.getAimX(), projectile.getAimY(), projectile.getAimZ());
  }

  /**
   * The deflection pass of a flying projectile, at a point and height: after each move, at its new
   * position, and at its arrival, at its aim. A projectile that never deflects passes untouched.
   * Every other is measured in three dimensions against every area effect of the live list, in its
   * order, that deflects projectiles and that the cleanup would not remove - its countdown not
   * below 0, so one on its last step, at 0, still counts: as a point, within the area effect's
   * deflection radius widened by the projectile's own deflect radius, or its body radius without
   * one; or, with a box body and no deflect radius, as that box (see {@link #touches}). A row whose
   * deflection behaviour ignores the height, as the Royal Giant's shot, is measured by the same
   * shapes on the ground plane: its height left out, so a shot flying at its constant height over
   * the area effect is reached as if it flew on the ground. The first that touches it and deflects
   * it ends the pass; one that touches it but answers no - it has arrived, or is on the area
   * effect's team - lets the pass go on. A projectile that no deflection turns around passes
   * untouched.
   *
   * <p>A projectile measured only at its target's point, as Arrows' arrows are, is asked here only
   * at its arrival until it is deflected (see {@link #arrivalPass}), and then a touching area
   * effect turns it only when its circle - its deflection radius - also overlaps the circle of the
   * projectile's chain: the spell's placed point and radius. Once deflected it is measured as any
   * other.
   *
   * <p>Refused rather than guessed, once a deflecting area effect is listed: such a projectile not
   * deflected before that has no chain, or belongs to a volley's group, whose circle no recording
   * holds; a pingpong projectile not deflected before, met at its aim on its arrival (see {@link
   * #refuseAtAim}); and every deflection {@link #deflect} does not model.
   *
   * @param projectile the projectile
   * @param x the point along the width
   * @param y the point along the length
   * @param z the height
   * @return true when a deflecting area effect turned the projectile around
   */
  public boolean deflectPass(ProjectileEntity projectile, int x, int y, int z) {
    List<AreaEffectEntity> deflectors = deflectors();
    if (deflectors.isEmpty()) {
      return false;
    }
    ProjectileData data = projectile.getData();
    if ((data.deflectBehaviour() & ProjectileData.NO_DEFLECT) != 0) {
      return false;
    }
    // The shape's radii: the projectile's deflect radius, or its body's without one.
    int rx = data.deflectRadius() >= 1 ? data.deflectRadius() : data.projectileRadius();
    int ry = data.deflectRadius() >= 1 ? 0 : data.projectileRadiusY();
    // Measured only at its target's point and not deflected yet: the area effect must also overlap
    // the circle of its chain.
    ProjectileChain gate = null;
    if ((data.deflectBehaviour() & ProjectileData.CHECK_ONLY_TARGET_POSITION) != 0
        && projectile.getDeflections() == 0) {
      gate = projectile.getChain();
      if (gate == null || projectile.isGrouped()) {
        throw new UnsupportedOperationException(
            projectile.name()
                + " ("
                + data.name()
                + ") flies by a deflecting area effect, measured at its target's point without"
                + " a chain's circle, which is not modelled");
      }
    }
    // A row measured on the ground plane leaves its height out: the same shapes, flat.
    int height = (data.deflectBehaviour() & ProjectileData.IGNORE_HEIGHT) != 0 ? 0 : z;
    for (AreaEffectEntity deflector : deflectors) {
      // Only an area effect the cleanup would remove, its countdown below 0, is passed over: one on
      // its last step, its countdown at 0, still deflects.
      if (deflector.getCountdown() < 0) {
        continue;
      }
      if (touches(deflector, x, y, height, rx, ry)
          && (gate == null
              || ShapeTests.withinCircle(
                  deflector.getX(),
                  deflector.getY(),
                  deflector.deflectRadius(),
                  gate.getX(),
                  gate.getY(),
                  gate.getRadius()))) {
        refuseAtAim(deflector, projectile);
        if (deflect(deflector, projectile)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether a deflecting area effect touches a projectile in the deflection pass, in three
   * dimensions, the area effect standing on the ground. A projectile without a half height is a
   * point, touched strictly within the area effect's deflection radius widened by the projectile's
   * radius. One with a half height, a box body as the rolling logs have, is a box around its point:
   * its radius across the width and up the height, its half height along the length; it is touched
   * when the point of the box nearest the area effect lies strictly within the deflection radius,
   * each of the three offsets first held within the radius either way.
   *
   * @param rx the projectile's radius: its deflect radius, or its body's without one
   * @param ry its half height along the length: its body's, without a deflect radius, or 0
   */
  private static boolean touches(AreaEffectEntity deflector, int x, int y, int z, int rx, int ry) {
    int radius = deflector.deflectRadius();
    if (ry <= 0) {
      int reach = radius + rx;
      int squared =
          FixedMath.guardedSumOfSquares(
              FixedMath.s32((long) x - deflector.getX()),
              FixedMath.s32((long) y - deflector.getY()),
              z);
      return squared < reach * reach;
    }
    int dx = nearestOffset(deflector.getX(), x, rx);
    if (dx > radius || dx < -radius) {
      return false;
    }
    int dy = nearestOffset(deflector.getY(), y, ry);
    if (dy > radius || dy < -radius) {
      return false;
    }
    // The area effect stands on the ground; the box reaches its radius up and down.
    int dz = nearestOffset(0, z, rx);
    if (dz > radius || dz < -radius) {
      return false;
    }
    return dx * dx + dy * dy + dz * dz < radius * radius;
  }

  /**
   * The offset from a centre to the point of a span nearest it: the centre held within the span's
   * centre less and plus its half extent, less the centre.
   */
  private static int nearestOffset(int centre, int at, int half) {
    int nearest = centre < at + half ? centre : at + half;
    nearest = centre > at - half ? nearest : at - half;
    return nearest - centre;
  }

  /**
   * Refuses a pingpong projectile not deflected before that a deflecting area effect of the other
   * team touches in this pass: its sweep runs the pass over its body's cells instead, so it meets
   * this pass only at its arrival, measured at its aim - the far point - while it is back at its
   * start. The turn-around would be the one on its sweep, but no recording holds it: at the aim's
   * height, the row's constant height, the Monk's Deflect does not reach any pingpong row carried
   * here.
   */
  private static void refuseAtAim(AreaEffectEntity deflector, ProjectileEntity projectile) {
    if (projectile.getData().pingpongVisualTimeMs() >= 1
        && projectile.getDeflections() == 0
        && !projectile.isReleased()
        && (deflector.side() & 1) != (projectile.side() & 1)) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " ("
              + projectile.getData().name()
              + ") is a pingpong projectile deflected at its aim on its arrival, by "
              + deflector.name()
              + ", not modelled");
    }
  }

  /** The area effects of the live list that deflect projectiles, in its order. */
  List<AreaEffectEntity> deflectors() {
    List<AreaEffectEntity> out = new ArrayList<>();
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof AreaEffectEntity areaEffect
          && areaEffect.getData().deflectsProjectiles()) {
        out.add(areaEffect);
      }
    }
    return out;
  }

  /**
   * Refuses a projectile whose deflection is not modelled, once an area effect would deflect it:
   * one with a deflection behaviour, a box body; one with a deflect radius that spawns a character
   * at its impact, as the Goblin Barrel, deflected before, whose turn-around back at the parent
   * that turned it no recording holds; one that hops or homes for a time; a hook already pulling
   * what it hooked, which a deflection would let go, as no recording holds; one that flies to a
   * point and does not stop at collisions with a least distance, a box body or a deflect radius,
   * which no recording holds; one whose impact spawns a chain of projectiles two links or more
   * long, as the evolved Bomber's bouncing bomb; one that belongs to a chain and is not spell-like.
   * A spell such as the Fireball, whose deflection behaviour sends it at the enemy nearest the area
   * effect without a target, is among them.
   *
   * <p>A projectile that flies to a point and stops at collisions is modelled, as the Hunter's
   * pellets are: its relaunch aims at its root owner's position itself, not stretched to its range,
   * and keeps the root owner as its target, as every deflected relaunch does; it draws a fresh
   * random delay for a row that has one (see {@link ProjectileEntity#deflect}); and nothing of the
   * deflection reads the collisions, so, turned, it hits what its body passes on its way back and
   * stops at the first hit as before. A volley's group that a deflection does not turn around as
   * one, as the Hunter's pellets leave it (DeflectGroup False), is no bar either: each projectile
   * of it is deflected on its own (see {@link #deflect}).
   *
   * <p>So is a piercing body (see {@link #piercingBody}), as the Magic Archer's arrow: its relaunch
   * stretches its aim to its range from where it stands toward its root owner, forgetting the
   * target, and a row that homes for a time homes on the root owner again (see {@link
   * ProjectileEntity#deflect}); turned, it hits what its body passes on its way back.
   *
   * <p>So is a hook on its way to its target, as the Fisherman's: a deflection does not turn it but
   * ends its flight where it is (see {@link ProjectileEntity#deflect}), so the time it homes for is
   * never read again.
   *
   * <p>A deflect radius is no bar otherwise: it widens the deflection pass's reach (see {@link
   * #deflectPass}) and nothing the deflection itself reads, as the Princess's arrows have. Nor is a
   * volley's group that a deflection turns around as one (see {@link #deflect}).
   *
   * <p>Nor is it on the Goblin Barrel's first deflection, the evolved one's and its decoy's too:
   * the king tower that casts it is its root and it has no target, so it is sent at the enemy crown
   * tower nearest the area effect (see {@link #deflect}), and its impact there makes its children
   * for the deflector's side (see {@link BattleWorld#impactSpawn}).
   *
   * <p>A deflection behaviour that only ignores the height is no bar, as the Royal Giant's shot
   * has: it changes the deflection pass's measure (see {@link #deflectPass}) and nothing the
   * deflection itself reads. Nor is a round body on a projectile that does not fly to a point, as
   * the evolved Royal Giant's homing shot has: the deflection reads the body only through the
   * flight to a point, so such a shot is sent back at its root owner as one without a body.
   *
   * <p>A pingpong projectile on its sweep is modelled, its round body and its flight to a point
   * with it, as the Executioner's axe is: the pass over its body's cells turns it around on its
   * way, and, deflected, it flies on as any projectile that flies to a point, which a deflecting
   * area effect of the other side may turn around again. The runs on it, as the evolved axe's
   * controller, are left as they are: the deflection asks them only for the damage of its hit on
   * the deflector's parent. So is one deflected on the step that ends its sweep, its time at its
   * end: the turn-around reads no sweep time, so it is the one on its way, and the step still ends
   * at its start, from where it flies back. One met at its aim on its arrival is refused by the
   * deflection pass (see {@link #deflectPass}).
   *
   * <p>A spell-like projectile is modelled too: one whose only deflection behaviour is the spells'
   * tower share, with a deflect radius of its own and no body, as the Fireball, the Rocket, the
   * Snowball and the evolved Cannon's bombs are (see {@link #deflect}); and so is one also measured
   * only at its target's point, of a chain, as Arrows' arrows are, each wave a chain.
   *
   * <p>And a rolling body: one whose deflection behaviour inverts its direction, with a round or a
   * box body and no deflect radius, that flies to a point, as the Bowler's ball, the rolling logs
   * and the Firecracker's sparks do (see {@link #rollingBody}). A starting action is no bar, as the
   * hero Barbarian Barrel's roll has: the deflection leaves its runs as they are, and the button
   * state its run writes on every step goes into the slots of the projectile's side as it is then,
   * so, turned, into the deflector's side's slot that follows the hero barbarian, if any.
   *
   * <p>An action on the deflector is no bar, as the Firecracker's rocket has: the redirect runs it
   * on the deflector's parent (see {@link ProjectileEntity#deflect}). Nor is a projectile whose
   * impact spawns projectiles one link long, as the rocket's sparks: the impact reads nothing of
   * the deflection but the side and the start the redirect gave it, so the sparks fan out, for the
   * deflector's side, along the line from where the rocket was turned to its new aim.
   *
   * <p>Nor is a chain of more links, as the evolved Bomber's bouncing bomb: the relaunch counts its
   * chain from the row's spawn chain again, as every launch does, so a turned bomb bounces on from
   * where it lands for the deflector's side, the deflector's parent its root, along the line from
   * where it was turned, and a turned bounce, which has a range and no body, is aimed its range
   * from where it stands toward its root owner, forgetting any target (see {@link
   * ProjectileEntity#deflect}), and bounces on as often from where it lands. Refused: a projectile
   * whose impact spawns along the length, by its side's forward, as the Fire Wall's, which a turn
   * hands to the other side and no recording holds.
   */
  private static void refuseDeflection(AreaEffectEntity deflector, ProjectileEntity projectile) {
    ProjectileData data = projectile.getData();
    boolean pingpong = data.pingpongVisualTimeMs() >= 1;
    boolean spellLike = spellLike(data);
    boolean rolling = rollingBody(data);
    boolean piercing = piercingBody(data);
    // A hooking projectile is not relaunched: the time it homes for is never read again.
    boolean hooking = data.dragBackSpeed() >= 1;
    // Ignoring the height changes only the deflection pass's measure: nothing after it reads it.
    int behaviour = data.deflectBehaviour() & ~ProjectileData.IGNORE_HEIGHT;
    if ((behaviour != 0 && !spellLike && !rolling)
        || (data.deflectRadius() != 0
            && !spellLike
            && data.spawnCharacter() != null
            && projectile.getDeflections() >= 1)
        || (data.projectileRadiusY() != 0 && !rolling)
        || data.chainedHitRadius() >= 1
        || (data.homingLike() && !pingpong && !rolling && !data.checkCollisions() && !piercing)
        || (data.homingTimeMs() >= 1 && !piercing && !hooking)
        || (hooking && projectile.isHooked())
        || (data.spawnProjectile() != null && data.spawnAxisY())
        || (projectile.getChain() != null && !spellLike)) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " ("
              + data.name()
              + ") flies within "
              + deflector.name()
              + " ("
              + deflector.getData().name()
              + "), whose deflection of it is not modelled");
    }
  }

  /**
   * The deflection of a projectile by an area effect that touches it: none for a projectile that
   * has arrived or is on the area effect's own team. Otherwise the object the area effect follows,
   * its parent, takes the projectile's damage at its level - not the deflected share - with a fresh
   * hit id, when it has hit points, is not listed among those the projectile has hit and the shared
   * validator lets the projectile reach it: the listening runs of a projectile with an action
   * holder change the damage first, as for a travelling hit (the evolved Executioner's controller
   * hands its own amount, by the parent's distance from the axe's start as it is then), and the hit
   * is queued for the damage drain, as a travelling hit is, without a direction. Then the
   * projectile is sent back at its root owner, for the parent's side, so the drain deals the hit,
   * and the runs hear of it, after the turn-around.
   *
   * <p>An enchanting copy a projectile carries, the Giant Buffer's on the completing hit's shot,
   * stays on it through the deflection: the turn touches neither the projectile's runs nor its row.
   * So the parent's hit gains the copy's added damage, and the turned projectile's impact gains it
   * again, after the deflected share, at the level and rarity of the buff's giver, by the
   * multiplier for the projectile's row, the count untouched.
   *
   * <p>A spell-like projectile (see {@link #spellLike}) goes another way. Once deflected, one whose
   * owner, a character, stands within DOUBLEDEFLECT_SPELL_MIN_DISTANCE of the area effect is not
   * deflected at all: it passes on untouched. One without a target - a cast spell - is sent at a
   * point instead of its root owner: the position of the crown tower of the other team than the
   * area effect's nearest the area effect (see {@link #nearestEnemyCrownTower}), a princess tower
   * or the king tower alike, with no target, as a cast Fireball or an arrow of Arrows is; with no
   * such tower it is sent back at its root owner after all. So a spell turned nearer the caster's
   * king tower than any of his princess towers standing - one fallen, or the Deflect right in front
   * of the king - is sent at the king tower, the Royal Chef's too, and lands on it. A bomb a
   * barrage drops onto its area effect has that area effect as its root and its target while the
   * area effect is in the battle: it is sent back at it, at its point, which it keeps as what it
   * was dropped onto. The parent takes the projectile's damage either way, before the redirect.
   *
   * <p>A spell-like projectile whose row names its own deflection action, as the evolved Snowball's
   * rolling snowball, goes that way up to the redirect - the gate, the search and the parent's
   * damage - and is then not turned: the redirect schedules that action on it instead (see {@link
   * ProjectileEntity#deflectAt}). The search's tower is never read.
   *
   * <p>A rolling body (see {@link #rollingBody}) inverts its direction instead, whatever its root:
   * no gate and no search. The parent takes its damage as above, and it is sent on, with no target,
   * from where it stands back by its flight from its start to its aim, so it rolls the way it came
   * by the length it was thrown.
   *
   * <p>A projectile whose root owner is a rider, as a Spear Goblin on a Goblin Giant or the Ram
   * Rider on her Ram, is sent back at the carrier the rider sits on instead: the carrier is its new
   * target and takes the deflected share of its damage. The gates before the redirect still read
   * the rider.
   *
   * <p>A projectile a crown tower fired at the parent has the tower as its root and keeps its
   * target: it is sent back at the tower, a king tower as a princess tower, as from any root.
   *
   * <p>A projectile whose root is a king tower and that has no target, not spell-like, goes the
   * spell-like way all the same: sent at the enemy crown tower nearest the area effect, with no
   * target, or back at the king when there is none. The king tower casts the Goblin Barrel, which
   * has no target, so a deflection sends the barrel at a crown tower of its caster's side, where
   * its children land for the deflector's side; no gate applies to it, the gate being the spells'
   * own.
   *
   * <p>A projectile of a volley's group turned around as one, as the Princess's arrows are (see
   * {@link ProjectileGroup}), is deflected only when it is the first of the group still in the
   * battle; the others pass on untouched. Its deflection turns every projectile of the group still
   * flying the same way - at the same source or point, for the parent's side - the first launched
   * first and the deflected one among them, and then the deflected one once more, so it counts two
   * deflections; without a root owner, the others are turned at the deflected one's start and fly
   * on, only the deflected one's flight is ended. A parent the group lists takes no damage from it;
   * the parent is listed after the damage either way. A chain's list of the entities it has hit is
   * that list for a projectile of a chain, as an arrow of one wave of Arrows: the wave deals the
   * parent its damage once, by its first arrow turned or landed on it, and its arrows turned after
   * pass the parent over, as its arrows landing after do.
   *
   * <p>Such a group is deflected again the same way, by an area effect of the other side, its first
   * still in the battle the one deflected: the first, which counts two deflections from the turn
   * before, counts a third with the group, past the most a projectile takes (MAX_DEFLECTION_TIMES),
   * so its flight ends and it is not turned on its own after; the others, one deflection each
   * before, are turned again and fly on, sent at the first one's root, the parent of the turn
   * before. The group lists only the parents it has turned at, so a new parent takes the first
   * one's damage.
   *
   * <p>A projectile of a volley's group not turned around as one, as each of the Hunter's pellets,
   * is deflected on its own, whichever of the group it is, and only it is turned. The group's list
   * holds for it all the same: the first of the group a deflection reaches deals the parent its
   * damage and lists it, and the group's pellets turned after deal it nothing.
   *
   * <p>A hooking projectile on its way to its target, the Fisherman's hook, is not sent back: the
   * parent takes its damage as above (it has none), and its flight ends where it is, still its
   * owner's, for his side and aimed at its target, its deflections uncounted. It leaves at the next
   * cleanup without hooking anything; its action on reaching its target is scheduled, as a release
   * schedules it. The observers hear of it as sent at nothing.
   *
   * <p>A projectile without a root owner, its shooter gone - the Witch's or the Little Prince's
   * shot, a princess tower's arrow after the tower fell - is not sent back: no gate and no search.
   * The parent takes its damage as above, it is turned at its start with no target, for the
   * parent's side, and its flight is ended: it takes no further step and leaves at the next
   * cleanup, without an impact on its target and without its action on reaching its target.
   *
   * <p>Refused rather than guessed: the projectiles {@link #refuseDeflection} names, an area effect
   * that follows nothing, a projectile without a root owner that is spell-like - a bomb whose area
   * effect has left - or has an action on reaching its target, which no recording holds; a Goblin
   * Barrel the search would send at a king tower, which no recording holds; a projectile an area
   * effect launched that was not dropped onto it. A deflection past the most a projectile takes
   * (MAX_DEFLECTION_TIMES) turns it around all the same and then ends its flight.
   *
   * @return true when the projectile was deflected
   */
  boolean deflect(AreaEffectEntity deflector, ProjectileEntity projectile) {
    if (projectile.isReleased() || (deflector.side() & 1) == (projectile.side() & 1)) {
      return false;
    }
    // Of a group turned around as one, only the first still in the battle is deflected: its
    // deflection turns the others with it.
    ProjectileGroup group = projectile.getGroup();
    boolean asOne = group != null && group.deflectsAsOne();
    if (asOne && group.first() != projectile) {
      return false;
    }
    refuseDeflection(deflector, projectile);
    boolean spellLike = spellLike(projectile.getData());
    WorldEntity source = projectile.getRoot();
    // A bomb dropped onto its area effect has that area effect as its root.
    AreaEffectEntity areaSource =
        source == null && spellLike && projectile.getAreaLauncher() != null
            ? projectile.getAreaLauncher()
            : null;
    if (areaSource != null && projectile.getAreaTarget() != areaSource) {
      throw new UnsupportedOperationException(
          deflector.name()
              + " deflects "
              + projectile.name()
              + ", launched by an area effect it was not dropped onto, which is not modelled");
    }
    if (spellLike && (source != null || areaSource != null) && nearOwner(deflector, projectile)) {
      return false;
    }
    if (!(deflector.getFollow() instanceof WorldEntity parent)) {
      throw new UnsupportedOperationException(
          deflector.name() + " deflects without an object it follows, which is not modelled");
    }
    boolean rolling = rollingBody(projectile.getData());
    // A projectile without a root owner - its shooter has left - is turned at its start and
    // finished: no gate, no search.
    boolean rootless = !rolling && source == null && areaSource == null;
    // Refused there: a spell-like projectile, a bomb whose area effect has left, and one with an
    // action on reaching its target, which the finish does not schedule; no recording holds either.
    if (rootless && (spellLike || projectile.getData().onTargetReachedAction() != null)) {
      throw new UnsupportedOperationException(
          deflector.name()
              + " deflects "
              + projectile.name()
              + " ("
              + projectile.getData().name()
              + ") without a root owner, "
              + (spellLike ? "a spell-like projectile" : "with an action on reaching its target")
              + ", which is not modelled");
    }
    // A projectile whose root is a king tower and that has no target searches for the nearest
    // enemy crown tower, as a spell-like one does: a cast Goblin Barrel, which the king tower
    // casts, and a king tower's own shot whose target has left in its flight, which homes. One
    // with its target still set is sent back at the king, as from any root.
    boolean kingSearch =
        !rolling
            && !rootless
            && !spellLike
            && source.getData().king()
            && projectile.getTarget() == null;
    // A spell-like projectile without a target is sent at the enemy crown tower nearest the area
    // effect, and so is one whose root is a king tower without a target, the Goblin Barrel and the
    // king tower's shot that has lost its target; one with a target, as a bomb on its area effect,
    // goes back at its root.
    WorldEntity tower = null;
    if ((spellLike
            && !rootless
            && projectile.getTarget() == null
            && projectile.getAreaTarget() == null)
        || kingSearch) {
      // A king tower is searched as a princess tower is: the nearest of them takes a spell-like
      // projectile, the Royal Chef's king tower too. A row with its own deflection action is not
      // sent anywhere: the point is never read.
      tower = nearestEnemyCrownTower(deflector, projectile);
      // The Goblin Barrel sent at a king tower, which no recording holds: refused. A king tower's
      // shot, which homes, is sent at a king tower found as at a princess tower: the search keeps
      // only the tower's position.
      if (kingSearch && !projectile.getData().homing() && tower != null && tower.getData().king()) {
        throw new UnsupportedOperationException(
            deflector.name()
                + " sends "
                + projectile.name()
                + " ("
                + projectile.getData().name()
                + "), cast from the king tower "
                + source.name()
                + ", at the king tower "
                + tower.name()
                + ", which is not modelled");
      }
    }
    // The parent takes the projectile's own damage, through its damage reduction, unless the
    // projectile has hit it already: a pingpong projectile on its way back after hitting it.
    if (parent.getHitPoints() != null
        && !projectile.getHitIds().contains(parent.getId())
        && ReferenceValidator.sharedValidate(
            projectile.areaOwner(),
            parent.getTargetView(),
            false,
            false,
            false,
            true,
            world.getValidatorQueries())) {
      int hitId = world.nextHitId();
      int damage = projectile.undeflectedDamage();
      // An enchanting copy the projectile carries adds its damage to this hit too, the parent's
      // crown-tower test choosing which; the deflection leaves the copy on the projectile.
      if (projectile.hasActionHolder()) {
        damage =
            projectile.listenedDamage(damage, hitId, parent.getTargetView().crownTower(), parent);
      }
      // A parent its group lists takes no damage from the group's projectiles; it is listed after.
      // A chain's list of what it has hit is its group's list, as Arrows' wave shares one.
      ProjectileChain chain = projectile.getChain();
      boolean listed =
          (group != null && group.dooms(parent.getId()))
              || (chain != null && chain.getHitIds().contains(parent.getId()));
      if (!listed) {
        // Queued with the projectile as the hit's source: the drain follows the damage with the
        // projectile's after-damage target buff, as it does an impact's, though the projectile
        // flies on to its new target.
        world.hits().queueDeflectedHit(projectile, parent, damage, hitId);
      }
      if (group != null) {
        group.doom(parent.getId());
      }
      if (chain != null && !chain.getHitIds().contains(parent.getId())) {
        chain.getHitIds().add(parent.getId());
      }
    }
    WorldEntity sentAt = null;
    Consumer<ProjectileEntity> turn;
    if (rolling) {
      // From where it stands, back by its flight from its start to its aim.
      turn =
          p ->
              p.deflectAt(
                  parent,
                  p.getX() + p.getStartX() - p.getAimX(),
                  p.getY() + p.getStartY() - p.getAimY());
    } else if (rootless) {
      // At its start, with no target: where it was launched from.
      int startX = projectile.getStartX();
      int startY = projectile.getStartY();
      turn = p -> p.deflectAt(parent, startX, startY);
    } else if (tower != null) {
      GridEntity at = tower.getView();
      turn = p -> p.deflectAt(parent, at.getX(), at.getY());
    } else if (source == null) {
      turn = p -> p.deflectAt(parent, areaSource.getX(), areaSource.getY());
    } else {
      // A root owner that rides a carrier, as the Spear Goblins on a Goblin Giant or the Ram Rider
      // on her Ram, is swapped for the carrier: the shot is sent back at the carrier, its new
      // target, and registers its damage on it.
      WorldEntity backAt =
          source instanceof CharacterEntity rider && rider.getParent() != null
              ? rider.getParent()
              : source;
      turn = p -> p.deflect(parent, backAt);
      // A hooking projectile is not sent back: the redirect ends its flight where it is.
      sentAt = projectile.getData().dragBackSpeed() >= 1 ? null : backAt;
    }
    // A group turned around as one: every projectile of it still in the battle, the first launched
    // first - the deflected one among them - and then the deflected one once more.
    if (asOne) {
      for (ProjectileEntity member : group.members()) {
        turnOnce(member, turn);
      }
    }
    turnOnce(projectile, turn);
    // A projectile turned without a root owner is finished: it takes no further step and leaves at
    // the next cleanup, without an impact and without its action on reaching its target.
    if (rootless) {
      projectile.finishRootless();
    }
    world.events().projectileDeflected(deflector, projectile, parent, sentAt);
    return true;
  }

  /**
   * One redirect of a deflection: nothing for a projectile whose flight has ended; otherwise it is
   * turned, and a deflection past the most a projectile takes (MAX_DEFLECTION_TIMES) still turns it
   * around and then ends its flight, as a release does.
   */
  private void turnOnce(ProjectileEntity projectile, Consumer<ProjectileEntity> turn) {
    if (projectile.isReleased()) {
      return;
    }
    turn.accept(projectile);
    if (projectile.getDeflections() > world.globalNumber("MAX_DEFLECTION_TIMES")) {
      projectile.finishOverDeflected();
    }
  }

  /**
   * Whether a projectile is spell-like as a deflection reads it: its only deflection behaviour is
   * the spells' tower share - with or without being measured only at its target's point, as Arrows'
   * arrows are, which changes the deflection pass alone (see {@link #deflectPass}) and nothing the
   * deflection reads - and it has no body, so it is measured by its deflect radius.
   */
  private static boolean spellLike(ProjectileData data) {
    int behaviour = data.deflectBehaviour() & ~ProjectileData.CHECK_ONLY_TARGET_POSITION;
    return behaviour == ProjectileData.USE_SPELLS_TOWER_DAMAGE_MUL
        && data.projectileRadius() == 0
        && data.projectileRadiusY() == 0;
  }

  /**
   * Whether a projectile is a rolling body as a deflection reads it: its deflection behaviour
   * inverts its direction, alone or with the spells' tower share, which the inverting deflection
   * never reads; it flies to a point with a body, round as the Bowler's ball or a box as the
   * rolling logs, not sweeping out and back, and it has no deflect radius. A least distance is no
   * bar: a deflected launch is never held to it. Nor is a constant height, as the Firecracker's
   * sparks have: the relaunch puts the projectile back at that height, as every launch does.
   */
  private static boolean rollingBody(ProjectileData data) {
    return (data.deflectBehaviour() & ~ProjectileData.USE_SPELLS_TOWER_DAMAGE_MUL)
            == ProjectileData.INVERT_DIRECTION
        && data.homingLike()
        && data.pingpongVisualTimeMs() < 1
        && data.deflectRadius() == 0;
  }

  /**
   * Whether a projectile is a piercing body as a deflection reads it: it flies to a point with a
   * round body, not sweeping out and back, does not stop at collisions and has no deflection
   * behaviour of its own (but the measure on the ground plane), no deflect radius and no least
   * distance, as the Magic Archer's arrows. Its relaunch aims from where it stands (see {@link
   * ProjectileEntity#deflect}); a time it homes for is no bar, the relaunch homes on its root owner
   * again as every launch homes on its target.
   */
  private static boolean piercingBody(ProjectileData data) {
    return (data.deflectBehaviour() & ~ProjectileData.IGNORE_HEIGHT) == 0
        && data.homingLike()
        && data.pingpongVisualTimeMs() < 1
        && !data.checkCollisions()
        && data.projectileRadiusY() == 0
        && data.deflectRadius() == 0
        && data.minDistance() == 0;
  }

  /**
   * The gate on a spell-like projectile deflected before: its owner - the parent of the area effect
   * that turned it - is a character standing within DOUBLEDEFLECT_SPELL_MIN_DISTANCE of this area
   * effect, by the guarded sum of squares against the distance squared.
   */
  private boolean nearOwner(AreaEffectEntity deflector, ProjectileEntity projectile) {
    WorldEntity owner = projectile.getOwner();
    if (projectile.getDeflections() < 1
        || owner == null
        || owner.kind() != BattleEntity.KIND_CHARACTER) {
      return false;
    }
    int least = world.globalNumber(DOUBLE_DEFLECT_SPELL_MIN_DISTANCE);
    int squared =
        FixedMath.guardedSumOfSquares(
            owner.getView().getX() - deflector.getX(), owner.getView().getY() - deflector.getY());
    return squared < least * least;
  }

  /**
   * The crown tower a deflected spell-like projectile is sent at: of the live list, in its order,
   * every princess-like tower (a row that is a summoner tower) and king tower (a summoner) of the
   * other team than the area effect's that the projectile has not hit and that the shared validator
   * lets it reach, its own team not asked; the nearest to the area effect by the guarded sum of
   * squares, the first on a tie. None when there is none.
   */
  private WorldEntity nearestEnemyCrownTower(
      AreaEffectEntity deflector, ProjectileEntity projectile) {
    WorldEntity best = null;
    int bestSquared = FixedMath.INT_MAX;
    for (BattleEntity entity : world.getHolder().entities()) {
      if (!(entity instanceof WorldEntity candidate)
          || !(candidate.getData().king() || candidate.getData().summonerTower())
          || (candidate.side() & 1) == (deflector.side() & 1)
          || projectile.getHitIds().contains(candidate.getId())
          || !ReferenceValidator.sharedValidate(
              projectile.areaOwner(),
              candidate.getTargetView(),
              true,
              false,
              false,
              true,
              world.getValidatorQueries())) {
        continue;
      }
      int squared =
          FixedMath.guardedSumOfSquares(
              deflector.getX() - candidate.getView().getX(),
              deflector.getY() - candidate.getView().getY());
      if (squared < bestSquared) {
        best = candidate;
        bestSquared = squared;
      }
    }
    return best;
  }
}
