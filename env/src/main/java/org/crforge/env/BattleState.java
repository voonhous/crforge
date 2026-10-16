/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.Arrays;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * A battle's state as integers, for telling two battles apart step by step: the tick, the battle
 * generator's state, each side's elixir, hand, queue and crowns, and every entity of the holder in
 * its order with its id, kind, side, row, position, state and hit points. Two battles whose states
 * differ after a step have not run the same; equal states after every step are the evidence that
 * they did.
 */
final class BattleState {

  private BattleState() {
    // Utility class
  }

  /**
   * The state of a battle now.
   *
   * @param standard the battle
   * @return its state as integers
   */
  static int[] of(Standard1v1Battle standard) {
    Battle battle = standard.getBattle();
    Ints out = new Ints();
    out.add(battle.getTick());
    out.add(standard.getWorld().getRandom().getState());
    LadderMatch match = standard.getMatch();
    for (int side = 0; side < 2; side++) {
      MatchSide own = match.side(side);
      out.add(own.getElixir());
      for (int slot : own.getHand().slots()) {
        out.add(slot);
      }
      out.add(-2);
      for (int index : own.getHand().queue()) {
        out.add(index);
      }
      out.add(-2);
      out.add(match.crowns(side));
    }
    out.add(standard.getPlays().size());
    for (BattleEntity entity : battle.getHolder().entities()) {
      out.add(entity.getId());
      if (entity instanceof WorldEntity world) {
        out.add(1);
        out.add(world.side());
        out.add(world.getData().name().hashCode());
        out.add(world.getView().getX());
        out.add(world.getView().getY());
        out.add(world.getView().getState());
        out.add(world.getView().isAlive() ? 1 : 0);
        HitPoints points = world.getHitPoints();
        out.add(points == null ? -1 : points.getHitPoints());
        out.add(points == null ? -1 : points.getShield());
      } else if (entity instanceof ProjectileEntity projectile) {
        out.add(2);
        out.add(projectile.side());
        out.add(projectile.getData().name().hashCode());
        out.add(projectile.x());
        out.add(projectile.y());
      } else if (entity instanceof AreaEffectEntity area) {
        out.add(3);
        out.add(area.side());
        out.add(area.getData().name().hashCode());
        out.add(area.getX());
        out.add(area.getY());
      } else {
        out.add(4);
        out.add(entity.getClass().getName().hashCode());
      }
    }
    return out.toArray();
  }

  /** A growing list of integers. */
  private static final class Ints {
    private int[] values = new int[256];
    private int size;

    void add(int value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = value;
    }

    int[] toArray() {
      return Arrays.copyOf(values, size);
    }
  }
}
