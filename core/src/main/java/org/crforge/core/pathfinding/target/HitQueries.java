package org.crforge.core.pathfinding.target;

/**
 * What applying a hit needs from outside the targeting component: the damage of one hit at the
 * owner's level, the battle's hit ids, and where the damage of a hit that lands is sent.
 *
 * <p>The two damages are asked for rather than carried because the standard game reads them from
 * the owner's data row at the owner's level at the moment of the hit.
 */
public interface HitQueries {

  /** Damage of one ordinary hit, at the owner's level. */
  int damage();

  /**
   * Damage of one special hit, at the owner's level; the ordinary damage for a unit without one.
   */
  default int specialDamage() {
    return damage();
  }

  /** The charge progress of an owner that tracks none. */
  int NO_CHARGE = -1;

  /** The charge progress at which a charge is complete. */
  int CHARGE_COMPLETE = 10000;

  /**
   * The progress of the owner's charge, or {@link #NO_CHARGE} for an owner that tracks none.
   * Supplied as none.
   */
  default int chargeProgress() {
    return NO_CHARGE;
  }

  /** Damage of the owner's charged hit, at its level; the ordinary damage for an owner without. */
  default int chargedDamage() {
    return damage();
  }

  /**
   * Resets the owner's charge after a hit: to zero for an owner with a charge range to build over,
   * and the targeting component's strike-now byte cleared. Supplied as doing nothing.
   */
  default void resetCharge() {}

  /**
   * Told once the owner's tags let the hit through, before the cancel for distance: a row with a
   * buff while it is not attacking restarts that countdown at one tick here, on every hit.
   */
  default void hitAllowed() {}

  /**
   * Told once a hit is not cancelled for distance, before its damage or its launch: the owner
   * counts its attacks, the count and its id the key a reflecting target deals its damage back once
   * by.
   */
  default void attackCounted() {}

  /**
   * The pushback of the attack sequence step a direct hit lands with: on the target, before its
   * damage, or, from the area branch, on every victim. A target without a movement component is not
   * pushed.
   *
   * @param target the target the hit lands on, or null from the area branch
   */
  default void stepPushback(TargetView target) {}

  /**
   * Told after a direct hit's damage was dealt to its one target: a row with an area effect on its
   * hits makes it here, where the owner stands.
   */
  default void directHitDealt() {}

  /** True when the owner may not attack at all, which discards the hit before any other step. */
  default boolean attackForbidden() {
    return false;
  }

  /**
   * The id of the hit about to be dealt, counted by the battle. One hit carries one id through
   * every target it reaches, which is how a target tells two hits apart.
   */
  default int nextHitId() {
    return 0;
  }

  /**
   * Deals the damage of a landed hit to one target.
   *
   * @param target what the hit landed on
   * @param damage hit points to take, already the crown-tower damage where the target asks for it
   * @param hitId the id this hit carries
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  void dealDamage(TargetView target, int damage, int hitId, int directionX, int directionY);

  /**
   * Launches the projectiles of one hit of a unit that fires rather than hits directly: as many as
   * its column says, aimed at where the reference stood at the start of the visit. A battle that
   * has no projectiles launches nothing.
   *
   * @param t the attacker's targeting component, whose stored reference position is the aim
   * @param target what the hit is aimed at, or null when the attacker has given it up
   * @param sequenceIndex which hit of the attack this is: -1 for a single-target attack, otherwise
   *     the index within the burst or the multi-target list, which fans the projectiles out
   * @param special true for a special hit, which fires the special projectile when there is one
   */
  default void launchProjectiles(
      TargetingState t, TargetView target, int sequenceIndex, boolean special) {}

  /**
   * Whether the attack sequence's entry this hit reads runs an action in place of hitting: the hit
   * then neither launches nor deals a direct hit, and does not tell the owner's listening actions
   * that its attack ended. An owner without such an entry answers false.
   */
  default boolean entryAction() {
    return false;
  }

  /**
   * Schedules the entry's action on the owner, with the hit's target as its cause, whether or not
   * the hit was cancelled for distance.
   *
   * @param target what the hit was aimed at, or null when the owner had given it up: the action
   *     then has no cause
   */
  default void runEntryAction(TargetView target) {}

  /**
   * Schedules the action the owner's row runs as it attacks on the owner, with the hit's target as
   * its cause, after a hit that was not cancelled for distance. An owner whose row names none, and
   * a hit with no target, schedule nothing.
   *
   * @param target what the hit was aimed at, or null when the owner had given it up
   */
  default void runAttackAction(TargetView target) {}

  /**
   * Schedules the action the owner's row runs on itself as it attacks on the owner, with the owner
   * as its own cause, after a hit that was not cancelled for distance and after the action the row
   * runs with the hit's target as cause. It is scheduled whether or not the hit has a target. An
   * owner whose row names none schedules nothing.
   */
  default void runAttackSelfAction() {}

  /**
   * Deals the damage of a landed hit to everything in a circle rather than to its target alone,
   * with the owner as the area's owner: its own side is spared and its own columns decide what it
   * may hit. A battle without an area to damage does nothing.
   *
   * @param x centre of the circle along the arena's width
   * @param y centre of the circle along the arena's length
   * @param radius radius of the circle
   * @param damage what an ordinary victim takes
   * @param towerDamage what a crown tower takes
   * @param hitId the id this hit carries
   */
  default void areaDamage(int x, int y, int radius, int damage, int towerDamage, int hitId) {}

  /**
   * The buff the owner's row applies to what its hit reached, right after a direct hit that was not
   * cancelled, each target of an attack in turn after its own hit. An owner whose row applies none
   * does nothing.
   *
   * @param target what the hit was aimed at, or null when the owner had given it up
   */
  default void buffOnDamage(TargetView target) {}

  /**
   * The hit's last call, made whether or not anything landed but not for a hit the owner's tags
   * forbid: a Kamikaze owner destroys itself here, after its direct hit or its launch.
   */
  default void hitEnded() {}

  /**
   * Whether the owner's actions listen to its hits: a running action that enchants them. Without
   * one the hit neither passes its damage through them nor tells them its attack ended.
   */
  default boolean hitListeners() {
    return false;
  }

  /**
   * The damage of a landed hit as the owner's listening actions change it, from the last listed
   * down, each handed the damage the one after it answered.
   *
   * @param damage the damage so far
   * @param hitId the id the hit carries
   * @param crownTower true for the crown-tower damage, false for the ordinary damage
   * @return the damage the hit carries on
   */
  default int listenedDamage(int damage, int hitId, boolean crownTower) {
    return damage;
  }

  /** Tells the owner's listening actions, first to last, that one of its attacks ended. */
  default void attackEnded() {}
}
