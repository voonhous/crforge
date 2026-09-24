package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * What a targeting component does when an entity leaves the battle: the notice every remaining
 * entity receives from the holder's removal, at the moment of the removal.
 *
 * <p>If the reference was the removed entity it is replaced, by the replacement given or by
 * nothing, the kept-with-pending-damage answer is cleared and the retarget load runs: a unit with
 * LoadAfterRetarget and an attack running reloads its load countdown and clears its attack; one
 * with LoadFirstHit is credited the load time less the attack time and clears its attack; a plain
 * unit with an attack running starts the target-lost countdown, under which the visit lets the
 * attack run on for the attack finish time before it selects again. A previous reference that was
 * the removed entity is forgotten.
 *
 * <p>The notice runs inside the closing cleanup of the tick the entity died in, so no visit ever
 * meets a dead reference.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the reference drop, the pending-damage answer, the three retarget loads and the"
            + " previous reference, held by the kill run's five standing ticks after the princess"
            + " tower's death. Not modelled: the forgetting of a held pingpong projectile and the"
            + " morph back it triggers; the held projectile is read as the suspended flag.")
public final class RemovalNotice {

  private RemovalNotice() {}

  /**
   * Tells a targeting component that an entity has left the battle.
   *
   * @param t the component's state
   * @param removed the view of the entity that left
   * @param replacement the entity that takes its place as the reference, or null for none
   */
  public static void entityRemoved(TargetingState t, TargetView removed, TargetView replacement) {
    TargetingConfig cfg = t.getConfig();
    if (t.getReference() != null && t.getReference() == removed) {
      if (replacement != null) {
        t.setReference(replacement);
        if (cfg.attackSequenceMode() != 0) {
          t.clearAttack();
        }
        t.setKeptByPendingDamageCheck(false);
        retargetLoad(t, cfg);
      } else {
        boolean wasKept = t.isKeptByPendingDamageCheck();
        t.setReference(null);
        t.setKeptByPendingDamageCheck(false);
        if (!wasKept) {
          retargetLoad(t, cfg);
        }
      }
    }
    if (t.getPreviousReference() != null && t.getPreviousReference() == removed) {
      t.setPreviousReference(null);
    }
  }

  /** The load a unit takes on losing its reference, by its load columns. */
  private static void retargetLoad(TargetingState t, TargetingConfig cfg) {
    if (cfg.loadAfterRetarget()) {
      if (t.getAttackTimerMs() >= 1) {
        t.clearAttack();
        t.setLoadTimerMs(cfg.loadTime());
      }
      return;
    }
    if (cfg.loadFirstHit()) {
      if (t.getAttackTimerMs() >= 1) {
        int credit = cfg.loadTime() - t.getAttackTimerMs();
        t.clearAttack();
        t.setLoadTimerMs(Math.max(credit, 0));
      }
      return;
    }
    if (t.getAttackTimerMs() < 1 || cfg.hitSpeed() < 2) {
      return;
    }
    if (t.getGlobals().attackFinishTimeMs() < 1) {
      return;
    }
    if (t.isVisitSuspended() || t.getBurstProgressMs() != 0 || cfg.overrideAttackFinishTime()) {
      return;
    }
    t.setTargetLostTimerMs(1);
  }
}
