package org.crforge.core.pathfinding.target;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.pathfinding.GridEntity;

/**
 * The working state of one entity's targeting component: who it is attacking and where its attack
 * timing stands.
 *
 * <p>The component belongs to exactly one entity, which it calls its owner. Everything that belongs
 * to the entity itself - its position, state and flags - stays on the {@link GridEntity}; what is
 * kept here is only what the targeting pass reads and writes.
 *
 * <p>All timers are milliseconds and advance in steps of 50, one per tick at 20 ticks per second.
 * Every field starts at zero, false or empty; the attack sequence index starts at the first step
 * and nothing resets it.
 */
@Getter
@Setter
public class TargetingState {

  /**
   * Index value meaning no step of the sequence is active: the range helpers read no step for it,
   * and the attack-timer advance steps at the ordinary pace. The component never starts there; the
   * value only arrives from outside.
   */
  public static final int NO_SEQUENCE_STEP = -1;

  // -------------------------------------------------------------------------------------------
  // The owner and its shape in the component graph
  // -------------------------------------------------------------------------------------------

  /** The entity this component belongs to. */
  private GridEntity owner;

  /** The owner's targeting columns. */
  private TargetingConfig config;

  /** The match's targeting switches. */
  private TargetingGlobals globals = TargetingGlobals.standard1v1();

  /** True while the owner's targeting component is present and enabled. */
  private boolean targetingComponentActive = true;

  /** True while the owner has a movement component that is present and enabled. */
  private boolean movementComponentActive;

  /**
   * The movement component's answer to "does my route lead away from my reference". The selector
   * consults it before it falls back to the default target.
   */
  private boolean routeLeadsAway;

  /**
   * Number of buff items on the owner that lock its current reference in place. While at least one
   * of them is present and a reference is held, the selector returns without choosing.
   */
  private int targetLockingBuffs;

  // -------------------------------------------------------------------------------------------
  // The reference
  // -------------------------------------------------------------------------------------------

  /** The target the owner is attacking, or null when it has none. */
  private TargetView reference;

  /** The target held before the current one. */
  private TargetView previousReference;

  /** True when the pending-damage re-check kept the reference this visit. */
  private boolean keptByPendingDamageCheck;

  /** True once a hit on the current reference has started. */
  private boolean hitStarted;

  /** Position of the reference as it was at the start of this visit, along the arena's width. */
  private int lastReferenceX;

  /** Position of the reference as it was at the start of this visit, along the arena's length. */
  private int lastReferenceY;

  /** Height of the reference as it was at the start of this visit. */
  private int lastReferenceZ;

  // -------------------------------------------------------------------------------------------
  // Attack timing
  // -------------------------------------------------------------------------------------------

  /** Countdown that blocks re-selection while it runs. */
  private int retargetCooldownMs;

  /** Index of the active attack sequence step, or {@link #NO_SEQUENCE_STEP}; the first step. */
  private int attackSequenceIndex;

  /**
   * Elapsed attack time; a hit lands whenever it crosses a multiple of the hit speed. An attack
   * that starts from zero is first credited the part of the wind-up that has already run down.
   */
  private int attackTimerMs;

  /**
   * The load countdown: how much of the wind-up is still to run. It loses one tick per visit, every
   * hit reloads it with the load time, and an attack starting from zero is credited the load time
   * less what remains here. It starts at zero, so a fresh unit is credited its whole load.
   */
  private int loadTimerMs;

  /**
   * Two flags a component restored from a saved battle may carry. Either one makes the next
   * attack-timer advance round the attack time up to the next multiple of the hit speed instead of
   * stepping it, and both are cleared by that advance. Nothing in a live battle sets them.
   */
  private boolean attackTimeRoundUpA;

  /** The second of the two restore flags; see {@link #attackTimeRoundUpA}. */
  private boolean attackTimeRoundUpB;

  /** True when the last attack-timer advance rounded the attack time up. Read by nothing. */
  private boolean attackTimeRoundedUp;

  /**
   * Flag raised together with the attack timer when the post-hit pause ends; its readers are not
   * documented.
   */
  private boolean attackResumeFlag;

  /** True while a hit is in progress on the current reference. */
  private boolean hitInProgress;

  /** True while a hit is in progress although the reference has already been given up. */
  private boolean hitInProgressWithoutReference;

  /** Countdown that suspends the attack; while it runs the visit only advances its timers. */
  private int attackBlockTimerMs;

  /** True while the whole visit is suspended after its timers have been advanced. */
  private boolean visitSuspended;

  /** Elapsed part of the resume delay. */
  private int resumeDelayElapsedMs;

  /** Length of the resume delay; zero when no delay is running. */
  private int resumeDelayMs;

  /** Countdown that makes the visit return as soon as its two leading timers have stepped. */
  private int visitHoldMs;

  /** Countdown that runs after the reference is lost and ends the attack when it expires. */
  private int targetLostTimerMs;

  // -------------------------------------------------------------------------------------------
  // Special attack and dash
  // -------------------------------------------------------------------------------------------

  /** True while a special attack is loaded or loading. */
  private boolean specialLoadPending;

  /** Remaining wind-up of the special attack. */
  private int specialLoadTimerMs;

  /** Elapsed charge time of the special attack. */
  private int specialChargeTimerMs;

  /** Remaining wind-up before a dash starts; a positive value also stops the owner moving. */
  private int dashWindupMs;

  // -------------------------------------------------------------------------------------------
  // Bursts
  // -------------------------------------------------------------------------------------------

  /** Elapsed time of the running burst; a hit lands whenever it crosses a multiple of the delay. */
  private int burstProgressMs;

  /** Position the burst started aiming at, along the arena's width. */
  private int burstOriginX;

  /** Position the burst started aiming at, along the arena's length. */
  private int burstOriginY;

  /** Countdown between the end of one burst and the start of the next. */
  private int burstDelayTimerMs;

  // -------------------------------------------------------------------------------------------
  // Lists and switches
  // -------------------------------------------------------------------------------------------

  /** Ids of the entities this component has already hit and must not take again. */
  private final List<Integer> hitTargetIds = new ArrayList<>();

  /** True to skip the alive test the validator would otherwise apply to a target. */
  private boolean aliveCheckBypass;

  /** True to skip the candidate selection in the next visit. */
  private boolean skipSelectionNextVisit;

  /**
   * Counter kept for the hit application, which is out of scope for this class; the targeting visit
   * never reads it. Carried so the component's reset values are complete.
   */
  private int hitBookkeepingCount;

  /**
   * Flag kept for the hit application, which is out of scope for this class; the targeting visit
   * never reads it. Carried so the component's reset values are complete.
   */
  private int hitBookkeepingFlag;

  /**
   * Clears the fields that describe an attack in progress: the attack timer, both hit-in-progress
   * flags, the burst progress, the resume flag, the target-lost countdown and the resume delay.
   */
  public void clearAttack() {
    attackTimerMs = 0;
    hitInProgress = false;
    hitInProgressWithoutReference = false;
    burstProgressMs = 0;
    attackResumeFlag = false;
    targetLostTimerMs = 0;
    resumeDelayElapsedMs = 0;
    resumeDelayMs = 0;
  }

  /**
   * Remembers the current reference's position, or leaves the last one in place when there is none.
   */
  public void storeReferencePosition() {
    if (reference != null) {
      lastReferenceX = reference.x();
      lastReferenceY = reference.y();
    }
  }
}
