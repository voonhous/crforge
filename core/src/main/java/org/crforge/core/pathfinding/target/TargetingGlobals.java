package org.crforge.core.pathfinding.target;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The balance switches the targeting visit and the validator read that are not already part of
 * {@link PathfindingGlobals}.
 *
 * <p>Every value is the published one. The reference trajectories were produced with six of these
 * switches left at zero, which is not a decision about them: a lone unit walking to a tower never
 * reaches any of the six, so the trajectories say nothing about them either way and are reproduced
 * identically with the published values.
 *
 * @param rangeExtensionToKeepTarget extra range, in game units, a unit is allowed before it gives
 *     up a reference it already has
 * @param attackFinishTimeMs milliseconds the visit lets an attack run on after the unit's reference
 *     has gone out of range under an attack sequence; the same published value the entity state
 *     visit counts its attack-finish latch against
 * @param preserveTargetIfHitStarted true when a reference that is slightly out of range is kept
 *     while a projectile attack is already under way
 * @param currentTargetIgnoresPendingDamage true when a reference that has taken damage may be kept
 *     although the ordinary check rejects it
 * @param compareUsingHitStarted true when the hit timing is read from the hit-started flag rather
 *     than recomputed from the attack timer
 * @param clearSpecialLoadOnReferenceLoss true when losing the reference also clears a pending
 *     special load
 * @param loadFirstHitResetTimerWhenZapped true when dropping the reference reloads the wind-up of a
 *     unit whose wind-up runs before its first hit
 * @param loadFirstHitResetTimerAfterAttack true when such a unit reloads its wind-up after every
 *     attack
 * @param loadFirstHitKeepLoadedAfterDiscard true when such a unit stays loaded after a hit that did
 *     not land
 * @param pendingDamageIgnoreIfDurationLess longest pending-damage duration, in milliseconds, that
 *     still lets the validator keep a dying target
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Every switch carries its published value. No reference walk reaches six of"
            + " them, so those six are held by the value test and not by behaviour.")
@Builder(toBuilder = true)
public record TargetingGlobals(
    int rangeExtensionToKeepTarget,
    int attackFinishTimeMs,
    boolean preserveTargetIfHitStarted,
    boolean currentTargetIgnoresPendingDamage,
    boolean compareUsingHitStarted,
    boolean clearSpecialLoadOnReferenceLoss,
    boolean loadFirstHitResetTimerWhenZapped,
    boolean loadFirstHitResetTimerAfterAttack,
    boolean loadFirstHitKeepLoadedAfterDiscard,
    int pendingDamageIgnoreIfDurationLess) {

  /** The switches as published for the standard 1v1 mode. */
  public static TargetingGlobals standard1v1() {
    return TargetingGlobals.builder()
        .rangeExtensionToKeepTarget(PathfindingGlobals.LOGIC_RANGE_EXTENSION_TO_KEEP_TARGET)
        .attackFinishTimeMs(250)
        .preserveTargetIfHitStarted(true)
        .currentTargetIgnoresPendingDamage(true)
        .compareUsingHitStarted(true)
        .clearSpecialLoadOnReferenceLoss(true)
        .loadFirstHitResetTimerWhenZapped(true)
        .loadFirstHitResetTimerAfterAttack(true)
        .loadFirstHitKeepLoadedAfterDiscard(true)
        .pendingDamageIgnoreIfDurationLess(600)
        .build();
  }
}
