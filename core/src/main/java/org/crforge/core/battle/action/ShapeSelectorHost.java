/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a shape selector's run asks of the battle around the area effect that runs it. Objects are
 * named by their ids, as the run keeps them.
 */
public interface ShapeSelectorHost {

  /** The battle tick of the step in progress. */
  int tick();

  /**
   * The objects a circle around the area effect's point holds: the index's buckets over the circle,
   * walked in their order, each object once, a building accepted when the point clamped into its
   * square lies strictly within the radius and anything else when its centre lies strictly within
   * the radius plus its collision radius, and each passing the filter, asked for the area effect's
   * team.
   *
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> collect(int radius, GameObjectFilter filter);

  /**
   * An object's score: its hit points, with its shield's when the mode includes shields, 0 for an
   * object without hit points; for {@link ShapeSelector#CLOSEST} the largest int with the bits of
   * the guarded squared distance from the owner's point to the object's flipped, so the nearest
   * scores highest.
   *
   * @param id the object's id
   * @param mode the selection mode, {@link ShapeSelector#HIGHEST_CURRENT_HP}, {@link
   *     ShapeSelector#HIGHEST_CURRENT_HP_INCLUDE_SHIELDS} or {@link ShapeSelector#CLOSEST}
   */
  int score(int id, int mode);

  /**
   * The action holder of an object, the cause of what the row schedules for a pick.
   *
   * @param id the object's id
   */
  ActionHolder holder(int id);

  /**
   * Schedules an action row on an object, built for that object, with a cause and a context: from
   * the run pass, so an action with no delay waits for the next pending pass.
   *
   * @param targetId the id of the object it runs on
   * @param action the action row's name
   * @param cause the holder of its cause
   * @param context the run's context, or null for none
   */
  void schedule(int targetId, String action, ActionHolder cause, ActionContext context);

  /** The owner's tag word, which the row's pause tags are tested against. */
  default long ownerTags() {
    throw new UnsupportedOperationException("a shape selector's pause tags on this owner");
  }

  /** The owner's side, which picks the side action run on it. */
  default int ownerSide() {
    throw new UnsupportedOperationException("a shape selector's side action on this owner");
  }

  /**
   * The owner's x, which the side action compares with the pick's and a circle that checks its
   * origin measures from.
   */
  default int ownerX() {
    throw new UnsupportedOperationException("a shape selector's side action on this owner");
  }

  /** The owner's y, which a circle that checks its origin measures from. */
  default int ownerY() {
    throw new UnsupportedOperationException("a shape selector's origin check on this owner");
  }

  /**
   * An object's x.
   *
   * @param id the object's id
   */
  default int x(int id) {
    throw new UnsupportedOperationException("a shape selector's side action on this owner");
  }

  /**
   * An object's y.
   *
   * @param id the object's id
   */
  default int y(int id) {
    throw new UnsupportedOperationException("a shape selector's origin check on this owner");
  }

  /**
   * Schedules an action row on the owner itself, built for it, with a cause and a context.
   *
   * @param action the action row's name
   * @param cause the holder of its cause: a picked object's, or the owner's own
   * @param context the run's context, or null for none
   */
  default void scheduleOnOwner(String action, ActionHolder cause, ActionContext context) {
    throw new UnsupportedOperationException(
        "a shape selector's action on its owner " + action + " is not modelled for this owner");
  }

  /**
   * A run started.
   *
   * @param action its row
   * @param phase the pending pass it started in
   * @param due the battle tick each entry is due on, in order
   */
  default void selectorStarted(ShapeSelector action, int phase, List<Integer> due) {}

  /**
   * A step queried its circle or finished the run.
   *
   * @param action its row
   * @param step what the step did
   */
  default void selectorStepped(ShapeSelector action, ShapeSelector.Step step) {}
}
