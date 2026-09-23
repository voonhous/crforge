package org.crforge.core.pathfinding.combat;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One damage event reaching a hit-points object: the guards, the amount the two sides' buffs make
 * of it, the dedupe list and the subtraction itself.
 *
 * <p>The chain is three steps of the standard game, kept apart here as they are there:
 *
 * <ol>
 *   <li>the entry decides whether the damage may be dealt at all and settles the amount: the
 *       target's own buffs change it and the attacker's percentages scale it, the second of them
 *       only against a crown tower;
 *   <li>the bookkeeping decides whether this event has already landed - a damage source that
 *       carries a dedupe id lands on one target once, and a repeat only refreshes the tick the id
 *       was listed with - and runs the subtraction;
 *   <li>the subtraction lowers the shield first and then the hit points, reports what was actually
 *       lost with the overkill of a killing hit removed, and on a death stores the heading of the
 *       hit that killed and clamps the hit points to zero.
 * </ol>
 *
 * <p>Nothing here removes the entity: the holder's cleanup does that, because a dead entity is
 * removable. The death handler itself - rewards, death spawns, what a destroyed tower does to the
 * match - is not part of this chain.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the guards, the order the two sides' percentages apply in, the floor at one, the"
            + " dedupe list, the shield before the hit points, the overkill removed from the"
            + " amount reported, the death test and the clamp to zero. Held by every hit of the"
            + " kill run. Supplied, not settled: nothing is untouchable or immune, no buff changes"
            + " the amount and the battle holds nothing. Not modelled: the death handler, the"
            + " credit to the attacker, the reflected attack, an absorber, the shield break, a"
            + " target both sides may damage, the presentation and the actions a hit runs on"
            + " arrival.")
public final class DamageApplication {

  private DamageApplication() {
    // Utility class
  }

  /**
   * Deals one damage event to a hit-points object.
   *
   * @param hitPoints the target's hit points
   * @param damage the damage the source decided on, before the two sides' buffs
   * @param dedupeId id a source that must land on one target only once carries; 0 for a source that
   *     may land again
   * @param directionX direction of the hit along the arena's width, stored on a death
   * @param directionY direction of the hit along the arena's length, stored on a death
   * @param queries what the chain asks about the target, the attacker and the battle
   */
  public static DamageResult damage(
      HitPoints hitPoints,
      int damage,
      int dedupeId,
      int directionX,
      int directionY,
      DamageQueries queries) {
    if (queries.damageForbidden() || queries.immune() || queries.untouchable() || damage < 1) {
      return DamageResult.NOTHING;
    }
    if (queries.targetHasBuffComponent()) {
      damage = Math.max(queries.modifyDamage(damage), 1);
      int extra = queries.extraDamage();
      if (extra > 0) {
        damage += extra;
      }
      if (queries.attackerHasBuffComponent()) {
        damage = scale(damage, queries.attackerDamagePercent());
        if (queries.crownTowerTarget()) {
          damage = scale(damage, queries.attackerCrownTowerPercent());
        }
      }
    }
    return bookkeeping(hitPoints, damage, dedupeId, directionX, directionY, queries);
  }

  /** A percentage of the damage, truncated and floored at one. */
  private static int scale(int damage, int percent) {
    return Math.max(FixedMath.divOrZero(percent * damage, 100), 1);
  }

  /** The dedupe list and the decision to run the subtraction at all. */
  private static DamageResult bookkeeping(
      HitPoints hitPoints,
      int damage,
      int dedupeId,
      int directionX,
      int directionY,
      DamageQueries queries) {
    if (queries.damageHeld() || queries.untouchable()) {
      return DamageResult.NOTHING;
    }
    if (dedupeId != 0) {
      if (hitPoints.isDedupeListed(dedupeId)) {
        // The source has already landed on this target; only the tick it landed on moves on.
        hitPoints.refreshDedupe(dedupeId, queries.battleTick());
        return DamageResult.NOTHING;
      }
      hitPoints.listDedupe(dedupeId, queries.battleTick());
    }
    return subtract(hitPoints, damage, directionX, directionY, queries);
  }

  /** The shield, then the hit points. */
  private static DamageResult subtract(
      HitPoints hitPoints, int damage, int directionX, int directionY, DamageQueries queries) {
    if (queries.battleEnded()) {
      return DamageResult.NOTHING;
    }
    if (hitPoints.getHitPoints() < 1) {
      // Already dead: the event is accepted, but there is nothing left to take.
      return new DamageResult(true, 0, false);
    }
    int applied;
    int shield = hitPoints.getShield();
    if (shield >= 1) {
      applied = Math.min(shield, damage);
      hitPoints.setShield(Math.max(shield - damage, 0));
      // Whatever the shield could not absorb is lost with it; the hit points take nothing.
      damage = 0;
    } else {
      applied = damage;
    }
    hitPoints.setHitPoints(hitPoints.getHitPoints() - damage);
    if (hitPoints.getHitPoints() < 0) {
      // The overkill of a killing hit is not part of what the target lost.
      applied += hitPoints.getHitPoints();
    }
    boolean died = hitPoints.getHitPoints() < 1;
    if (died) {
      hitPoints.setLastHitHeading(FixedMath.angleOfVector(directionX, directionY));
      if (hitPoints.getHitPoints() < 0) {
        hitPoints.setHitPoints(0);
      }
    }
    return new DamageResult(true, applied, died);
  }
}
