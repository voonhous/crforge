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
 *       was listed with - then has the character that dealt it count the hit, and runs the
 *       subtraction;
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
        "Settled: the guards, the order the two sides' percentages apply in, the floor at one,"
            + " the dedupe list, the shield before the hit points, the overkill removed from the"
            + " amount reported, the death test and the clamp to zero. Held by every hit of the"
            + " reference battles; a kill as that hit of the whole hit points ignoring the holds,"
            + " held by the worked kills; a typed hit's entry, its refusals, its id listed"
            + " without a refresh and what it answers; the tiebreaker's drain passing the holds,"
            + " held by the reference battle timeline_tiebreak_tower_hp; a Kamikaze drain,"
            + " refused where damage is forbidden and passing the holds, held by"
            + " card_SkeletonBalloon. The hit counted for the character that dealt it after the"
            + " bookkeeping's gates and before the subtraction, held by"
            + " evo_barbarians_vs_musketeer and evo_bats_vs_musketeer. The battle's two holds -"
            + " the tiebreaker's and the end's - are the battle's answers. The target's buffs"
            + " lower the amount through the battle's damage reduction before the floor at one,"
            + " held by ability_monk, random_battle16_s0048 and knight_evolved_third_play. The"
            + " entry lowers a typed hit and a buff's damage over time by that reduction again"
            + " after their own stage did, held by DamageEntryReductionTest. A buff's damage"
            + " over time refused on a hidden target, held by tv-replays-v3/tv_replay_001 (a"
            + " burning Mighty Miner routing across the arena). Supplied, not"
            + " settled: nothing is untouchable or immune. Not modelled: the death handler, the"
            + " credit to the attacker, an absorber, the shield break, a target both sides may"
            + " damage, the presentation and the actions a hit runs on arrival. The reflected"
            + " attack, which runs between the subtraction and the death test, is the battle's:"
            + " it runs around this chain, held by golden-gaps-v1/electro_giant_struck_by_knights"
            + " and card_ElectroGiant. The target told of a hit that took hit points, after the"
            + " death test and whatever the hit left, for its on-damage action, held by"
            + " evo_minionhorde. The attacker's percents from the buffs the hit's source carries,"
            + " and an unkillable target held at 1 hit point by a hit that does not pierce"
            + " immunity, held by hero_berserker; the crown tower percent by"
            + " BattleBuffDamagePercentTest alone. A drain on an unkillable object is refused. A"
            + " hit after the battle's end takes nothing and is accepted, so the drain still"
            + " applies a projectile's target buff, held by random_battle16_s0038.")
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
    if (queries.damageForbidden()
        || queries.immune()
        || queries.hidden()
        || queries.untouchable()
        || damage < 1) {
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
    // The target's runs hear of the hit; a counter that takes all of it leaves nothing to deal.
    damage = queries.heard(damage);
    if (damage == 0) {
      return DamageResult.NOTHING;
    }
    return bookkeeping(hitPoints, damage, dedupeId, directionX, directionY, queries);
  }

  /**
   * Deals a hit of damage over time from a buff: refused where damage is forbidden and when the
   * target is hidden, since the bookkeeping every queued hit passes asks the hidden test of it, the
   * hit not being one that reaches hidden objects; an amount of at least 1 is lowered by the
   * target's damage reduction once more, as that entry lowers it, and floored at 1; then the
   * bookkeeping and the subtraction as for an ordinary hit, with no dedupe id and no heading. So a
   * Mighty Miner routing across the arena hidden takes none of the burn it carries, and takes it
   * again once it lands.
   *
   * @param hitPoints the target's hit points
   * @param damage the amount, after the target's damage reduction the buff's own hit took off
   * @param queries what the chain asks about the target and the battle
   */
  public static DamageResult overTime(HitPoints hitPoints, int damage, DamageQueries queries) {
    if (queries.damageForbidden() || queries.hidden()) {
      return DamageResult.NOTHING;
    }
    return bookkeeping(hitPoints, entryReduction(damage, queries), 0, 0, 0, queries);
  }

  /**
   * Kills a hit-points object: an ordinary hit of its whole hit points that ignores the battle's
   * holds and lists no dedupe id. A shield that is up takes it and the object lives; otherwise it
   * dies. Only an untouchable entity, such as one attached to a parent, is spared.
   *
   * @param hitPoints the object
   * @param queries what the chain asks about the target and the battle
   */
  public static DamageResult kill(HitPoints hitPoints, DamageQueries queries) {
    queries.hitCounted();
    // A kill pierces immunity: an unkillable object dies of it all the same.
    return subtract(hitPoints, hitPoints.getHitPoints(), 0, 0, queries, true, true);
  }

  /**
   * Deals one step of a tiebreaker's drain: the bookkeeping entered with the flag that passes the
   * battle's holds, so neither the tiebreaker's hold nor the end refuses it. Only an untouchable
   * target is spared; the amount takes no modifier and lists no dedupe id.
   *
   * @param hitPoints the tower's hit points
   * @param damage the drain's step
   * @param queries what the chain asks about the target and the battle
   */
  public static DamageResult drain(HitPoints hitPoints, int damage, DamageQueries queries) {
    if (queries.untouchable()) {
      return DamageResult.NOTHING;
    }
    if (queries.unkillable()) {
      throw new UnsupportedOperationException(
          "a drain on an unkillable object, whether it pierces the object's immunity is not"
              + " established");
    }
    queries.hitCounted();
    return subtract(hitPoints, damage, 0, 0, queries, true, false);
  }

  /**
   * Deals one step of a Kamikaze unit's drain over its time, the unit its own attacker: the damage
   * entry the drain calls refuses a target damage is forbidden on, then enters the bookkeeping with
   * the flag that passes the battle's holds, as a tiebreaker's drain does. Only an untouchable
   * target is spared there; the amount takes no modifier and lists no dedupe id.
   *
   * @param hitPoints the unit's hit points
   * @param damage the drain's step
   * @param queries what the chain asks about the unit and the battle
   */
  public static DamageResult kamikazeDrain(HitPoints hitPoints, int damage, DamageQueries queries) {
    if (queries.damageForbidden()) {
      return DamageResult.NOTHING;
    }
    return drain(hitPoints, damage, queries);
  }

  /**
   * Deals a typed hit, the pipeline already run: refused while the battle holds damage, when the
   * target is hidden or untouchable and when its id is already listed, which leaves that id's tick
   * as it was; otherwise the id is listed and the shield and the hit points are lowered as by an
   * ordinary hit. An amount of at least 1 is first lowered by the target's damage reduction once
   * more, as the entry every queued hit passes lowers it, and floored at 1: the pipeline's
   * protection stage has already taken the reduction off once, so a typed hit loses it twice.
   *
   * @param hitPoints the target's hit points
   * @param amount the amount after the type's pipeline
   * @param damageId the hit's damage id; 0 lists nothing
   * @param directionX direction of the hit along the arena's width, stored on a death
   * @param directionY direction of the hit along the arena's length, stored on a death
   * @param queries what the chain asks about the target and the battle
   * @return what the hit did, whose amount is what the shield lost when a shield was up before the
   *     hit, else what the hit points lost
   */
  public static DamageResult typedHit(
      HitPoints hitPoints,
      int amount,
      int damageId,
      int directionX,
      int directionY,
      DamageQueries queries) {
    // Whether a typed hit asks the hidden test is untraced; it refuses a hidden target as an
    // ordinary hit's entry does.
    if (queries.damageHeld() || queries.hidden() || queries.untouchable()) {
      return DamageResult.NOTHING;
    }
    if (amount >= 1) {
      amount = entryReduction(amount, queries);
      // The target's runs hear of a hit that deals something, as at the entry.
      amount = queries.heard(amount);
      if (amount == 0) {
        return DamageResult.NOTHING;
      }
    }
    if (damageId != 0 && hitPoints.isDedupeListed(damageId)) {
      return DamageResult.NOTHING;
    }
    int shieldBefore = hitPoints.getShield();
    int hitPointsBefore = hitPoints.getHitPoints();
    if (damageId != 0) {
      hitPoints.listDedupe(damageId, queries.battleTick());
    }
    queries.hitCounted();
    DamageResult result =
        subtract(hitPoints, amount, directionX, directionY, queries, false, false);
    int lost =
        shieldBefore > 0
            ? shieldBefore - hitPoints.getShield()
            : hitPointsBefore - hitPoints.getHitPoints();
    return new DamageResult(result.landed(), lost, result.died(), result.accepted());
  }

  /**
   * The target's damage reduction as the entry every queued hit passes takes it off: for a target
   * with the buff component, an amount of at least 1 lowered by the reduction and floored at 1. The
   * entry asks it of every hit whatever reduction the hit's own stage took off before - a typed
   * hit's protection stage, a buff's damage over time - so those hits lose the reduction twice.
   */
  private static int entryReduction(int amount, DamageQueries queries) {
    if (amount < 1 || !queries.targetHasBuffComponent()) {
      return amount;
    }
    return Math.max(queries.modifyDamage(amount), 1);
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
    queries.hitCounted();
    return subtract(hitPoints, damage, directionX, directionY, queries, false, false);
  }

  /**
   * The shield, then the hit points; a kill ignores the battle's hold. A hit that does not pierce
   * immunity leaves an unkillable target at 1 hit point at least, after the overkill is taken out
   * of what it lost: such a target does not die of it. Once the battle has ended the subtraction
   * takes nothing and answers not landed, yet accepted: the bookkeeping that called it accepts
   * every event that reaches it, whatever it answers.
   */
  private static DamageResult subtract(
      HitPoints hitPoints,
      int damage,
      int directionX,
      int directionY,
      DamageQueries queries,
      boolean ignoreHolds,
      boolean piercesImmunity) {
    if (!ignoreHolds && queries.battleEnded()) {
      // Nothing is taken and nothing the subtraction runs follows, but the bookkeeping that called
      // it accepts the event all the same: the drain applies a hit's buffs after the end.
      return DamageResult.ENDED;
    }
    if (hitPoints.getHitPoints() < 1) {
      // Already dead: the event is accepted, but there is nothing left to take. The whole amount
      // still counts as taken off the hit points for the on-damage action.
      if (damage >= 1) {
        queries.hitPointsTaken(damage);
      }
      return new DamageResult(true, 0, false);
    }
    queries.beforeSubtraction();
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
    if (!piercesImmunity && queries.unkillable() && hitPoints.getHitPoints() < 1) {
      hitPoints.setHitPoints(1);
    }
    boolean died = hitPoints.getHitPoints() < 1;
    if (died) {
      hitPoints.setLastHitHeading(FixedMath.angleOfVector(directionX, directionY));
      if (hitPoints.getHitPoints() < 0) {
        hitPoints.setHitPoints(0);
      }
    }
    // After the death test, whether the hit killed or not; nothing for a hit the shield took.
    if (damage >= 1) {
      queries.hitPointsTaken(damage);
    }
    return new DamageResult(true, applied, died);
  }
}
