/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.state;

import java.util.function.BooleanSupplier;

/**
 * The answers the entity state visit and its resume helper pull from the rest of the simulation.
 *
 * <p>The two route questions are the load-bearing ones: together they decide whether a unit that
 * has finished deploying starts moving or stands still. Answering both yes is what makes a unit
 * with a movement component leave the deployment countdown in the moving state; a unit without one
 * stands.
 *
 * @param team which player the entity belongs to, 0 or 1, which picks the facing an arriving
 *     spawn-pathfinding unit is given
 * @param mayHoldRoute whether the entity is one that can follow a route at all
 * @param gridAllowsRoute whether the arena will give that entity a route
 * @param gridRouteFlag the flag the grid's route question is asked with, read just before that
 *     question; its meaning is not documented
 * @param hasHitPoints whether the entity still has hit points, which decides whether a unit that
 *     may not route stands or is removed
 * @param abilityCastActive whether an ability is currently being cast, which holds the casting
 *     state
 * @param abilityTriggerReady whether a requested ability may start this visit, asked when the visit
 *     reaches its pending ability, so it answers for the state the visit has reached
 * @param protectedFromDamage whether something is currently shielding the entity, which keeps its
 *     dash immunity topped up
 * @param protectionApplies whether that shield applies in this match, asked alongside it
 * @param goalRow the row of the entity's own side's goal line, in routing cells, or -1 for none
 * @param scaledDeployStepMs the deploy countdown's step in milliseconds when the entity's speed
 *     modifiers scale it; the unscaled step is 50
 * @param notAttacking the section of a row with a buff while it is not attacking, run where the
 *     visit reaches it: its countdown, and the buff's removal or apply; nothing for any other row
 * @param kamikazeDrain the drain of a Kamikaze row with a time, run where the visit reaches it,
 *     right after the not-attacking section: one step from the tick its hit ended on; nothing for
 *     any other row
 * @param deployEndVisit what a hiding row's deploy end runs right after its resume: the combat
 *     gate, then, with its targeting component on, its targeting visit
 * @param hide the hide handler of a hiding row, run where the visit reaches it, since it reads the
 *     state the visit has reached
 * @param abilityEffect the ability's effect, run where the visit reaches it: on the visit the
 *     trigger delay reaches zero, after both countdowns step and before the cast's end is tested,
 *     so the rest of the visit sees the state the effect leaves
 */
public record StateQueries(
    int team,
    boolean mayHoldRoute,
    boolean gridAllowsRoute,
    boolean gridRouteFlag,
    boolean hasHitPoints,
    boolean abilityCastActive,
    BooleanSupplier abilityTriggerReady,
    boolean protectedFromDamage,
    boolean protectionApplies,
    int goalRow,
    int scaledDeployStepMs,
    Runnable notAttacking,
    Runnable kamikazeDrain,
    Runnable deployEndVisit,
    Runnable hide,
    Runnable abilityEffect) {

  /** Milliseconds one tick advances every countdown by. */
  public static final int TICK_MS = 50;

  /** The answers without an ability effect: a visit that reaches it runs nothing. */
  public StateQueries(
      int team,
      boolean mayHoldRoute,
      boolean gridAllowsRoute,
      boolean gridRouteFlag,
      boolean hasHitPoints,
      boolean abilityCastActive,
      BooleanSupplier abilityTriggerReady,
      boolean protectedFromDamage,
      boolean protectionApplies,
      int goalRow,
      int scaledDeployStepMs,
      Runnable notAttacking,
      Runnable kamikazeDrain,
      Runnable deployEndVisit,
      Runnable hide) {
    this(
        team,
        mayHoldRoute,
        gridAllowsRoute,
        gridRouteFlag,
        hasHitPoints,
        abilityCastActive,
        abilityTriggerReady,
        protectedFromDamage,
        protectionApplies,
        goalRow,
        scaledDeployStepMs,
        notAttacking,
        kamikazeDrain,
        deployEndVisit,
        hide,
        () -> {});
  }

  /**
   * Whether the arena will give the entity a route, asked the way the resume helper asks it: with
   * the flag it has just read. Nothing varies the answer with the flag today and what would is not
   * documented, so the flag is carried to the question and the standing answer is given.
   *
   * @param routeFlag the flag read immediately before the question
   */
  public boolean gridAllowsRoute(boolean routeFlag) {
    return gridAllowsRoute;
  }

  /**
   * The answers for an ordinary unit that can follow a route: both route questions yes, hit points
   * present, no ability and no shield.
   *
   * @param team which player the entity belongs to, 0 or 1
   */
  public static StateQueries forUnitWithRoute(int team) {
    return new StateQueries(
        team,
        true,
        true,
        false,
        true,
        false,
        () -> false,
        false,
        false,
        -1,
        TICK_MS,
        () -> {},
        () -> {},
        () -> {},
        () -> {});
  }

  /** The same answers with the "can follow a route" question answered differently. */
  public StateQueries withMayHoldRoute(boolean value) {
    return new StateQueries(
        team,
        value,
        gridAllowsRoute,
        gridRouteFlag,
        hasHitPoints,
        abilityCastActive,
        abilityTriggerReady,
        protectedFromDamage,
        protectionApplies,
        goalRow,
        scaledDeployStepMs,
        notAttacking,
        kamikazeDrain,
        deployEndVisit,
        hide,
        abilityEffect);
  }
}
