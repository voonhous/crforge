/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a laser ball's run asks of the battle around the area effect that runs it. Objects are named
 * by their ids, as the run keeps them.
 */
public interface LaserBallHost {

  /**
   * The objects the object query around the area effect's point answers: the index's buckets over
   * the circle, walked in their order, each object once, a building accepted when the point clamped
   * into its square lies strictly within the radius and anything else when its centre lies strictly
   * within the radius plus its collision radius, and each passing the filter, asked for the area
   * effect's team.
   *
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> detect(int radius, GameObjectFilter filter);

  /**
   * Schedules an action on an object, with the area effect as its cause: from the run pass, so an
   * action with no delay waits for the next pending pass.
   *
   * @param targetId the id of the object it runs on
   * @param action the action
   */
  void schedule(int targetId, BattleAction action);

  /**
   * A run started.
   *
   * @param action its row
   * @param phase the pending pass it started in
   * @param timerMs its timer as it starts
   */
  default void laserStarted(LaserBall action, int phase, int timerMs) {}

  /**
   * A run fired.
   *
   * @param action its row
   * @param count how many objects it listed
   * @param index the action list's index the count picked
   * @param targets the ids of the objects it scheduled the action on, in order
   * @param scheduled the action scheduled on them, or null for none
   * @param timerBefore its timer as the step began
   * @param timerAfter its timer as the step ended
   */
  default void laserFired(
      LaserBall action,
      int count,
      int index,
      List<Integer> targets,
      BattleAction scheduled,
      int timerBefore,
      int timerAfter) {}
}
