package org.crforge.core.pathfinding.combat;

/**
 * What one damage event did to a hit-points object.
 *
 * @param landed true when the damage reached the object at all; false when a guard turned it away,
 *     which is what the entry answers the caller
 * @param applied hit points actually lost, with the overkill of a killing hit removed and a
 *     shield's share counted as applied; zero when nothing landed
 * @param died true when the hit took the object below one hit point
 */
public record DamageResult(boolean landed, int applied, boolean died) {

  /** The answer of a damage event that a guard turned away. */
  public static final DamageResult NOTHING = new DamageResult(false, 0, false);
}
