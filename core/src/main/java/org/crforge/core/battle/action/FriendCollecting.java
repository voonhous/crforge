package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;

/**
 * What a friend-collecting run asks of the battle around the unit that runs it. Objects are named
 * by their ids, as the run keeps them.
 */
public interface FriendCollecting {

  /** The id of the unit running the collection. */
  int ownerId();

  /**
   * The objects the object query around the unit answers: the index's buckets over the circle,
   * walked in their order, each object once, accepted on where it stands now - its centre closer
   * than its collision radius plus the radius - and by the filter, asked for the unit's team. The
   * unit itself may be among them.
   *
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> query(int radius, GameObjectFilter filter);

  /** Whether a character with the id is still in the battle's live list. */
  boolean found(int id);

  /**
   * The squared distance from the unit to the object, the largest int when it would not fit, as the
   * collection compares distances.
   */
  int squaredDistance(int id);

  /** True while the unit may not attack, which holds the collection's countdowns. */
  boolean noAttack();

  /** A time step as the unit's buffs scale its hit speed. */
  int hitSpeed(int stepMs);

  /** The battle's target locks, made by the first call. */
  TargetLocks locks();

  /** Requests the unit's ability. */
  void requestAbility();

  /**
   * Schedules an action on an object, with the unit as its cause, as the collection's hooks are
   * scheduled: from the run pass, so an action with no delay waits for the next pending pass.
   *
   * @param targetId the id of the object it runs on
   * @param action the action
   */
  void schedule(int targetId, BattleAction action);

  /**
   * Launches a projectile from the unit at a friend, where the friend stands now, at the unit's
   * level, and hands it to the holder.
   *
   * @param projectile the projectile row
   * @param friendId the id of the friend
   */
  void launch(ProjectileData projectile, int friendId);
}
