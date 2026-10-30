/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.ShapeSelectorHost;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * What a shape selector's run asks of the battle whoever its owner is: the battle tick, an object's
 * hit points and shield or its squared distance from the owner's point, the holder of a pick, where
 * a pick stands, and an action built for and scheduled on a pick with the cause and the context the
 * run hands it. The owner answers for itself: the circle around its point, its own point, and the
 * action scheduled on itself.
 */
abstract class WorldShapeSelectorHost implements ShapeSelectorHost {

  private final BattleWorld world;

  WorldShapeSelectorHost(BattleWorld world) {
    this.world = world;
  }

  /**
   * The objects the circle of the radius around the owner's point lets through, buildings tested by
   * their squares.
   */
  abstract List<WorldEntity> around(int radius, GameObjectFilter filter);

  @Override
  public int tick() {
    return world.tick();
  }

  @Override
  public List<Integer> collect(int radius, GameObjectFilter filter) {
    List<Integer> ids = new ArrayList<>();
    for (WorldEntity entity : around(radius, filter)) {
      ids.add(entity.getId());
    }
    return ids;
  }

  @Override
  public int score(int id, int mode) {
    WorldEntity object = (WorldEntity) world.liveObject(id);
    if (mode == ShapeSelector.CLOSEST) {
      // The guarded squared distance from the owner's point, its bits flipped.
      return FixedMath.INT_MAX
          ^ FixedMath.squaredDistance(ownerX(), ownerY(), object.x(), object.y());
    }
    HitPoints hitPoints = object.getHitPoints();
    if (hitPoints == null) {
      return 0;
    }
    return mode == ShapeSelector.HIGHEST_CURRENT_HP_INCLUDE_SHIELDS
        ? hitPoints.getHitPoints() + hitPoints.getShield()
        : hitPoints.getHitPoints();
  }

  @Override
  public ActionHolder holder(int id) {
    return ((WorldEntity) world.liveObject(id)).actionHolder();
  }

  @Override
  public void schedule(int targetId, String action, ActionHolder cause, ActionContext context) {
    WorldEntity target = (WorldEntity) world.liveObject(targetId);
    BattleAction built = world.getActions().build(action, world.binding(target));
    target.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
  }

  @Override
  public int x(int id) {
    return ((WorldEntity) world.liveObject(id)).x();
  }

  @Override
  public int y(int id) {
    return ((WorldEntity) world.liveObject(id)).y();
  }
}
