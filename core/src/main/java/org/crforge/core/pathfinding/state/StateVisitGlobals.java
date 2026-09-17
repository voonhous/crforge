package org.crforge.core.pathfinding.state;

/**
 * The match-wide settings the entity state visit reads.
 *
 * @param attackFinishTimeMs how long an attack takes to finish, after which the entity stops being
 *     counted as finishing one
 */
public record StateVisitGlobals(int attackFinishTimeMs) {

  /** The standard game's value, which the recorded trajectories use. */
  public static StateVisitGlobals standard() {
    return new StateVisitGlobals(0);
  }
}
