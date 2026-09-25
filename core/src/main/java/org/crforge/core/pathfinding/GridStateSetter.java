package org.crforge.core.pathfinding;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.function.Supplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.state.StateSetter;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * The state setter of one grid-driven unit: the interrupt guard, and the actions a state change
 * runs on the unit's movement and targeting components.
 *
 * <p>A state change is more than a store. Leaving the attacking state clears the targeting
 * component's target-lost timer; leaving the deploying or either pathfinding state clears the
 * deploy countdown, unless the unit is leaving for clone setup. Entering the standing, attacking,
 * clone-setup or casting state empties the route and clears the route-leads-away bit, so a unit
 * that stops holds no route. Entering the moving state prepares a route at once, over whatever
 * reference the targeting component holds at that moment, rather than waiting for the next movement
 * visit. The actions run in that order: exit actions, the store, entry actions.
 *
 * <p>The guard comes first: while the deploy countdown is running, only clone setup and the two
 * removed-and-following states may be set, so nothing pulls a unit that is still being placed into
 * attacking or moving. A request for the state the unit is already in does nothing at all.
 *
 * <p>The route preparation needs the movement pass's answers over the unit's <b>current</b>
 * reference, which is why the setter is given a supplier of chains rather than a chain: each
 * preparation builds a fresh one.
 *
 * <p>A unit without a movement component skips every route action, and one without a targeting
 * component skips the target-lost clear; either may be null.
 *
 * <p>A setter given the unit's deploy time seeds the deploy countdown on entering the deploying
 * state with the larger of the countdown and the deploy time, which is how a unit that waited its
 * turn starts deploying.
 *
 * <p><b>Not carried here.</b> The standard game also switches components on and off as states
 * change, seeds the morph countdown on entering the morphing state and the ability countdowns on
 * entering the casting state, raises the dashing flag and stops the unit on entering the dashing
 * state, chains a further dash on leaving it, clears the movement component's destination on
 * leaving clone setup, relocates a unit leaving a following state to a free cell, makes a building
 * asked to follow stand instead, and ends every change with two notifications. None of that is
 * reachable from a plain ground unit, which is all the grid drives.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the interrupt guard; the route emptied on entering the standing, attacking,"
            + " clone-setup and casting states; the route prepared on entering the moving"
            + " state; the target-lost timer cleared on leaving the attacking state; the deploy"
            + " countdown cleared on leaving the deploying and pathfinding states and raised to"
            + " the unit's deploy time on entering the deploying state. Held by the 53 reference"
            + " walks, whose route empties at the lock, and the staggered placements. Not"
            + " modelled: switching components, the countdowns seeded on entering the morphing and"
            + " casting states, the dash entry and exit, the following-state rewrites and the"
            + " two notifications every change ends with.")
public final class GridStateSetter implements StateSetter {

  private final GridEntity owner;
  private final MovementState movement;
  private final TargetingState targeting;
  private final Supplier<MovementChain> chains;

  /** The deploy countdown entering the deploying state seeds; -1 for a setter that seeds none. */
  private final int deployTimeMs;

  /**
   * Creates the setter of one unit.
   *
   * @param owner the unit whose state this sets
   * @param movement the unit's movement component, or null when it has none
   * @param targeting the unit's targeting component, or null when it has none
   * @param chains builds a movement chain over the unit's current reference, for the route
   *     preparation that entering the moving state runs
   */
  public GridStateSetter(
      GridEntity owner,
      MovementState movement,
      TargetingState targeting,
      Supplier<MovementChain> chains) {
    this(owner, movement, targeting, chains, -1);
  }

  /**
   * Creates the setter of one unit that seeds its deploy countdown on entering the deploying state.
   *
   * @param owner the unit whose state this sets
   * @param movement the unit's movement component, or null when it has none
   * @param targeting the unit's targeting component, or null when it has none
   * @param chains builds a movement chain over the unit's current reference
   * @param deployTimeMs the unit's deploy time, which entering the deploying state seeds the
   *     countdown with; -1 to seed nothing
   */
  public GridStateSetter(
      GridEntity owner,
      MovementState movement,
      TargetingState targeting,
      Supplier<MovementChain> chains,
      int deployTimeMs) {
    this.owner = owner;
    this.movement = movement;
    this.targeting = targeting;
    this.chains = chains;
    this.deployTimeMs = deployTimeMs;
  }

  @Override
  public void setState(GridEntity entity, int newState) {
    checkArgument(
        entity == owner,
        () -> "the setter of " + owner.getName() + " was asked about " + entity.getName());
    int oldState = owner.getState();
    if (oldState == newState) {
      return;
    }
    if (owner.getDeployCountdown() >= 1 && !interruptsDeployment(newState)) {
      return;
    }
    exit(oldState, newState);
    owner.setState(newState);
    enter(newState);
  }

  /** The three states that may be set while the deploy countdown is still running. */
  private static boolean interruptsDeployment(int state) {
    return state == GridEntityState.CLONE_SETUP
        || state == GridEntityState.FOLLOWING_REMOVED
        || state == GridEntityState.FOLLOWING_REMOVED_BUILDING;
  }

  /** The actions keyed by the state being left. */
  private void exit(int oldState, int newState) {
    switch (oldState) {
      case GridEntityState.ATTACKING -> {
        if (targeting != null) {
          targeting.setTargetLostTimerMs(0);
        }
      }
      case GridEntityState.DEPLOYING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND -> {
        if (newState != GridEntityState.CLONE_SETUP) {
          owner.setDeployCountdown(0);
        }
      }
      default -> {
        // No ported action.
      }
    }
  }

  /** The actions keyed by the state being entered, run after it is stored. */
  private void enter(int newState) {
    switch (newState) {
      case GridEntityState.STANDING,
          GridEntityState.ATTACKING,
          GridEntityState.CLONE_SETUP,
          GridEntityState.CASTING ->
          resetRoute();
      case GridEntityState.MOVING -> prepareRoute();
      case GridEntityState.DEPLOYING -> {
        if (deployTimeMs >= 0) {
          // The entry keeps the larger of the running countdown and the deploy time. The guard
          // refuses this state while the countdown is 1 or more, so here it is the deploy time.
          owner.setDeployCountdown(Math.max(owner.getDeployCountdown(), deployTimeMs));
        }
      }
      default -> {
        // No ported action.
      }
    }
  }

  /** Empties the route and clears the route-leads-away bit. */
  private void resetRoute() {
    if (movement == null) {
      return;
    }
    movement.getRoute().clear();
    movement.setRouteLeadsAway(0);
  }

  /**
   * Prepares a route over the unit's current reference, as entering the moving state does. Storing
   * a reference asks for the same preparation, so a targeting component's owner points its route
   * request here.
   */
  public void prepareRoute() {
    if (movement == null) {
      return;
    }
    chains.get().prepareRoute();
  }
}
