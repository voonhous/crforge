package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * What a hit writes back into the attacker's own targeting component.
 *
 * <p>Applying a hit is a long chain: the guards, the long-distance cancel, the damage, the effects,
 * the projectile or the direct hit, the actions afterwards. This class carries only the part of it
 * that the targeting visit reads back on later ticks, so that the visit's timing is right before
 * the rest of the chain exists:
 *
 * <ul>
 *   <li>the hit-started flag is raised before any guard, so even a hit that is then refused counts
 *       as started;
 *   <li>the load countdown is reloaded with the load time, unless a wind-up-first unit's hit missed
 *       and the match keeps such a unit loaded;
 *   <li>a unit without a special charge counts the hit in its special charge field;
 *   <li>a unit with a stop time after its attack has its attack block timer set to it;
 *   <li>a pending special load is cleared.
 * </ul>
 *
 * <p>Not here yet: the damage itself, the miss decision (the long-distance cancel), special hits,
 * projectiles, effects and the actions the hit runs. A caller that has not decided a miss passes
 * false.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The five component writes agree with the reference and are held by the kill run's hit"
            + " cadence through the reloaded countdown. Not modelled: everything the hit does to"
            + " its target, the long-distance cancel, special hits, projectiles, effects and"
            + " actions.")
public final class HitApplication {

  private HitApplication() {
    // Utility class
  }

  /**
   * Records one hit on the attacker's component.
   *
   * @param t the attacker's targeting component
   * @param missed true when the hit was cancelled for distance and landed on nothing
   * @return true when nothing landed, which is what the visit's sink answers
   */
  public static boolean record(TargetingState t, boolean missed) {
    TargetingConfig cfg = t.getConfig();
    TargetingGlobals globals = t.getGlobals();
    t.setHitStarted(true);
    if (!(cfg.loadFirstHit() && globals.loadFirstHitKeepLoadedAfterDiscard() && missed)) {
      t.setLoadTimerMs(cfg.loadTime());
    }
    if (cfg.specialChargeTime() <= 0) {
      // Without a special charge the field counts hits; a special hit would clear it instead.
      t.setSpecialChargeTimerMs(t.getSpecialChargeTimerMs() + 1);
    }
    if (cfg.stopTimeAfterAttack() >= 1) {
      t.setAttackBlockTimerMs(cfg.stopTimeAfterAttack());
    }
    t.setSpecialLoadPending(false);
    return missed;
  }
}
