package org.crforge.core.pathfinding.target;

import lombok.Builder;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The balance switches the targeting visit and the validator read that are not already part of
 * {@link PathfindingGlobals}.
 *
 * <p>Five of the values below are answered as fixed constants by the reference trajectories rather
 * than taken from the published data, and {@link #standard1v1()} answers them the same way so the
 * simulation reproduces those trajectories exactly. Each one says so in its Javadoc.
 *
 * @param rangeExtensionToKeepTarget extra range, in game units, a unit is allowed before it gives
 *     up a reference it already has
 * @param attackFinishTimeMs milliseconds a unit keeps attacking after losing its reference;
 *     supplied as zero, so the attack ends on the tick the reference is lost
 * @param preserveTargetIfHitStarted true when a reference that is slightly out of range is kept
 *     while a projectile attack is already under way
 * @param currentTargetIgnoresPendingDamage true when a reference that has taken damage may be kept
 *     although the ordinary check rejects it; supplied as false
 * @param compareUsingHitStarted true when the hit timing is read from the hit-started flag rather
 *     than recomputed from the attack timer; supplied as false, so the attack timer decides
 * @param clearSpecialLoadOnReferenceLoss true when losing the reference also clears a pending
 *     special load; supplied as false
 * @param loadFirstHitResetTimerWhenZapped true when dropping the reference reloads the wind-up of a
 *     unit whose wind-up runs before its first hit
 * @param loadFirstHitResetTimerAfterAttack true when such a unit reloads its wind-up after every
 *     attack; supplied as false
 * @param loadFirstHitKeepLoadedAfterDiscard true when such a unit stays loaded after a hit that did
 *     not land; supplied as false
 * @param pendingDamageIgnoreIfDurationLess longest pending-damage duration, in milliseconds, that
 *     still lets the validator keep a dying target
 */
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

  /**
   * The switches as the standard 1v1 mode answers them for the reference trajectories: the
   * published range extension and wind-up reload, and the five switches listed above answered as
   * constants.
   */
  public static TargetingGlobals standard1v1() {
    return TargetingGlobals.builder()
        .rangeExtensionToKeepTarget(PathfindingGlobals.LOGIC_RANGE_EXTENSION_TO_KEEP_TARGET)
        .attackFinishTimeMs(0)
        .preserveTargetIfHitStarted(true)
        .currentTargetIgnoresPendingDamage(false)
        .compareUsingHitStarted(false)
        .clearSpecialLoadOnReferenceLoss(false)
        .loadFirstHitResetTimerWhenZapped(true)
        .loadFirstHitResetTimerAfterAttack(false)
        .loadFirstHitKeepLoadedAfterDiscard(false)
        .pendingDamageIgnoreIfDurationLess(600)
        .build();
  }
}
