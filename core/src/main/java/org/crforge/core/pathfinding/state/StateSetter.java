package org.crforge.core.pathfinding.state;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * How a requested state change is applied to an entity.
 *
 * <p>The standard game does not apply every request: while an entity's deploy countdown is still
 * running, only clone setup and the two removed-and-following states may be set, so nothing can
 * pull a unit that is still being placed into attacking or moving. {@link #guarded()} is that rule.
 *
 * <p>{@link #direct()} applies every request. The recorded trajectories use it, which makes no
 * difference there because the only state change during a deployment happens after the countdown
 * has already reached zero. A tick driver in which a deploying unit can be stunned, killed or
 * targeted wants the guarded setter.
 *
 * <p><b>What this interface does not carry.</b> The standard game runs actions when an entity
 * leaves a state and when it enters one, and none of them is ported; only the guard above is.
 *
 * <ul>
 *   <li><b>Components.</b> Entering the moving, deploying or clone-setup state switches the
 *       movement component on; entering the staggered-placement, following or carried states
 *       switches both components off, and leaving the following states switches them back on. Here
 *       a component is never switched by a state change, so whatever drives the entity has to keep
 *       a unit in one of those states quiet itself.
 *   <li><b>Routes.</b> Entering the standing, attacking or casting state empties the route, and
 *       entering the moving state prepares one at once rather than on the next movement visit. Here
 *       a route survives into the attacking state, and a unit that resumes walking prepares its
 *       route one visit later.
 *   <li><b>Countdowns.</b> Entering the deploying state seeds the deploy countdown and its copy
 *       from the DeployTime column; leaving the deploying, spawn-pathfinding or in-game-pathfinding
 *       state for anything but clone setup clears it; entering the morphing state seeds the morph
 *       countdown; entering the casting states seeds the ability countdowns; leaving the attacking
 *       state seeds the not-attacking buff timer; and entering the dashing state raises the dashing
 *       flag and clears the landing countdown.
 *   <li><b>Rewrites.</b> A building asked to enter the second following state stands instead;
 *       leaving a following state moves the entity to a free cell; a dash chained from the setter
 *       returns without storing the requested state; and a cast of no length reverts.
 * </ul>
 *
 * <p>What that means in practice. Whatever creates a unit seeds its deploy countdown, so an
 * ordinary ground troop deploys correctly. The morph, ability and dash countdowns have no writer
 * anywhere, so the blocks of the entity state visit that count them down are inert. Two cases would
 * go wrong rather than merely stay inert, and neither is reachable while only plain ground units
 * are driven: a unit put into a following state while still deploying keeps its countdown and is
 * then refused its way out, and a unit that reaches the deploying state from spawn pathfinding or
 * staggered placement arrives with no countdown and never leaves it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: while a deploy countdown runs only clone setup and the two following"
            + " states may be set. Not modelled: every action the standard game runs on"
            + " leaving and entering a state - switching components, emptying and preparing"
            + " routes, seeding countdowns, the rewrites - as the class comment lists. The"
            + " reference walks were produced the same way, so they do not hold this either.")
@FunctionalInterface
public interface StateSetter {

  /** Applies, or refuses, a state change. */
  void setState(GridEntity entity, int newState);

  /** A setter that applies every request. */
  static StateSetter direct() {
    return (entity, newState) -> entity.setState(newState);
  }

  /**
   * A setter that refuses every state but clone setup and the two removed-and-following states
   * while the entity's deploy countdown is still running.
   */
  static StateSetter guarded() {
    return (entity, newState) -> {
      if (entity.getDeployCountdown() > 0
          && newState != GridEntityState.CLONE_SETUP
          && newState != GridEntityState.FOLLOWING_REMOVED
          && newState != GridEntityState.FOLLOWING_REMOVED_BUILDING) {
        return;
      }
      entity.setState(newState);
    };
  }
}
