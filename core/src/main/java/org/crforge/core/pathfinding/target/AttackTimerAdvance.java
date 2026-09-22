package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Advances a unit's attack time and burst timer on one attack tick.
 *
 * <p>The targeting visit calls this once per attack tick, after it has requested the attacking
 * state and before it counts how many hits the attack time now covers. In order:
 *
 * <ol>
 *   <li>If the battle holds every attack timer, the attack time is cleared and nothing else moves.
 *   <li>The step is one tick, or half the hit speed multiplier of the current attack sequence step
 *       when a step is active, scaled by the unit's status effects. A scaled step under one
 *       millisecond ends the advance.
 *   <li>An attack time standing at zero is first loaded. When the load time is no longer than the
 *       hit speed, the unit is credited the load time less what remains of the load countdown, and
 *       the countdown is reloaded; so a unit whose countdown has run out is credited its whole
 *       load, and one that hit a moment ago only what has recharged since. A load time longer than
 *       the hit speed credits nothing: the countdown is cleared when it is no longer than the hit
 *       speed, and the advance ends without a step when it is longer.
 *   <li>A component restored from a saved battle may carry a flag that instead rounds the attack
 *       time up to the next multiple of the hit speed, once.
 *   <li>Otherwise the burst timer takes the step while it is running, and then the attack time
 *       takes it too, unless a running burst freezes the attack time under the
 *       burst-affects-animation column.
 * </ol>
 *
 * <p>Whether a hit fires is not decided here: the visit compares the hit count before and after.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: the hold, the step and its halving, the load"
            + " credit and reload in all three load-time cases, the restore round-up, the burst"
            + " timer and the animation freeze. Held by the kill run's hit ticks for a unit with"
            + " one sequence step and no bursts. Supplied: the battle never holds the timers,"
            + " and no status effect scales the step.")
public final class AttackTimerAdvance {

  private AttackTimerAdvance() {
    // Utility class
  }

  /**
   * Runs one advance.
   *
   * @param t the attacker's targeting component
   * @param cfg the attacker's targeting columns
   * @param queries the battle-wide hold and the status-effect scaling
   */
  public static void advance(TargetingState t, TargetingConfig cfg, TargetingQueries queries) {
    if (queries.attackTimersHeld()) {
      t.setAttackTimerMs(0);
      t.setHitInProgressWithoutReference(false);
      t.setAttackTimeRoundedUp(false);
      return;
    }

    int base;
    int index = t.getAttackSequenceIndex();
    if (index != TargetingState.NO_SEQUENCE_STEP) {
      // A step past the end of the sequence is read anyway; the config answers its before-start
      // step there, which is the closest this port comes to reading past the table.
      AttackSequenceEntry entry = cfg.entry(cfg.stepId(index));
      int multiplier =
          entry == null
              ? AttackSequenceEntry.DEFAULT_HIT_SPEED_MULTIPLIER
              : entry.hitSpeedMultiplier();
      base = multiplier / 2; // halved toward zero
    } else {
      base = TargetingQueries.TICK_MS;
    }
    int step = queries.scaleTimeStep(base);
    int hitSpeed = cfg.hitSpeed();
    if (step < 1) {
      t.setHitInProgressWithoutReference(false);
      return;
    }

    int attackTime = t.getAttackTimerMs();
    boolean roundUp = t.isAttackTimeRoundUpA() || t.isAttackTimeRoundUpB();
    if (attackTime == 0 && !roundUp) {
      int loadTime = cfg.loadTime();
      int countdown = t.getLoadTimerMs();
      if (loadTime <= hitSpeed) {
        attackTime = loadTime - countdown;
        t.setAttackTimerMs(attackTime);
        t.setLoadTimerMs(loadTime);
        int intoTheHit = attackTime - FixedMath.divOrZero(attackTime, hitSpeed) * hitSpeed;
        if (intoTheHit >= loadTime) {
          if (t.getReference() != null) {
            t.setHitInProgressWithoutReference(true);
          }
        } else {
          t.setHitInProgressWithoutReference(false);
        }
      } else if (countdown <= hitSpeed) {
        if (t.getReference() != null) {
          t.setHitInProgressWithoutReference(true);
        }
        t.setLoadTimerMs(0);
      } else {
        t.setAttackTimerMs(0);
        t.setHitInProgressWithoutReference(false);
        return;
      }
    }

    if (roundUp) {
      t.setAttackTimeRoundUpA(false);
      t.setAttackTimeRoundUpB(false);
      t.setAttackTimeRoundedUp(true);
      int wholeHits = FixedMath.divOrZero(attackTime, hitSpeed) * hitSpeed;
      t.setAttackTimerMs(attackTime + hitSpeed + (wholeHits - attackTime));
      return;
    }

    int burst = t.getBurstProgressMs();
    if (burst >= 1) {
      t.setBurstProgressMs(burst + step);
    }
    if (burst != 0 && cfg.burstAffectAnimation()) {
      t.setAttackTimeRoundedUp(false);
      return;
    }
    t.setAttackTimerMs(attackTime + step);
    t.setAttackTimeRoundedUp(false);
  }
}
