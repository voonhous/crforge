package org.crforge.core.pathfinding.target;

import java.util.List;

/**
 * What the targeting visit needs from outside the targeting pass.
 *
 * <p>Every method has a default that gives the answer the standard 1v1 mode gives for an ordinary
 * single-target troop without a projectile, so an implementation only overrides what it changes.
 * The two that have no default are the candidate selection and the hit sink, because the visit
 * cannot invent either.
 *
 * <p>The three supplied time answers are the ones the reference trajectories were produced with:
 * each visit advances the wind-up timers by one tick and the attack timer by one tick, and adds
 * nothing to the burst timer.
 */
public interface TargetingQueries {

  /** One tick, in milliseconds. */
  int TICK_MS = 50;

  /**
   * How far a countdown the visit steps this visit, in milliseconds. Slowing or hastening a unit
   * would change this; the standard answer is one tick.
   */
  default int timeStepMs() {
    return TICK_MS;
  }

  /** How far the attack timer advances on an attack tick, in milliseconds; one tick. */
  default int attackTimerStepMs() {
    return TICK_MS;
  }

  /** How far the burst timer advances on an attack tick, in milliseconds; nothing. */
  default int burstTimerStepMs() {
    return 0;
  }

  /** True when the visit's request to enter the attacking state takes effect immediately. */
  default boolean stateChangeApplies() {
    return true;
  }

  /** True when the entity carries the buff component the visit asks about. */
  default boolean hasBuffComponent() {
    return false;
  }

  /** Number of extra targets the entity's buffs add to one attack. */
  default int extraTargetCount() {
    return 0;
  }

  /** True while an ability is running, which is what lets a casting unit attack. */
  default boolean abilityInProgress() {
    return false;
  }

  /** True when the reference is within the range a dash may start from. */
  default boolean dashRangeReached(TargetView reference) {
    return false;
  }

  /** True when a unit whose reference is gone should nevertheless carry on attacking. */
  default boolean continueWithoutReference() {
    return false;
  }

  /** True when a building with no reference should still run its attack reset. */
  default boolean buildingKeepsAttacking() {
    return false;
  }

  /** The id recorded on the hit list when the unit attacks without a reference. */
  default int nullTargetId() {
    return 0;
  }

  /** The attack sequence step index that follows the current attack time. */
  default int nextAttackSequenceStep(int attackTimerMs) {
    return 0;
  }

  /** Runs the candidate selection and answers the reference it chose, or null. */
  TargetView runSelection();

  /**
   * The validator's answer about the component's <b>current</b> reference. The visit asks this
   * several times while it is dropping and re-taking references, and every answer must be about the
   * reference the component holds at that moment.
   */
  boolean validateReference(int mode);

  /** Where the hits go. */
  HitSink hitSink();

  /** Runs the action a unit performs on the first hit of an attack. */
  default void onStartingAttack() {
    // No action by default.
  }

  /** Starts a dash toward the given point; the dash itself belongs to the movement pass. */
  default void startDash(TargetView target, int x, int y, int radius) {
    // No dash by default.
  }

  /** How many hits a dash of this unit applies; the slot picks which of the unit's dashes. */
  default int dashDamageIndex(int slot) {
    return 0;
  }

  /** The entities a dash passes through, queried around the dashing unit. */
  default List<TargetView> dashCandidates(int x, int y, int radius) {
    return List.of();
  }

  /** Returns the dash candidate list to the index. */
  default void releaseDashCandidates(List<TargetView> candidates) {
    // Nothing to return by default.
  }

  /**
   * Applies one dash hit and answers whether it landed. The vector is the separation from the
   * dashing unit to the target, in game units.
   */
  default boolean applyDashHit(TargetView target, int damageIndex, int vectorX, int vectorY) {
    return false;
  }

  /** Pushes an entity a dash has hit away from the dashing unit. */
  default void applyDashPushback(TargetView target, int fromX, int fromY, int pushback) {
    // No pushback by default.
  }

  /** Answers the extra targets of a multi-target attack, by index; null when there is no more. */
  default TargetView multiTarget(int index, boolean unique) {
    return null;
  }

  /** The list a unique multi-target attack draws its extra targets from. */
  default List<TargetView> uniqueMultiTargets() {
    return List.of();
  }
}
