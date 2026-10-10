/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a net attack's run asks of the battle around the character that runs it. Objects are named
 * by their ids, as the run keeps them.
 */
public interface NetAttackHost {

  /** True while the owner's tag word holds NO_ATTACK, a stun or a hold. */
  boolean noAttack();

  /** A time step as the owner's buffs scale its hit speed. */
  int timeStep(int stepMs);

  /** True while the owner's targeting component is on; a stun or a deploy switches it off. */
  boolean targetingOn();

  /**
   * How long the owner's attack has loaded since its load time was last written: its row's LoadTime
   * less what its load countdown still holds.
   */
  int loadedMs();

  /** The owner's row's HitSpeed. */
  int hitSpeedMs();

  /** The owner's attack time, from which its hits are counted. */
  int attackTimeMs();

  /** Where the owner stands along the width. */
  int ownerX();

  /** Where the owner stands along the length. */
  int ownerY();

  /** The owner's row's collision radius. */
  int ownerRadius();

  /**
   * The objects the object query around the owner's point answers, testing a building by its
   * square: the index's buckets over the circle, walked in their order, each object once, anything
   * else accepted strictly within the radius plus its own collision radius, and each by the filter,
   * asked for the owner's team.
   *
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> query(int radius, GameObjectFilter filter);

  /** Where the object stands along the width. */
  int x(int id);

  /** Where the object stands along the length. */
  int y(int id);

  /**
   * Whether an object other than the owner holds a target lock on the object on the channel; false
   * while the battle has made no lock manager.
   */
  boolean heldByOther(int id, int channel);

  /**
   * Launches the net: from the start, at the target where it stands now, with the owner as launcher
   * and owner, at its level and on its side, handed to the holder.
   *
   * @param projectile the projectile's row
   * @param targetId the target's id
   * @param x the start along the width
   * @param y the start along the length
   * @param z the start's height
   * @return the projectile's id
   */
  int launch(String projectile, int targetId, int x, int y, int z);

  /**
   * Schedules an action on the owner from the run pass, the owner as its cause, so an action with
   * no delay waits for the next pending pass.
   *
   * @param action the action
   */
  void schedule(BattleAction action);
}
