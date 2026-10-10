/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.target;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The balance switches the targeting visit and the validator read that are not already part of
 * {@link PathfindingGlobals}.
 *
 * <p>Every value is the published one. A lone unit walking to a tower, as in the Knight walks of
 * the reference battles, never reaches six of these switches, so those walks say nothing about them
 * either way.
 *
 * @param rangeExtensionToKeepTarget extra range, in game units, a unit is allowed before it gives
 *     up a reference it already has
 * @param attackFinishTimeMs milliseconds the visit lets an attack run on after the unit's reference
 *     has gone out of range under an attack sequence; the same published value the entity state
 *     visit counts its attack-finish latch against
 * @param preserveTargetIfHitStarted true when a reference that is slightly out of range is kept
 *     while a projectile attack is already under way
 * @param currentTargetIgnoresPendingDamage true when an owner that has hit may keep a reference the
 *     pending-damage rule refuses, one its shots in flight will kill
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
 * @param pendingDamageIgnoreIfDurationLess longest pending-damage duration, in milliseconds, the
 *     pending-damage rule acts on: damage that lands later than this refuses no target
 * @param cancelHitFromLongDistance true when a hit whose target has left the attack range during
 *     the wind-up is cancelled and lands on nothing
 * @param cancelHitFromLongDistanceRange extra range, in game units, the cancel test allows on top
 *     of the attack range before it cancels the hit
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Every switch carries its published value. Six of them are held by the value test and not"
            + " by behaviour: a lone unit walking to a tower, as in the Knight walks of the"
            + " reference battles (smoke-c1/knight, knight_centre_s0 and knight_behind_king_s0,"
            + " golden-gaps-v1/walk_knight_left_inner and walk_knight_right_rear), never reaches"
            + " them, and whether another reference battle does is not checked.")
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
    int pendingDamageIgnoreIfDurationLess,
    boolean cancelHitFromLongDistance,
    int cancelHitFromLongDistanceRange) {

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
        .cancelHitFromLongDistance(true)
        .cancelHitFromLongDistanceRange(1500)
        .build();
  }
}
