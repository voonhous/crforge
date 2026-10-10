/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a target indicator attack's run asks of the battle around the character that runs it.
 * Objects are named by their ids, as the run keeps them.
 */
public interface TargetIndicatorHost {

  /** A time step as the owner's buffs scale its hit speed. */
  int timeStep(int stepMs);

  /** True while the owner's targeting component is on; a stun or a deploy switches it off. */
  boolean active();

  /** True while the owner carries NO_ATTACK. */
  boolean noAttack();

  /** Where the owner stands along the width. */
  int ownerX();

  /** Where the owner stands along the length. */
  int ownerY();

  /** The owner's row's collision radius. */
  int ownerRadius();

  /** The owner's facing as it stands, as {x, y}. */
  int[] facing();

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

  /** Whether an object with the id is in the battle's live list. */
  boolean live(int id);

  /** Where the object stands along the width. */
  int x(int id);

  /** Where the object stands along the length. */
  int y(int id);

  /** The object's collision radius. */
  int radius(int id);

  /** What a selection takes off the object's squared distance: its const-priority offset. */
  int priority(int id);

  /**
   * Makes the signal: the area effect of the row at the target's point, for the owner's side and at
   * its level, the owner as its parent, following nothing, handed to the holder with the owner's id
   * as its creator.
   *
   * @param row the area effect's row
   * @param targetId the target's id
   * @return the signal's id
   */
  int signal(String row, int targetId);

  /**
   * Launches the projectile at the signal: from the start, at the signal's point, with the owner as
   * launcher and owner, at its level and on its side, handed to the holder.
   *
   * @param projectile the projectile's row
   * @param signalId the signal's id
   * @param x the start along the width
   * @param y the start along the length
   * @param z the start's height
   * @return the projectile's id
   */
  int launch(String projectile, int signalId, int x, int y, int z);

  /** Ends a live signal: its countdown to 0, so it leaves at the next cleanup. */
  void endSignal(int signalId);

  /**
   * Schedules an action on the owner from the run pass, so an action with no delay waits for the
   * next pending pass, with an object as its cause.
   *
   * @param action the action
   * @param instigatorId the id of the object that caused it
   */
  void schedule(BattleAction action, int instigatorId);

  /**
   * Tells the battle what the run did, for its observers.
   *
   * @param event what happened, with its details
   */
  void log(TargetIndicatorAttack.Event event);
}
