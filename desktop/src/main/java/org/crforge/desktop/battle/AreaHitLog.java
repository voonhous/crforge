/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.projectile.ProjectileChain;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.crforge.core.pathfinding.combat.AreaDamage;

/**
 * Collects the circle of every area hit the battle deals, for the visualizer's area damage
 * indicators: an entity's splash and a death's area (the world's {@code areaDamaged}), each hit of
 * an area effect ({@code areaEffectDamaged}) and the arrival of a projectile whose row has an area.
 * The world tells no observer of a projectile's area, so that one is read from the projectiles the
 * tick visited: one released in the tick with an area radius hit around its aim, or its chain's
 * ring point, as its impact does. It only listens; the screen drains what it collected after each
 * frame's steps.
 */
public final class AreaHitLog implements WorldObserver {

  /**
   * One area hit.
   *
   * @param tick the tick it fell in
   * @param side the side of the entity whose hit it was
   * @param x the circle's centre along the width, in game units
   * @param y the circle's centre along the length, in game units
   * @param radius the circle's radius, in game units
   */
  public record AreaHit(int tick, int side, int x, int y, int radius) {}

  private final List<AreaHit> pending = new ArrayList<>();

  @Override
  public void areaDamaged(
      int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    pending.add(new AreaHit(tick, owner.side(), area.x(), area.y(), area.radius()));
  }

  @Override
  public void areaEffectDamaged(
      int tick, AreaEffectEntity areaEffect, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    pending.add(new AreaHit(tick, areaEffect.side(), area.x(), area.y(), area.radius()));
  }

  @Override
  public void afterPostHooks(
      int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
    for (ProjectileEntity projectile : projectiles) {
      int radius = projectile.getData().radius();
      if (!projectile.isReleased() || radius < 1) {
        continue;
      }
      ProjectileChain chain = projectile.getChain();
      boolean onRing = chain != null && chain.isRingPoints();
      int x = onRing ? projectile.getRingX() : projectile.getAimX();
      int y = onRing ? projectile.getRingY() : projectile.getAimY();
      pending.add(new AreaHit(tick, projectile.getSide(), x, y, radius));
    }
  }

  /** The area hits since the last drain, in the order they fell, and forgets them. */
  public List<AreaHit> drain() {
    List<AreaHit> hits = List.copyOf(pending);
    pending.clear();
    return hits;
  }
}
