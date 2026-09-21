package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * How many game units an entity may move during one visit.
 *
 * <p>The budget is the entity's speed column scaled by the percent modifiers currently on it and,
 * once a charge is complete, by the charge multiplier. Several conditions short-circuit it to zero
 * before the state is even looked at: a movement-forbidding flag, a targeting component that is
 * winding up a dash or holding the entity, and the entity's own movement-blocking countdown. Some
 * states answer a different column instead of the ordinary speed, and six answer zero.
 *
 * <p>The follower divides the budget by 250 to decide how many extra displacements a visit takes,
 * so a speed of 60 gives exactly one displacement of 60 units per tick.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line; held for a walking unit at its base"
            + " speed, at the one speed the reference walks use. Not held: the held-position"
            + " flags, the attack hold, the block countdown, jump speed, clone setup and the"
            + " pathfind speed columns. Not modelled: status effects never reach the budget,"
            + " so a slowed or raged unit walks at base speed.")
public final class SpeedBudget {

  /** Percent the modifier arithmetic is expressed in. */
  private static final int PERCENT = 100;

  /** Fraction of a cell a clone is displaced by, expressed as a numerator over 500. */
  private static final int CLONE_STEP_NUMERATOR = 250;

  /** Denominator of the clone displacement, one routing cell in game units. */
  private static final int CLONE_STEP_DENOMINATOR = 500;

  private SpeedBudget() {
    // Utility class
  }

  /**
   * Returns the entity's movement budget for this visit, in game units.
   *
   * @param entity what the budget asks about the entity
   * @param config the entity's speed columns
   * @param globals the match-wide clone distances, read only while the entity is a clone being set
   *     up
   */
  public static int speedBudget(SpeedInputs entity, SpeedConfig config, SpeedGlobals globals) {
    if ((entity.flags() & (EntityFlags.NO_MOVE | EntityFlags.NO_MOVE_ALLOW_ATTRACT)) != 0) {
      return 0;
    }
    if (entity.hasTargetingComponent()) {
      if (targetingActive(entity)) {
        return 0;
      }
      if (entity.attackHold() > 0) {
        return 0;
      }
    }
    if (entity.blockCountdownMs() > 0) {
      return 0;
    }
    int state = entity.state();
    if (GridEntityState.inMask(state, GridEntityState.SPEED_ZERO_STATES)) {
      return 0;
    }
    if (state == GridEntityState.DASHING || state == GridEntityState.JUMPING) {
      return config.jumpSpeed();
    }
    if (state == GridEntityState.SPAWN_PATHFIND) {
      return config.spawnPathfindSpeed();
    }
    if (state == GridEntityState.INGAME_PATHFIND) {
      return config.ingamePathfindSpeed();
    }
    if (state == GridEntityState.CLONE_SETUP) {
      int x =
          FixedMath.divOrZero(
              globals.cloneDistanceX() * CLONE_STEP_NUMERATOR, CLONE_STEP_DENOMINATOR);
      int y =
          FixedMath.divOrZero(
              globals.cloneDistanceY() * CLONE_STEP_NUMERATOR, CLONE_STEP_DENOMINATOR);
      return Math.max(x, y);
    }
    if (entity.hasTargetingComponent() && entity.specialLoadPending() != 0) {
      return 0;
    }
    int speed = speedModifier(entity.modifierPercents(), config.speed());
    if (!entity.hasMovementComponent() || entity.chargeProgress() < MovementState.CHARGE_COMPLETE) {
      return speed;
    }
    return FixedMath.divOrZero(config.chargeSpeedMultiplier() * speed, PERCENT);
  }

  /**
   * Applies the largest boost and the largest slow among the percent modifiers to a base speed.
   *
   * <p>Only two of the modifiers ever matter: the single largest positive percent, which replaces
   * the neutral 100, and the single largest negative percent, whose magnitude is taken off 100. The
   * two are applied one after the other, each with truncating division, so the order is observable.
   *
   * @param percents the modifiers currently on the entity, positive for a boost, negative for a
   *     slow
   * @param base the speed to scale, in game units
   */
  public static int speedModifier(int[] percents, int base) {
    int boost = PERCENT;
    int slow = 0;
    for (int percent : percents) {
      if (percent >= 1) {
        boost = Math.max(percent, boost);
      } else if (percent < 0) {
        slow = Math.max(slow, -percent);
      }
    }
    int remaining = Math.max(Math.min(PERCENT - slow, PERCENT), 0);
    return FixedMath.divOrZero(FixedMath.divOrZero(boost * base, PERCENT) * remaining, PERCENT);
  }

  /**
   * True while the entity's targeting component is winding up a dash, which stops the entity and
   * shuts all three gates.
   */
  static boolean targetingActive(SpeedInputs entity) {
    return entity.hasTargetingComponent() && entity.dashWindup() > 0;
  }
}
