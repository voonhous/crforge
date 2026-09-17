package org.crforge.core.pathfinding.state;

import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The match-wide settings the entity state visit reads.
 *
 * @param attackFinishTimeMs how long an attack takes to finish, after which the entity stops being
 *     counted as finishing one
 */
public record StateVisitGlobals(int attackFinishTimeMs) {

  /**
   * The standard game's value: {@link PathfindingGlobals#ATTACK_FINISH_TIME_MS}, so the
   * attack-finish latch survives five ticks and clears on the sixth.
   */
  public static StateVisitGlobals standard() {
    return new StateVisitGlobals(PathfindingGlobals.ATTACK_FINISH_TIME_MS);
  }
}
