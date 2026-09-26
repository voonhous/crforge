package org.crforge.core.pathfinding.state;

import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The match-wide settings the entity state visit reads.
 *
 * @param attackFinishTimeMs the attack-finish time, which is also how long a spawned unit's
 *     first-tick immunity lasts
 */
public record StateVisitGlobals(int attackFinishTimeMs) {

  /**
   * The standard game's value: {@link PathfindingGlobals#ATTACK_FINISH_TIME_MS}, so a spawned
   * unit's first-tick immunity survives five visits and clears on the sixth.
   */
  public static StateVisitGlobals standard() {
    return new StateVisitGlobals(PathfindingGlobals.ATTACK_FINISH_TIME_MS);
  }
}
