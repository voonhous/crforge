package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.pathfinding.combat.DamageResult;

/**
 * Something that watches one battle's arena from outside the tick: it is told where the tick's
 * entity visits begin and end, and about every hit that lands, and takes no part in any of it.
 *
 * <p>Both tick calls hand over the tick's arena entities in ascending id, the list the visits ran
 * over. An entity that became removable during the tick is still in it at the end, because the
 * closing cleanup runs after the last call.
 */
public interface WorldObserver {

  /**
   * After the tick's pre-pass: everything the visits query is built, and nothing has been visited.
   */
  default void afterPrePass(int tick, List<WorldEntity> present) {}

  /**
   * After every entity's post-hook and before the closing cleanup: every position and state of the
   * tick is final, and an entity that died this tick is still present.
   */
  default void afterPostHooks(int tick, List<WorldEntity> present) {}

  /**
   * The damage of one hit was dealt to an entity.
   *
   * @param tick the tick the hit landed in
   * @param target the entity the damage was dealt to
   * @param damage hit points the hit dealt, before the target's guards and the clamp to zero
   * @param result what the damage did to the target
   */
  default void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {}
}
