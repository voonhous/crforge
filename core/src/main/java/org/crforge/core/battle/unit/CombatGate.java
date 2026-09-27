package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.GameTags;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetingOutcome;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.TargetingVisit;

/**
 * The combat gate at the tail of the entity state visit: whether the targeting component is on or
 * off for the next tick, and when the reference is dropped.
 *
 * <p>In order: a sleeping or waking entity (the INACTIVE and ACTIVATING tags) is off, and so is one
 * waiting to deploy. An entity that is alive, has no deploy time left and whose hit speed scales
 * above 0 is acting: it is switched on, except in an ability's follow-up, where it is off. Anything
 * else - dead, still deploying, or a hit speed scaled to 0 - has its reference dropped through the
 * setter's null path while its targeting component is on, and is then switched off when its row has
 * hit points; one without keeps whatever it had.
 *
 * <p>So a unit's reference is gone on its death tick, and a king tower, which is never removed, is
 * switched off on the tick it dies and never on again. A stun, which scales the hit speed to 0,
 * drops the reference and switches the component off at every gate while it lasts; the first gate
 * after it goes switches it on again, and the next targeting visit selects anew.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The gate agrees with the reference for the tags, the waiting state, the acting test and"
            + " the drop and switch of a dead, deploying or stunned entity, held by zap_knight for"
            + " the stun. Not modelled: a Projectile buff (refused with its row), the"
            + " casting state's KeepCurrentTarget, a clone's setup state with CLONE_RESET_TARGET,"
            + " and the touchdown query (Ladder answers 0). A dashing row's null path asks for a"
            + " resume, which the gate runs, held by bandit_knight's death.")
final class CombatGate {

  /** The time step the gate scales by the hit speed multipliers, as the attack timer does. */
  static final int HIT_SPEED_STEP_MS = 50;

  private CombatGate() {
    // Utility class
  }

  /**
   * Runs the gate and answers whether the targeting component is on.
   *
   * @param view the entity as the state visit left it
   * @param targeting its targeting component's state
   * @param targetingOn whether its targeting component is switched on now
   * @param alive whether it is alive
   * @param hitSpeed the gate's time step as the entity's buffs scale it; 0 under a stun
   * @param rowHasHitPoints whether its row has hit points, which decides the switch of an entity
   *     that is not acting
   * @param routePreparer prepares a route when the dropped reference asks for one
   * @param resume resumes the entity when the dropped reference asks for it, as a dashing row's
   *     does
   * @return whether the targeting component is on after the gate
   */
  static boolean targetingOn(
      GridEntity view,
      TargetingState targeting,
      boolean targetingOn,
      boolean alive,
      int hitSpeed,
      boolean rowHasHitPoints,
      Runnable routePreparer,
      Runnable resume) {
    int state = view.getState();
    if ((view.getFlags() & GameTags.KEEPS_TARGETING_OFF) != 0) {
      return false;
    }
    if (state == GridEntityState.WAITING_TO_DEPLOY) {
      return false;
    }
    if (state == GridEntityState.CASTING || state == GridEntityState.CLONE_SETUP) {
      throw new UnsupportedOperationException(
          "the combat gate of an entity casting or set up as a clone, which no run holds yet");
    }
    // A Projectile buff, which keeps a stunned entity acting, is refused with its row.
    if (alive && view.getDeployCountdown() <= 0 && hitSpeed != 0) {
      if (state == GridEntityState.ABILITY_FOLLOW_UP) {
        return false;
      }
      if (state != GridEntityState.FOLLOWING_REMOVED) {
        return true;
      }
    }
    // Not acting: the reference goes through the setter's null path while the component is on.
    if (targetingOn && targeting.getReference() != null) {
      TargetingOutcome outcome = new TargetingOutcome();
      outcome.setRoutePreparer(routePreparer);
      TargetingVisit.clearReference(targeting, view, outcome);
      if (outcome.isResumeRequested()) {
        resume.run();
      }
    }
    return !rowHasHitPoints && targetingOn;
  }
}
