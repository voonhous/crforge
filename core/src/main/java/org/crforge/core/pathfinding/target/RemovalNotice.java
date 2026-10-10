/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
 * the removed entity is forgotten, and so is its id among the remembered targets of a unit that
 * ever remembers them. A reference removed with no replacement that the component's last visit kept
 * for a lethal pending damage skips the retarget load, whoever killed it, so the component selects
 * again on its next visit.
 *
 * <p>The notice runs inside the closing cleanup of the tick the entity died in, so no visit ever
 * meets a dead reference.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the reference drop, the pending-damage answer, the three retarget loads and the"
            + " previous reference, held by the reference battles in which a princess tower"
            + " falls, card_Pekka_until_stop among them, and RemovalNoticeTest; the retarget load"
            + " skipped for a reference kept through a killing shot in flight, by every tower's"
            + " re-lock on the tick after its arrow's kill in the battle references, and with"
            + " another unit's kill, by card_Cannon, troops_cannon_pulls_hog and"
            + " random_battle_s0012, against the countdown of the towers' retargets in"
            + " card-deploy-c2 and troops_minipekka_vs_pekka. The forgetting of a held projectile"
            + " is the entity's, beside this notice: a hooking projectile's, and a pingpong"
            + " projectile's that came back to an owner whose targeting was off, held by"
            + " BattlePingpongReturnStunnedTest. Not modelled: the morph back a forgotten hooking"
            + " projectile triggers for a row with a morph.")
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
    // A unit that ever remembers its targets forgets the one that left: the first time its id is
    // listed, the rest keeping their order.
    if (cfg.remembersAnyTargets()) {
      t.getRememberedTargetIds().remove(Integer.valueOf(removed.id()));
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
