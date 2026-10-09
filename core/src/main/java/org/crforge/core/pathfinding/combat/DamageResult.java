package org.crforge.core.pathfinding.combat;

/**
 * What one damage event did to a hit-points object.
 *
 * @param landed true when the damage reached the object at all, past the subtraction's own test of
 *     the battle's end; false when a guard turned it away, which is what the entry answers the
 *     caller. Whatever the subtraction runs - the shield and the hit points, the reflect, the
 *     attacker's hook - follows only a hit that landed
 * @param applied hit points actually lost, with the overkill of a killing hit removed and a
 *     shield's share counted as applied; zero when nothing landed
 * @param died true when the hit took the object below one hit point
 * @param accepted true when the bookkeeping let the event through, whatever the subtraction then
 *     did: a hit that landed, and one the subtraction refused because the battle has ended. The
 *     damage drain applies a hit's buffs for an accepted hit
 */
public record DamageResult(boolean landed, int applied, boolean died, boolean accepted) {

  /** The answer of a damage event that a guard turned away. */
  public static final DamageResult NOTHING = new DamageResult(false, 0, false, false);

  /**
   * The answer of a damage event the bookkeeping let through to a subtraction that refused it, the
   * battle being over: nothing taken, nothing the subtraction runs, but accepted.
   */
  public static final DamageResult ENDED = new DamageResult(false, 0, false, true);

  /**
   * The answer of a damage event that either landed, and so was accepted, or was turned away.
   *
   * @param landed true when the damage reached the object
   * @param applied hit points actually lost
   * @param died true when the hit took the object below one hit point
   */
  public DamageResult(boolean landed, int applied, boolean died) {
    this(landed, applied, died, landed);
  }
}
