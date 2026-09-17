package org.crforge.core.pathfinding.target;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * The per-tick targeting pass of one entity.
 *
 * <p>It runs before the movement pass, once per tick, and does five things in this order: it steps
 * the two leading countdowns, applies a dash's hits while the entity is dashing, steps the
 * remaining timers, decides whether the reference it has is still worth keeping and re-selects one
 * when it is not, and finally decides whether an attack happens this tick. An attack puts the
 * entity into the attacking state and advances the attack timer; a hit lands whenever that timer
 * crosses a multiple of the hit speed, and is handed to a {@link HitSink}.
 *
 * <p>Two things the visit decides are for the caller to carry out and are reported through {@link
 * TargetingOutcome}: the request to plan a route to a newly chosen target, and the resume request
 * that follows a dropped reference or a finished attack.
 *
 * <p>Timers are milliseconds. The visit never advances one by more than {@link
 * TargetingQueries#timeStepMs()} in a tick.
 */
public final class TargetingVisit {

  /** Entity states in which the targeting pass does nothing at all. */
  private static final int STATE_SPAWN_PATHFIND = GridEntityState.SPAWN_PATHFIND;

  private static final int STATE_INGAME_PATHFIND = GridEntityState.INGAME_PATHFIND;
  private static final int STATE_MORPHING = GridEntityState.MORPHING;
  private static final int STATE_DASHING = GridEntityState.DASHING;
  private static final int STATE_JUMPING = GridEntityState.JUMPING;
  private static final int STATE_ATTACKING = GridEntityState.ATTACKING;
  private static final int STATE_CASTING = GridEntityState.CASTING;

  /** Extra range, in game units, a dash keeps its reference over. */
  private static final int DASH_KEEP_RANGE_MARGIN = 1000;

  /** Extension used by the range check that keeps a projectile attack's target. */
  private static final int PROJECTILE_KEEP_EXTENSION = 500;

  private TargetingVisit() {
    // Utility class
  }

  /**
   * Runs one visit.
   *
   * @param t the entity's targeting component
   * @param e the entity itself; the visit may change its state and its pending flags
   * @param movement the entity's movement component as the visit sees it, or null when it has none
   * @param queries the answers the visit needs from outside the targeting pass
   * @param outcome collects the route and resume requests the visit makes
   */
  public static void targetingVisit(
      TargetingState t,
      GridEntity e,
      TargetingMovementView movement,
      TargetingQueries queries,
      TargetingOutcome outcome) {
    TargetingConfig cfg = t.getConfig();
    TargetingGlobals globals = t.getGlobals();
    int step = TargetingQueries.TICK_MS;

    boolean skipSelection = t.isSkipSelectionNextVisit();
    t.setSkipSelectionNextVisit(false);
    t.setLoadTimerMs(Math.max(t.getLoadTimerMs(), step) - step);
    t.setRetargetCooldownMs(Math.max(t.getRetargetCooldownMs(), step) - step);

    int state = e.getState();
    if (state == STATE_SPAWN_PATHFIND
        || state == STATE_INGAME_PATHFIND
        || state == STATE_MORPHING) {
      return;
    }
    if (t.getReference() != null) {
      t.setLastReferenceX(t.getReference().x());
      t.setLastReferenceY(t.getReference().y());
      t.setLastReferenceZ(t.getReference().z());
    }
    if (state == STATE_DASHING) {
      applyDashHits(t, e, movement, cfg, queries);
    }

    boolean pushbackBlocks = false;
    boolean attackPushbackRuns = false;
    if (movement != null) {
      pushbackBlocks = movement.getPushbackInFlight() != 0 || movement.getBlockCountdownMs() > 0;
      attackPushbackRuns = movement.getAttackPushback() != 0;
    }
    if (cfg.specialChargeTime() >= 1) {
      t.setSpecialChargeTimerMs(t.getSpecialChargeTimerMs() + step);
    }
    int attackBlockOnEntry = t.getAttackBlockTimerMs();
    if (attackBlockOnEntry >= 1) {
      t.setAttackBlockTimerMs(Math.max(attackBlockOnEntry, step) - step);
      if (t.getAttackBlockTimerMs() == 0) {
        t.clearAttack();
      }
    }
    if (t.getResumeDelayMs() >= 1) {
      t.setResumeDelayElapsedMs(t.getResumeDelayElapsedMs() + step);
      if (t.getResumeDelayElapsedMs() < t.getResumeDelayMs()) {
        return;
      }
      t.setResumeDelayElapsedMs(0);
      t.setResumeDelayMs(0);
      if (t.isVisitSuspended()) {
        return;
      }
      t.setAttackTimerMs(1);
      t.setAttackResumeFlag(true);
    } else if (t.isVisitSuspended()) {
      return;
    }
    if (t.getVisitHoldMs() >= 1) {
      t.setVisitHoldMs(Math.max(t.getVisitHoldMs(), step) - step);
      return;
    }

    boolean referenceInRange = checkReferenceStillReachable(t, cfg, globals, queries);
    if (cfg.reselectsEveryVisit() && t.getRetargetCooldownMs() == 0) {
      clearReference(t, e, outcome);
    }
    t.setKeptByPendingDamageCheck(false);
    boolean mayAttack = decideWhetherToKeepTheReference(t, queries, cfg, globals, referenceInRange);
    if (!mayAttack) {
      reselect(t, e, cfg, attackBlockOnEntry, skipSelection, queries, outcome);
    }
    keepDashWindupConsistent(t, e, cfg, queries);

    if (attackBlockOnEntry > 0) {
      tail(t, e, cfg, globals, outcome, false);
      return;
    }
    if (state == STATE_DASHING || state == STATE_JUMPING) {
      t.setDashWindupMs(0);
      t.clearAttack();
      tail(t, e, cfg, globals, outcome, false);
      return;
    }
    runAttackLogic(
        t, e, movement, cfg, globals, queries, outcome, pushbackBlocks, attackPushbackRuns);
  }

  /**
   * Drops the reference and prepares the component for having none. This is the same store as
   * taking a new reference, with a null one.
   */
  public static void clearReference(TargetingState t, GridEntity e, TargetingOutcome outcome) {
    ReferenceSetter.setReference(t, null, false, false, false, null, outcome);
  }

  // -------------------------------------------------------------------------------------------
  // Dash hits
  // -------------------------------------------------------------------------------------------

  /**
   * Applies the hits of a dash as it passes through the entities around the unit. An entity is hit
   * once per dash, must be on the other side, must not already be on the hit list and must be on a
   * layer the unit can attack. A dash that reaches a building is cut short.
   */
  private static void applyDashHits(
      TargetingState t,
      GridEntity e,
      TargetingMovementView movement,
      TargetingConfig cfg,
      TargetingQueries queries) {
    e.setPendingFlags(e.getPendingFlags() | TargetingFlags.DASHING);
    if (queries.dashDamageIndex(0) < 1) {
      return;
    }
    List<TargetView> found = queries.dashCandidates(e.getX(), e.getY(), e.getCollisionRadius());
    for (TargetView other : found) {
      int vectorX = other.x() - e.getX();
      int vectorY = other.y() - e.getY();
      int damageIndex = queries.dashDamageIndex(0);
      if (other.getEntity().getSide() == e.getSide()) {
        continue;
      }
      if (t.getHitTargetIds().contains(other.id())) {
        continue;
      }
      if (!cfg.attacksAir() && other.air()) {
        continue;
      }
      if (!cfg.attacksGround() && cfg.attacksAir() && !other.air()) {
        continue;
      }
      t.getHitTargetIds().add(other.id());
      queries.applyDashHit(other, damageIndex, vectorX, vectorY);
      if (other.building() && movement != null) {
        movement.setDashTimeMs(0);
      }
      if (cfg.dashingPushback() >= 1) {
        queries.applyDashPushback(other, e.getX(), e.getY(), cfg.dashingPushback());
      }
    }
    queries.releaseDashCandidates(found);
  }

  // -------------------------------------------------------------------------------------------
  // Keeping or dropping the reference
  // -------------------------------------------------------------------------------------------

  /**
   * Whether the reference is still close enough to attack. A unit firing a projectile keeps a
   * reference that has slipped a little further away while its current hit is still under way.
   */
  private static boolean checkReferenceStillReachable(
      TargetingState t, TargetingConfig cfg, TargetingGlobals globals, TargetingQueries queries) {
    TargetView reference = t.getReference();
    if (reference == null) {
      return false;
    }
    int extension = cfg.isBuilding() ? 0 : globals.rangeExtensionToKeepTarget();
    boolean inRange = RangeTest.referenceInRange(t, reference, extension);
    if (!inRange && globals.preserveTargetIfHitStarted() && cfg.hasProjectile()) {
      if (RangeTest.referenceInRange(t, reference, PROJECTILE_KEEP_EXTENSION)
          && cfg.hitSpeed() != 0) {
        inRange = t.getAttackTimerMs() % cfg.hitSpeed() > TargetingQueries.TICK_MS;
      }
    }
    return inRange;
  }

  /** Whether the reference the component holds may be attacked this tick. */
  private static boolean decideWhetherToKeepTheReference(
      TargetingState t,
      TargetingQueries queries,
      TargetingConfig cfg,
      TargetingGlobals globals,
      boolean referenceInRange) {
    TargetView reference = t.getReference();
    if (reference == null) {
      return referenceInRange;
    }
    if (!referenceInRange) {
      return false;
    }
    if (queries.validateReference(ReferenceValidator.MODE_TAKE)) {
      return true;
    }
    if (t.isHitStarted()
        && cfg.keepTargetWithPendingDamage()
        && globals.currentTargetIgnoresPendingDamage()) {
      boolean kept = queries.validateReference(ReferenceValidator.MODE_RECHECK);
      t.setKeptByPendingDamageCheck(kept);
      return kept;
    }
    return false;
  }

  /**
   * Runs the selection and the three checks that can drop a reference just taken: an
   * attack-sequence unit gives up a target it can no longer reach, a target that has started
   * pathfinding away is dropped, a target outside the dash reach is dropped and an untargetable one
   * is dropped.
   */
  private static void reselect(
      TargetingState t,
      GridEntity e,
      TargetingConfig cfg,
      int attackBlockOnEntry,
      boolean skipSelection,
      TargetingQueries queries,
      TargetingOutcome outcome) {
    if (cfg.attackSequenceMode() != 0 && t.getReference() != null) {
      if (queries.validateReference(ReferenceValidator.MODE_TAKE)
          && !RangeTest.referenceInRange(t, t.getReference(), 0)
          && t.getAttackTimerMs() >= 1) {
        clearReference(t, e, outcome);
        t.setTargetLostTimerMs(1);
      }
    }
    if (t.getRetargetCooldownMs() == 0) {
      boolean idle =
          !(attackBlockOnEntry > 0 || t.getTargetLostTimerMs() != 0 || t.getBurstProgressMs() >= 1);
      if (idle) {
        if (!skipSelection) {
          t.setReference(queries.runSelection());
        }
      } else if (!skipSelection && cfg.reselectsEveryVisit()) {
        t.setReference(queries.runSelection());
      }
    }
    if (t.getReference() == null) {
      return;
    }
    if (t.getReference().getEntity().getState() == STATE_INGAME_PATHFIND) {
      clearReference(t, e, outcome);
    }
    if (t.getReference() == null) {
      return;
    }
    if (t.getDashWindupMs() >= 1
        && !RangeTest.rangeTest(
            t.getReference(),
            e.getX(),
            e.getY(),
            cfg.dashMaxRange() + DASH_KEEP_RANGE_MARGIN,
            0,
            false)) {
      clearReference(t, e, outcome);
    }
    if (t.getReference() != null
        && (t.getReference().getEntity().getFlags() & TargetingFlags.UNTARGETABLE) != 0) {
      clearReference(t, e, outcome);
    }
  }

  /** Clears a dash wind-up that can no longer lead anywhere. */
  private static void keepDashWindupConsistent(
      TargetingState t, GridEntity e, TargetingConfig cfg, TargetingQueries queries) {
    if (cfg.dashCooldown() <= 0) {
      return;
    }
    if (t.getDashWindupMs() >= 1 && (e.getFlags() & TargetingFlags.NO_DASH) != 0) {
      t.setDashWindupMs(0);
    }
    boolean keep = t.getReference() != null && queries.dashRangeReached(t.getReference());
    if (keep) {
      return;
    }
    if (queries.hasBuffComponent()) {
      if (queries.timeStepMs() != 0) {
        t.setDashWindupMs(0);
      }
    } else {
      t.setDashWindupMs(0);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The attack
  // -------------------------------------------------------------------------------------------

  /** Decides whether the unit attacks this tick, and runs the attack when it does. */
  private static void runAttackLogic(
      TargetingState t,
      GridEntity e,
      TargetingMovementView movement,
      TargetingConfig cfg,
      TargetingGlobals globals,
      TargetingQueries queries,
      TargetingOutcome outcome,
      boolean pushbackBlocks,
      boolean attackPushbackRuns) {
    int hitSpeed = cfg.hitSpeed();
    if (t.getReference() == null) {
      boolean idleAttack =
          t.isHitInProgressWithoutReference()
              || t.getAttackBlockTimerMs() < 0
              || t.getBurstProgressMs() >= 1;
      if (idleAttack && !queries.continueWithoutReference()) {
        if (hitSpeed == 0) {
          return;
        }
        int burstDelay = cfg.burstDelay();
        int burstsBefore = burstDelay != 0 ? t.getBurstProgressMs() / burstDelay : 0;
        attack(
            t,
            e,
            movement,
            cfg,
            globals,
            queries,
            outcome,
            hitSpeed,
            burstDelay,
            burstsBefore,
            pushbackBlocks,
            attackPushbackRuns);
        return;
      }
      if (t.getTargetLostTimerMs() >= 1) {
        t.setTargetLostTimerMs(t.getTargetLostTimerMs() + TargetingQueries.TICK_MS);
        int limit =
            cfg.overrideAttackFinishTime() ? cfg.attackFinishTime() : globals.attackFinishTimeMs();
        if (t.getTargetLostTimerMs() < limit) {
          tail(t, e, cfg, globals, outcome, false);
          return;
        }
        t.clearAttack();
        tail(t, e, cfg, globals, outcome, false);
        return;
      }
      if (!cfg.isBuilding() || queries.buildingKeepsAttacking()) {
        attackReset(t, outcome);
      }
      tail(t, e, cfg, globals, outcome, false);
      return;
    }
    if (hitSpeed == 0) {
      return;
    }
    int burstDelay = cfg.burstDelay();
    int burstsBefore = burstDelay != 0 ? t.getBurstProgressMs() / burstDelay : 0;
    attack(
        t,
        e,
        movement,
        cfg,
        globals,
        queries,
        outcome,
        hitSpeed,
        burstDelay,
        burstsBefore,
        pushbackBlocks,
        attackPushbackRuns);
  }

  /** Clears the attack fields and asks the entity to resume moving, unless a burst is pending. */
  static void attackReset(TargetingState t, TargetingOutcome outcome) {
    if (t.getBurstDelayTimerMs() != 0) {
      return;
    }
    t.clearAttack();
    outcome.setResumeRequested(true);
  }

  /** The attack decision itself. */
  private static void attack(
      TargetingState t,
      GridEntity e,
      TargetingMovementView movement,
      TargetingConfig cfg,
      TargetingGlobals globals,
      TargetingQueries queries,
      TargetingOutcome outcome,
      int hitSpeed,
      int burstDelay,
      int burstsBefore,
      boolean pushbackBlocks,
      boolean attackPushbackRuns) {
    TargetView reference = t.getReference();
    int attackTimerOnEntry = t.getAttackTimerMs();
    boolean withinPlainRange = reference != null && RangeTest.referenceInRange(t, reference, 0);
    boolean mayNotAttack = (e.getFlags() & TargetingFlags.NO_ATTACK) != 0;
    if (mayNotAttack) {
      t.clearAttack();
      t.setSpecialChargeTimerMs(0);
    }

    boolean midHit;
    if (globals.compareUsingHitStarted()) {
      midHit = t.isHitInProgress();
    } else {
      midHit = t.getAttackTimerMs() % hitSpeed > TargetingQueries.TICK_MS;
    }
    boolean timingReady = t.getBurstProgressMs() <= 0 ? midHit : true;

    if (cfg.specialRange() >= 1
        && !t.isSpecialLoadPending()
        && (e.getFlags() & TargetingFlags.NO_SPECIAL_ATTACK) == 0
        && reference != null
        && RangeTest.rangeTest(
            reference, e.getX(), e.getY(), cfg.specialRange(), cfg.specialMinRange(), false)
        && !t.getHitTargetIds().contains(reference.id())
        && !(reference.building() && cfg.specialIgnoreBuildings())) {
      t.setSpecialLoadTimerMs(cfg.specialLoadTime());
      t.setSpecialLoadPending(true);
    }
    boolean specialReady = t.isSpecialLoadPending() && t.getSpecialLoadTimerMs() < 1;
    if (t.isSpecialLoadPending() && reference != null && t.getSpecialLoadTimerMs() >= 1) {
      t.setSpecialLoadTimerMs(Math.max(t.getSpecialLoadTimerMs() - queries.timeStepMs(), 0));
      tail(t, e, cfg, globals, outcome, false);
      return;
    }
    if (reference != null && (!t.isSpecialLoadPending() || t.getSpecialLoadTimerMs() < 1)) {
      boolean inDashRange = queries.dashRangeReached(reference);
      if (!mayNotAttack && inDashRange && !timingReady) {
        if (t.getDashWindupMs() <= 0) {
          t.setDashWindupMs(cfg.dashCooldown());
        }
        t.setDashWindupMs(Math.max(t.getDashWindupMs() - queries.timeStepMs(), 0));
        if (t.getDashWindupMs() > 0) {
          tail(t, e, cfg, globals, outcome, false);
          return;
        }
        t.setDashWindupMs(cfg.dashLandingTime() > 0 ? 0 : cfg.dashCooldown());
        queries.startDash(reference, reference.x(), reference.y(), reference.radius());
        tail(t, e, cfg, globals, outcome, false);
        return;
      }
    }

    boolean proceed;
    if (mayNotAttack) {
      proceed = timingReady && !pushbackBlocks;
    } else {
      proceed = !pushbackBlocks && (withinPlainRange || specialReady || timingReady);
    }
    if (proceed && e.getState() == STATE_CASTING && !queries.abilityInProgress()) {
      proceed = false;
    }
    if (!proceed) {
      boolean ready =
          cfg.loadFirstHit()
              ? pushbackBlocks && timingReady
              : pushbackBlocks && timingReady && cfg.loadAfterRetarget();
      if (!ready || attackPushbackRuns) {
        attackReset(t, outcome);
      } else {
        outcome.setResumeRequested(true);
      }
      tail(t, e, cfg, globals, outcome, false);
      return;
    }

    boolean specialHit = specialReady;
    if (cfg.dashCooldown() > 0) {
      t.setDashWindupMs(0);
    }
    if (queries.stateChangeApplies()) {
      e.setState(STATE_ATTACKING);
    }
    int hitsBefore = attackTimerOnEntry / hitSpeed;
    t.setAttackTimerMs(t.getAttackTimerMs() + queries.attackTimerStepMs());
    t.setBurstProgressMs(t.getBurstProgressMs() + queries.burstTimerStepMs());
    t.setHitInProgress(t.getAttackTimerMs() % hitSpeed > TargetingQueries.TICK_MS);
    if ((cfg.attackSequenceMode() & ~1) == 2) {
      t.setAttackSequenceIndex(queries.nextAttackSequenceStep(t.getAttackTimerMs()));
    }
    if (attackTimerOnEntry == 0 || t.getAttackTimerMs() / hitSpeed > hitsBefore) {
      queries.onStartingAttack();
    }
    boolean hitPending = t.getAttackTimerMs() / hitSpeed > hitsBefore;
    boolean hitReady = false;
    if (hitPending) {
      if (reference != null) {
        hitReady = t.getSpecialLoadTimerMs() == 0;
      } else if (t.isHitInProgressWithoutReference() && !queries.continueWithoutReference()) {
        hitReady = t.getSpecialLoadTimerMs() == 0;
      }
    }
    int hitsWithDashTime = (cfg.attackDashTime() + attackTimerOnEntry) / hitSpeed;
    int burstsNow = burstDelay >= 1 ? t.getBurstProgressMs() / burstDelay : 0;
    if (burstsNow > burstsBefore) {
      if (burstsNow == cfg.burst() - 1) {
        t.setBurstProgressMs(0);
        t.setBurstDelayTimerMs(TargetingQueries.TICK_MS);
      }
      hitReady = true;
    }
    if (!(specialHit || hitReady)) {
      tail(t, e, cfg, globals, outcome, false);
      return;
    }

    boolean keepsAttacking;
    if (burstDelay >= 1 && (t.getBurstProgressMs() | burstsNow) == 0) {
      t.setBurstOriginX(t.getLastReferenceX());
      t.setBurstOriginY(t.getLastReferenceY());
      t.setBurstProgressMs(TargetingQueries.TICK_MS);
      keepsAttacking = false;
      if (cfg.burstKeepTarget()) {
        t.setReference(null);
      }
    } else {
      keepsAttacking = true;
      t.setHitInProgress(false);
      t.setHitInProgressWithoutReference(false);
    }
    if (specialHit) {
      boolean listIt = cfg.specialAttacksToIgnoreList() || cfg.specialRange() <= 0;
      if (listIt) {
        t.getHitTargetIds()
            .add(t.getReference() != null ? t.getReference().id() : queries.nullTargetId());
      }
    }

    applyHits(t, cfg, globals, queries, burstDelay, burstsNow, hitsWithDashTime);
    if (cfg.attackSequenceMode() == 1 && cfg.attackSequenceLength() != 0) {
      t.setAttackSequenceIndex((t.getAttackSequenceIndex() + 1) % cfg.attackSequenceLength());
    }
    tail(t, e, cfg, globals, outcome, keepsAttacking);
  }

  /** Sends the hit, and the extra hits of a multi-target attack, to the sink. */
  private static void applyHits(
      TargetingState t,
      TargetingConfig cfg,
      TargetingGlobals globals,
      TargetingQueries queries,
      int burstDelay,
      int burstsNow,
      int hitsWithDashTime) {
    TargetView target = t.getReference();
    int multiple = cfg.multipleTargets();
    int index = burstsNow;
    if (burstDelay >= 1) {
      if (burstsNow - 1 >= 0 && multiple >= 2) {
        TargetView found = queries.multiTarget(burstsNow - 1, false);
        target = found != null ? found : (cfg.allTargetsHit() ? t.getReference() : null);
      }
    } else {
      index = multiple < 2 ? -1 : 0;
    }
    int extra = queries.hasBuffComponent() ? queries.extraTargetCount() : 0;
    boolean landed = queries.hitSink().hit(target, index, extra, index == -1);
    if (cfg.loadFirstHit() && globals.loadFirstHitResetTimerAfterAttack()) {
      if (landed && globals.loadFirstHitKeepLoadedAfterDiscard()) {
        t.setAttackTimerMs(0);
        t.setLoadTimerMs(0);
      } else {
        t.setHitInProgress(false);
        t.setHitInProgressWithoutReference(false);
        t.setBurstProgressMs(0);
        t.setAttackTimerMs(0);
        t.setLoadTimerMs(cfg.loadTime());
        t.setAttackResumeFlag(false);
        t.setTargetLostTimerMs(0);
        t.setResumeDelayMs(0);
        t.setResumeDelayElapsedMs(0);
      }
    }
    if (multiple >= 2 && cfg.burst() == 0) {
      boolean unique = cfg.uniqueMultipleTargets();
      List<TargetView> remaining = unique ? new ArrayList<>(queries.uniqueMultiTargets()) : null;
      int last = multiple - 1;
      int i = 0;
      while (true) {
        i++;
        TargetView found = queries.multiTarget(i - 1, remaining != null);
        TargetView hitTarget = found;
        if (found == null) {
          hitTarget = cfg.allTargetsHit() ? t.getReference() : null;
        }
        if (hitTarget != null) {
          queries.hitSink().hit(hitTarget, i, extra, i == last);
          if (remaining != null && !remaining.isEmpty()) {
            int k = remaining.indexOf(hitTarget);
            if (k >= 0) {
              remaining.set(k, remaining.get(remaining.size() - 1));
              remaining.remove(remaining.size() - 1);
            }
          }
        }
        if (i == last) {
          break;
        }
      }
    }
  }

  /**
   * The tail every path runs: the burst delay is stepped, a unit that has just attacked may drop
   * its reference, and a unit that has lost its reference may drop a pending special load.
   */
  private static void tail(
      TargetingState t,
      GridEntity e,
      TargetingConfig cfg,
      TargetingGlobals globals,
      TargetingOutcome outcome,
      boolean justAttacked) {
    if (t.getBurstDelayTimerMs() >= 1) {
      int next = t.getBurstDelayTimerMs() + TargetingQueries.TICK_MS;
      t.setBurstDelayTimerMs(next < cfg.burstDelay() ? next : 0);
    }
    if (justAttacked && cfg.dropsReferenceAfterAttack()) {
      clearReference(t, e, outcome);
    }
    if (globals.clearSpecialLoadOnReferenceLoss()
        && t.getReference() == null
        && t.isMovementComponentActive()
        && t.isSpecialLoadPending()) {
      t.setSpecialLoadPending(false);
      t.setSpecialLoadTimerMs(0);
    }
  }
}
