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
   * An object's score: its hit points, with its shield's when the mode includes shields; 0 for an
   * object without hit points.
   *
   * @param id the object's id
   * @param mode the selection mode, {@link ShapeSelector#HIGHEST_CURRENT_HP} or {@link
   *     ShapeSelector#HIGHEST_CURRENT_HP_INCLUDE_SHIELDS}
   */
  int score(int id, int mode);

  /**
   * Schedules an action row on an object, built for that object, with the area effect as its cause:
   * from the run pass, so an action with no delay waits for the next pending pass.
   *
   * @param targetId the id of the object it runs on
   * @param action the action row's name
   */
  void schedule(int targetId, String action);

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
