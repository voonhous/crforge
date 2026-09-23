package org.crforge.core.pathfinding.combat;

/**
 * What the damage chain needs to know about the target it is lowering, the attacker behind the
 * damage and the battle they are in.
 *
 * <p>Every method has a default that gives the answer a plain tower or troop gives in a running
 * standard battle, so an implementation only overrides what it changes. These are the answers the
 * reference run was produced with: nothing is untouchable, nothing blocks damage, and neither side
 * carries a buff that changes the amount.
 */
public interface DamageQueries {

  /** The answers of a plain target in a running battle. */
  DamageQueries STANDARD = new DamageQueries() {};

  /** True when the target takes no damage at all, whatever the source. */
  default boolean damageForbidden() {
    return false;
  }

  /**
   * The target's own answer to "am I immune to this hit", asked before anything else is read. An
   * ordinary entity answers no; what sets it is not documented.
   */
  default boolean immune() {
    return false;
  }

  /** True while the target cannot be touched at all, which also stops the bookkeeping. */
  default boolean untouchable() {
    return false;
  }

  /** True while the battle holds every damage event, which the bookkeeping refuses to run under. */
  default boolean damageHeld() {
    return false;
  }

  /** True once the battle is over, which the subtraction refuses to run under. */
  default boolean battleEnded() {
    return false;
  }

  /** True when the target carries the buff component that may change the damage it takes. */
  default boolean targetHasBuffComponent() {
    return true;
  }

  /**
   * The damage after the target's own buffs have changed it. The chain floors the answer at one
   * afterwards.
   */
  default int modifyDamage(int damage) {
    return damage;
  }

  /** Damage the target's buffs add on top, ignored when it is not positive. */
  default int extraDamage() {
    return 0;
  }

  /** True when the attacker carries the buff component whose percentages scale the damage. */
  default boolean attackerHasBuffComponent() {
    return true;
  }

  /** Percentage the attacker's buffs scale every hit by; 100 leaves the damage alone. */
  default int attackerDamagePercent() {
    return 100;
  }

  /**
   * Percentage the attacker's buffs scale a hit on a crown tower by, applied after {@link
   * #attackerDamagePercent()}; 100 leaves the damage alone.
   */
  default int attackerCrownTowerPercent() {
    return 100;
  }

  /** True when the target is a crown tower, which is what makes the second percentage apply. */
  default boolean crownTowerTarget() {
    return false;
  }

  /** The battle tick a dedupe id is listed with. */
  default int battleTick() {
    return 0;
  }
}
