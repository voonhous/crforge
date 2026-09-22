package org.crforge.core.pathfinding.target;

import lombok.Getter;
import lombok.Setter;

/**
 * What one targeting visit asks the rest of the tick to do.
 *
 * <p>The targeting pass never changes an entity's route or its movement state itself. It records
 * two requests here: the resume request belongs to the entity state pass, which puts a unit that
 * has just lost its target back into the moving state, and the route request belongs to the
 * movement pass, which plans a route to a newly chosen target.
 *
 * <p>The standard game prepares that route at the moment the reference is stored, not on the next
 * movement visit. A caller that can do the same hands the outcome a {@link
 * #setRoutePreparer(Runnable) route preparer}, and {@link #requestRoutePreparation()} runs it as it
 * records the request; a caller that cannot reads the flag after the visit.
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

  /** Prepares the route the moment it is requested, or null when the caller does it later. */
  private Runnable routePreparer;

  /** Records the route request and, when the caller has said how, prepares the route at once. */
  public void requestRoutePreparation() {
    routePreparationRequested = true;
    if (routePreparer != null) {
      routePreparer.run();
    }
  }

  /** Clears both requests, ready for the next visit. The route preparer stays. */
  public void clear() {
    resumeRequested = false;
    routePreparationRequested = false;
  }
}
