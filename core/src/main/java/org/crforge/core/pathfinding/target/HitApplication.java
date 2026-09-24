package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One hit of an attack: what it writes back into the attacker's own targeting component, whether it
 * lands at all, and the damage it carries to its target.
 *
 * <p>In the order the standard game runs it:
 *
 * <ul>
 *   <li>the hit-started flag is raised before any guard, so even a hit that is then refused counts
 *       as started;
 *   <li>a unit that may not attack discards the hit here;
 *   <li>the long-distance cancel: a target that has left the attack range, widened by the published
 *       allowance, during the wind-up is missed, and the hit lands on nothing. A building never
 *       cancels;
 *   <li>the load countdown is reloaded with the load time, unless a wind-up-first unit's hit missed
 *       and the match keeps such a unit loaded;
 *   <li>the special-hit decision, after which a special hit clears the charge and an ordinary hit
 *       of a unit without a charge counts itself in the same field;
 *   <li>the damage of one hit at the owner's level, the special one for a special hit;
 *   <li>a unit with a stop time after its attack has its attack block timer set to it;
 *   <li>the hit itself: the direct hit for a unit without a projectile, and for a unit with one the
 *       launch of its projectiles, which a cancelled hit skips;
 *   <li>a pending special load is cleared.
 * </ul>
 *
 * <p>The damage chosen here reaches the target only through the direct hit. A projectile computes
 * its own damage from its row when it arrives, so for a unit that fires the damage is chosen and
 * not used, as the standard game has it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the order above, the component writes, the long-distance cancel, the direct"
            + " hit of a unit without a projectile and the launch for a unit with one. Held by the"
            + " kill run's hit cadence, by every hit landing on the reference's remaining hit"
            + " points and by the Musketeer run's launch ticks. Supplied, not settled: the owner"
            + " may always attack. Not modelled: special hits by interval or while hidden and the"
            + " columns they read, the attack sequence step's own damage and projectile, the"
            + " charged hit, the projectile a buff substitutes, the targeted hit effect and its"
            + " pushback, the attack counter and the attacking flag on the owner, the buff a hit"
            + " applies, the actions an attack runs and the notifications it ends with, and the"
            + " dasher's exception to the long-distance cancel, whose column is not carried.")
public final class HitApplication {

  private HitApplication() {
    // Utility class
  }

  /**
   * Applies one hit of a single-target attack.
   *
   * @see #apply(TargetingState, TargetView, int, HitQueries)
   */
  public static boolean apply(TargetingState t, TargetView target, HitQueries queries) {
    return apply(t, target, -1, queries);
  }

  /**
   * Applies one hit.
   *
   * <p>Of the sink's other arguments, the extra targets the owner's buffs add and whether this is
   * the last hit are read only by the notifications, which are not modelled, so they are not taken
   * here.
   *
   * @param t the attacker's targeting component
   * @param target what the hit is aimed at, or null when the attacker has given it up
   * @param sequenceIndex which hit of the attack this is: -1 for a single-target attack, otherwise
   *     the index within the burst or the multi-target list, which only the projectile placement
   *     reads
   * @param queries the damage at the owner's level, the battle's hit ids and where damage goes
   * @return true when <b>nothing landed</b>, which is what the visit's sink answers
   */
  public static boolean apply(
      TargetingState t, TargetView target, int sequenceIndex, HitQueries queries) {
    TargetingConfig cfg = t.getConfig();
    TargetingGlobals globals = t.getGlobals();
    t.setHitStarted(true);
    if (queries.attackForbidden()) {
      return true;
    }
    boolean missed = cancelledForDistance(t, target, cfg, globals);
    if (!(cfg.loadFirstHit() && globals.loadFirstHitKeepLoadedAfterDiscard() && missed)) {
      t.setLoadTimerMs(cfg.loadTime());
    }
    boolean special = specialDue(t, cfg);
    if (special) {
      t.setSpecialChargeTimerMs(0);
    } else if (cfg.specialChargeTime() <= 0) {
      // Without a special charge the field counts hits instead.
      t.setSpecialChargeTimerMs(t.getSpecialChargeTimerMs() + 1);
    }
    int damage = special ? queries.specialDamage() : queries.damage();
    if (cfg.stopTimeAfterAttack() >= 1) {
      t.setAttackBlockTimerMs(cfg.stopTimeAfterAttack());
    }
    if (!cfg.hasProjectile()) {
      DirectHit.resolve(t, target, damage, missed, queries);
    } else if (!missed) {
      queries.launchProjectiles(t, target, sequenceIndex);
    }
    t.setSpecialLoadPending(false);
    return missed;
  }

  /**
   * Whether the hit is cancelled because its target has walked out of reach during the wind-up: the
   * target must still pass the range test with the attack range widened by the published allowance,
   * and with the minimum range left out of it.
   */
  private static boolean cancelledForDistance(
      TargetingState t, TargetView target, TargetingConfig cfg, TargetingGlobals globals) {
    // A dasher that is immune to damage while it dashes is the other exception to the cancel; the
    // column that says for how long is not carried, so no unit takes that exception here.
    if (!globals.cancelHitFromLongDistance() || cfg.isBuilding() || target == null) {
      return false;
    }
    int range = AttackRange.attackRange(t) + globals.cancelHitFromLongDistanceRange();
    return !RangeTest.rangeTest(target, t.getOwner().getX(), t.getOwner().getY(), range, 0, false);
  }

  /**
   * Whether this hit is the special one: a loaded special attack is, and so is a charged one once
   * its charge is full. The two other routes to a special hit - every so many hits, and a unit that
   * is hidden - read columns this data does not carry.
   */
  private static boolean specialDue(TargetingState t, TargetingConfig cfg) {
    if (t.isSpecialLoadPending()) {
      return true;
    }
    if (cfg.specialChargeTime() < 1) {
      return false;
    }
    return t.getSpecialChargeTimerMs() >= cfg.specialChargeTime();
  }
}
