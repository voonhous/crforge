package org.crforge.core.pathfinding.target;

import lombok.Getter;
import lombok.Setter;

/**
 * What one targeting visit asks the rest of the tick to do.
 *
 * <p>The targeting pass never changes an entity's route or its movement state itself. It records
 * the two requests here and the caller acts on them after the visit returns: the resume request
 * belongs to the entity state pass, which puts a unit that has just lost its target back into the
 * moving state, and the route request belongs to the movement pass, which plans a route to a newly
 * chosen target in the same tick.
 *
 * <p>A caller clears the outcome before each visit.
 */
@Getter
@Setter
public class TargetingOutcome {

  /**
   * True when the visit dropped its reference or finished an attack and the entity should go back
   * to moving. The entity state pass owns the decision of which state that is.
   */
  private boolean resumeRequested;

  /** True when a reference was stored and a route to it should be planned. */
  private boolean routePreparationRequested;

  /** Clears both requests, ready for the next visit. */
  public void clear() {
    resumeRequested = false;
    routePreparationRequested = false;
  }
}
