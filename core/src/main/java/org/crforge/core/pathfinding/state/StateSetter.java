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
 * pull a unit that is still being placed into attacking or moving. {@link #guarded()} is that rule
 * and nothing more.
 *
 * <p>A state change also carries actions on the entity's components: stopping empties the route,
 * resuming prepares one at once, leaving the attacking state clears the target-lost timer, leaving
 * a deployment clears its countdown. Those live in {@code GridStateSetter}, the setter of a
 * grid-driven unit, which every tick driver installs. The two setters here run none of them, so
 * they are right only for a unit whose route and timers nobody reads, which is what the tests of
 * the individual visits drive.
 *
 * <p>{@link #direct()} applies every request, the guard included. It exists for tests that want to
 * put an entity into a state regardless of its countdown.
 *
 * <p>What no setter carries yet: the standard game also switches components on and off as states
 * change, seeds the deploy countdown on entering the deploying state, the morph countdown on
 * entering the morphing state and the ability countdowns on entering the casting state, and
 * rewrites some requests (a building asked to follow stands instead, a dash chained from the setter
 * returns without storing the requested state, a cast of no length reverts). Whatever creates a
 * unit seeds its deploy countdown, so an ordinary ground troop deploys correctly; the morph,
 * ability and dash countdowns have no writer, so the state-visit blocks that count them down are
 * inert. A unit that reaches the deploying state from spawn pathfinding or staggered placement
 * would arrive with no countdown and never leave it; no plain ground unit does.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: while a deploy countdown runs only clone setup and the two following"
            + " states may be set. The actions a state change carries live in GridStateSetter;"
            + " the two setters here run none of them and are for tests of single visits.")
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
   * while the entity's deploy countdown is still running, and runs no other action.
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
