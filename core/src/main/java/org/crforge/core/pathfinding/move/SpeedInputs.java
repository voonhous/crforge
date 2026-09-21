package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;

/**
 * Everything the speed budget and the three movement gates ask about one entity, gathered into one
 * value so those routines stay pure.
 *
 * <p>The three targeting fields and the countdown are what the gates actually read; the integrator
 * fills them from the entity's targeting component and from the entity itself.
 *
 * @param flags the entity's 64-bit behaviour flags
 * @param state the entity's current state
 * @param hasTargetingComponent whether the entity carries an active targeting component at all
 * @param dashWindup positive while the targeting component winds up a dash, which zeroes the budget
 *     and the facing and avoidance gates
 * @param attackHold a second positive-blocks-movement field of the targeting component; its writers
 *     are not documented, so the name describes only its effect
 * @param specialLoadPending non-zero while the targeting component holds a special attack loaded,
 *     which zeroes the ordinary budget and the avoidance gate
 * @param blockCountdownMs the <b>entity's</b> movement-blocking countdown in milliseconds, not the
 *     movement component's countdown at the same position in its own layout; while it is positive
 *     the budget and all three gates answer zero
 * @param modifierPercents the percent speed modifiers currently applied to the entity, positive for
 *     a boost and negative for a slow
 * @param hasMovementComponent whether the entity carries a movement component, which decides
 *     whether the charge multiplier is consulted at all
 * @param chargeProgress the movement component's charge progress
 */
public record SpeedInputs(
    long flags,
    int state,
    boolean hasTargetingComponent,
    int dashWindup,
    int attackHold,
    int specialLoadPending,
    int blockCountdownMs,
    int[] modifierPercents,
    boolean hasMovementComponent,
    int chargeProgress) {

  /** No modifiers at all, shared by the callers that have none. */
  private static final int[] NO_MODIFIERS = new int[0];

  /**
   * The inputs of an entity with no targeting component, no modifiers and no countdown: only its
   * flags, its state and its movement component's charge decide the budget.
   */
  public static SpeedInputs of(GridEntity entity, MovementState movement) {
    return new SpeedInputs(
        entity.getFlags(),
        entity.getState(),
        false,
        0,
        0,
        0,
        entity.getBlockCountdownMs(),
        NO_MODIFIERS,
        movement != null,
        movement == null ? MovementState.CHARGE_INACTIVE : movement.getChargeProgress());
  }

  /** Returns this entity's modifiers, never null. */
  @Override
  public int[] modifierPercents() {
    return modifierPercents == null ? NO_MODIFIERS : modifierPercents;
  }
}
