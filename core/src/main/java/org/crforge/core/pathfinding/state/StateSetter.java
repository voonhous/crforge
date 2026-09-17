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
 *
 * <p><b>What this interface does not carry.</b> The standard game also runs an action when an
 * entity enters or leaves certain states, and none of those actions is ported. Entering the
 * deploying state seeds the deploy countdown and its copy from the DeployTime column; leaving the
 * deploying, standing or moving state clears the countdown; entering the morphing state seeds the
 * morph countdown; entering the casting state seeds the ability countdown and its warning; and
 * entering the dashing state raises the dashing flag and clears the landing countdown. The grid
 * driver seeds the deploy countdown itself when it builds a troop's view, so an ordinary ground
 * troop deploys correctly, but the morph, ability and dash countdowns have no writer anywhere and
 * the blocks of the entity state visit that count them down are therefore inert.
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
