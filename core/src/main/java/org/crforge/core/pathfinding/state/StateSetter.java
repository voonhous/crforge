package org.crforge.core.pathfinding.state;

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
 */
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
