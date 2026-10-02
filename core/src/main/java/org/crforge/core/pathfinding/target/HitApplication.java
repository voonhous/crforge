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
 *       cancels, nor does a dasher that is immune after its dash;
 *   <li>the load countdown is reloaded with the load time, unless a wind-up-first unit's hit missed
 *       and the match keeps such a unit loaded;
 *   <li>the special-hit decision, after which a special hit clears the charge and an ordinary hit
 *       of a unit without a charge counts itself in the same field;
 *   <li>the damage of one hit at the owner's level, the special one for a special hit;
 *   <li>a unit with a stop time after its attack has its attack block timer set to it;
 *   <li>the hit itself: the direct hit for a unit without a projectile, and for a unit with one the
 *       launch of its projectiles, which a cancelled hit skips; a special hit fires the special
 *       projectile when the row has one. Before a direct hit, a unit that tracks a charge deals its
 *       charged damage when the charge is complete, and has the charge reset, complete or not,
 *       unless its row keeps charging after an attack. After a direct hit that was not cancelled,
 *       the buff the row applies on damage goes onto the target. An attack sequence entry with an
 *       action replaces both: the action is scheduled on the owner with the target as its cause,
 *       cancelled or not, and the buff on damage follows a hit that was not;
 *   <li>a pending special load is cleared;
 *   <li>a hit not cancelled for distance schedules the action the row runs as it attacks on the
 *       owner, with the target as its cause; a hit with no target schedules nothing.
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
            + " points and by the Musketeer run's launch ticks; the charged direct hit and the"
            + " charge reset after it, by prince_tower and dark_prince_tower. Supplied, not"
            + " settled: the owner may always attack. Not modelled: special hits by interval or"
            + " while hidden and the columns they read, the attack sequence step's own damage"
            + " and projectile, the charged hit of a unit that fires, whose rows are refused, the"
            + " charged-hit byte, which nothing ported reads, the projectile a buff substitutes,"
            + " the targeted hit effect and its"
            + " pushback, the attacking flag on the owner, the buff on"
            + " damage of a hit every so many or over an area, the action an attack runs on the"
            + " owner as its own cause, whose rows are refused, and the notifications it ends"
            + " with. The action a hit runs on the owner with its target as cause, after a direct"
            + " hit and at a launch, is held by valkyrie_ev1_barbarians and"
            + " royal_giant_ev1_knights. The buff on damage after a direct hit is"
            + " held by electro_wizard_tower_defence and mini_sparkys_knight. An attack sequence"
            + " entry's action in place of the launch and the direct hit, with the target as its"
            + " cause and no end told to the listening actions, is held by"
            + " three_musketeers_pekka and three_musketeers_air_building. The"
            + " dasher's exception to the long-distance cancel is carried and held by no run. The"
            + " attack counter, raised by a hit not cancelled for distance, is held by"
            + " electro_giant_struck and electro_giant_tower, whose reflect keys on it.")
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
    return apply(t, target, sequenceIndex, true, queries);
  }

  /**
   * Applies one hit, knowing whether it is the last of its attack: the end of a single-target
   * attack's hit that landed is told to the owner's listening actions.
   *
   * @param t the attacker's targeting component
   * @param target what the hit is aimed at, or null when the attacker has given it up
   * @param sequenceIndex which hit of the attack this is, -1 for a single-target attack
   * @param last true for the last hit of its attack, which a single-target attack's always is
   * @param queries the damage at the owner's level, the battle's hit ids and where damage goes
   * @return true when <b>nothing landed</b>, which is what the visit's sink answers
   */
  public static boolean apply(
      TargetingState t, TargetView target, int sequenceIndex, boolean last, HitQueries queries) {
    TargetingConfig cfg = t.getConfig();
    TargetingGlobals globals = t.getGlobals();
    t.setHitStarted(true);
    if (queries.attackForbidden()) {
      return true;
    }
    queries.hitAllowed();
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
    if (!missed) {
      queries.attackCounted();
    }
    if (cfg.stopTimeAfterAttack() >= 1) {
      t.setAttackBlockTimerMs(cfg.stopTimeAfterAttack());
    }
    // A special hit fires the special projectile when there is one, any other hit the row's own.
    boolean fires = cfg.hasProjectile() || (special && cfg.hasSpecialProjectile());
    // An attack sequence entry with an action runs it in place of the launch and the direct hit,
    // even for a hit cancelled for distance; the buff on damage follows one that landed, as the
    // entry has no projectile.
    boolean entryAction = queries.entryAction();
    if (entryAction) {
      queries.runEntryAction(target);
      if (!missed) {
        queries.buffOnDamage(target);
      }
    } else if (!fires) {
      damage = charged(cfg, damage, queries);
      DirectHit.resolve(t, target, damage, missed, queries);
      // The buff on damage follows its own direct hit; a unit that fires never reaches it.
      if (!missed) {
        queries.buffOnDamage(target);
      }
    } else if (!missed) {
      queries.launchProjectiles(t, target, sequenceIndex, special);
    }
    boolean specialLoad = t.isSpecialLoadPending();
    t.setSpecialLoadPending(false);
    // A hit not cancelled for distance runs the row's attack action after its action, direct hit or
    // launch: once for each hit, at the launch for a unit that fires and never at the impact.
    if (!missed) {
      queries.runAttackAction(target);
    }
    // A landed hit that ends a single-target attack, not a special one and not an entry's action,
    // is counted by the owner's listening actions.
    if (!missed && !entryAction && !specialLoad && last && queries.hitListeners()) {
      queries.attackEnded();
    }
    queries.hitEnded();
    return missed;
  }

  /**
   * The charged branch of a direct hit: an owner that tracks a charge deals its charged damage when
   * the charge is complete, and then has the charge reset, complete or not, unless its row keeps
   * charging after an attack.
   */
  private static int charged(TargetingConfig cfg, int damage, HitQueries queries) {
    int progress = queries.chargeProgress();
    if (progress == HitQueries.NO_CHARGE) {
      return damage;
    }
    int dealt = progress >= HitQueries.CHARGE_COMPLETE ? queries.chargedDamage() : damage;
    if (!cfg.keepChargingAfterAttack()) {
      queries.resetCharge();
    }
    return dealt;
  }

  /**
   * Whether the hit is cancelled because its target has walked out of reach during the wind-up: the
   * target must still pass the range test with the attack range widened by the published allowance,
   * and with the minimum range left out of it.
   */
  private static boolean cancelledForDistance(
      TargetingState t, TargetView target, TargetingConfig cfg, TargetingGlobals globals) {
    // A dasher that is immune to damage after its dash is the other exception to the cancel.
    if (!globals.cancelHitFromLongDistance()
        || cfg.isBuilding()
        || (cfg.dashCooldown() > 0 && cfg.dashImmuneToDamageTime() > 0)
        || target == null) {
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
