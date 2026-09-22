package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Stores a new reference on a targeting component and prepares the component for it.
 *
 * <p>Storing a reference is more than an assignment: it remembers the previous one, decides what
 * happens to the attack timing, prepares a route to the new target and remembers where that target
 * stood. Whether the attack timing is reset, rebased or left alone depends on whether the new
 * reference is already in range and on the owner's wind-up columns, in the order below. The route
 * is prepared through the outcome, at the point the store reaches it, when the caller has given the
 * outcome a preparer; otherwise the request is only recorded.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The store, the wind-up rebase, the null paths and the route preparation agree with"
            + " the reference. Not modelled: the two notifications the standard game sends when"
            + " a reference changes. Only taking a new reference is held by a fixture.")
public final class ReferenceSetter {

  /** The entity state of a unit that is dashing. */
  private static final int STATE_DASHING = 3;

  private ReferenceSetter() {
    // Utility class
  }

  /**
   * Stores {@code reference} on the component.
   *
   * @param t the component
   * @param reference the new reference, or null to give the current one up
   * @param forceRefresh true to run the whole preparation even when the reference does not change
   * @param suppressWindUpReload true to keep the wind-up as it is when the reference is given up
   * @param skipRecheck true to skip the re-check of the newly stored reference
   * @param queries supplies that re-check
   * @param outcome collects the resume and route requests the store makes
   */
  public static void setReference(
      TargetingState t,
      TargetView reference,
      boolean forceRefresh,
      boolean suppressWindUpReload,
      boolean skipRecheck,
      SelectionQueries queries,
      TargetingOutcome outcome) {
    if (t.getReference() == reference && !forceRefresh) {
      t.storeReferencePosition();
      return;
    }
    TargetingConfig cfg = t.getConfig();
    t.setPreviousReference(t.getReference());
    t.setReference(reference);
    t.setKeptByPendingDamageCheck(false);
    t.setHitStarted(false);

    if (reference != null) {
      int range = AttackRange.attackRange(t);
      int minimum = AttackRange.minRange(t);
      boolean inRange =
          RangeTest.rangeTest(
              reference, t.getOwner().getX(), t.getOwner().getY(), range, minimum, false);
      if (inRange) {
        if (cfg.attackSequenceMode() != 0 || cfg.loadAfterRetarget()) {
          t.clearAttack();
        } else if (t.getAttackTimerMs() >= 1
            && cfg.hasOnStartingAttackAction()
            && queries != null) {
          // A unit already part way through an attack keeps its timing and runs its on-starting
          // action again for the target it has just taken.
          queries.onStartingAttack();
        }
      } else {
        boolean windsUpBeforeHitting = cfg.loadFirstHit() || cfg.loadAfterRetarget();
        if (windsUpBeforeHitting && t.getAttackTimerMs() != 0) {
          // Rebase the wind-up on how far into the current hit the unit had got.
          int hitSpeed = cfg.hitSpeed();
          int intoTheHit =
              hitSpeed == 0
                  ? t.getAttackTimerMs()
                  : t.getAttackTimerMs() - (t.getAttackTimerMs() / hitSpeed) * hitSpeed;
          t.setLoadTimerMs(cfg.loadTime() - intoTheHit);
          t.setAttackTimerMs(0);
          t.setHitInProgress(false);
          t.setHitInProgressWithoutReference(false);
        } else if (!windsUpBeforeHitting) {
          t.clearAttack();
        }
      }
      if (!skipRecheck) {
        // The answer is not used; the re-check exists for what it notifies.
        queries.validate(reference, ReferenceValidator.MODE_RECHECK);
      }
    } else {
      if (cfg.loadFirstHit() || cfg.loadAfterRetarget()) {
        if (t.getGlobals().loadFirstHitResetTimerWhenZapped() && !suppressWindUpReload) {
          t.clearAttack();
          t.setLoadTimerMs(cfg.loadTime());
        }
      } else if (cfg.attackSequenceMode() != 0 || cfg.resetHitTimerWhenNoTarget()) {
        t.clearAttack();
      } else if (cfg.dashCooldown() > 0 && (cfg.dashMinRange() > 0 || cfg.dashMaxRange() >= 1)) {
        boolean jumpingDash = t.getOwner().getState() == STATE_DASHING && cfg.jumpHeight() > 0;
        if (!jumpingDash) {
          if (!(t.getDashWindupMs() >= 1 && cfg.jumpHeight() >= 1)) {
            t.setDashWindupMs(0);
          }
          outcome.setResumeRequested(true);
        }
      }
    }
    t.setSpecialLoadPending(false);
    t.setSpecialLoadTimerMs(0);
    if (t.getTargetLostTimerMs() == 0 && t.isMovementComponentActive()) {
      outcome.requestRoutePreparation();
    }
    t.storeReferencePosition();
  }
}
