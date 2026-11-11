/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.crforge.core.pathfinding.combat.AreaDamage;

/**
 * Collects the area of every area hit the battle deals, for the visualizer's area damage
 * indicators: an entity's splash and a death's area (the world's {@code areaDamaged}), each hit of
 * an area effect, with hit switches ({@code areaEffectDamaged}) or in the filter form ({@code
 * areaEffectFilterHit}), and the impact of a projectile whose row has an area ({@code
 * projectileAreaDamaged}), which the world tells only when the impact dealt its area. An area is a
 * circle, or a box for an area effect shaped as a rectangle. It only listens; the screen drains
 * what it collected after each frame's steps.
 */
public final class AreaHitLog implements WorldObserver {

  /**
   * One area hit.
   *
   * @param tick the tick it fell in
   * @param side the side of the entity whose hit it was
   * @param x the circle's centre along the width, in game units
   * @param y the circle's centre along the length, in game units
   * @param radius the circle's radius, or a box's half width across the arena, in game units
   * @param halfHeight a box's half height along the arena's length, in game units; 0 for a circle
   */
  public record AreaHit(int tick, int side, int x, int y, int radius, int halfHeight) {

    /** A circle. */
    public AreaHit(int tick, int side, int x, int y, int radius) {
      this(tick, side, x, y, radius, 0);
    }

    /** Whether it is a box rather than a circle. */
    public boolean box() {
      return halfHeight > 0;
    }
  }

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

  /**
   * Each hit of an area effect in the filter form, as every damaging area effect row of the game's
   * tables is: the circle it listed in, or the box of a row shaped as a rectangle.
   */
  @Override
  public void areaEffectFilterHit(
      int tick,
      AreaEffectEntity areaEffect,
      int x,
      int y,
      int radius,
      int halfWidth,
      int halfHeight) {
    if (radius >= 1) {
      pending.add(new AreaHit(tick, areaEffect.side(), x, y, radius));
    } else if (halfWidth >= 1 && halfHeight >= 1) {
      pending.add(new AreaHit(tick, areaEffect.side(), x, y, halfWidth, halfHeight));
    }
  }

  /**
   * The area of a projectile's impact, as the impact dealt it: the circle about its aim, or its
   * chain's ring point. An impact that hits nothing, as the thrown projectile of the Log does, is
   * not told, so no indicator marks it.
   */
  @Override
  public void projectileAreaDamaged(
      int tick, ProjectileEntity projectile, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    pending.add(new AreaHit(tick, projectile.getSide(), area.x(), area.y(), area.radius()));
  }

  /** The area hits since the last drain, in the order they fell, and forgets them. */
  public List<AreaHit> drain() {
    List<AreaHit> hits = List.copyOf(pending);
    pending.clear();
    return hits;
  }
}
