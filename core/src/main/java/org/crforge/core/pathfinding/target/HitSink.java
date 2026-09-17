package org.crforge.core.pathfinding.target;

/**
 * Where the targeting visit sends the hits it decides to make.
 *
 * <p>Applying a hit - the damage, the effects, the projectile - is not part of the targeting pass.
 * The visit decides <b>when</b> a hit happens and <b>what</b> it is aimed at, and hands both to a
 * sink; everything after that belongs to the combat code.
 */
@FunctionalInterface
public interface HitSink {

  /**
   * Applies one hit.
   *
   * @param target what is being hit, or null when the unit is firing at a target it has already
   *     given up
   * @param sequenceIndex which hit of the attack this is: -1 for a single-target attack, otherwise
   *     the index within the burst or the multi-target list
   * @param extraTargets number of extra targets the owner's buffs add to this attack
   * @param last true when this is the last hit of the attack
   * @return true when the hit was applied; the visit only reads this for a unit whose wind-up runs
   *     before its first hit
   */
  boolean hit(TargetView target, int sequenceIndex, int extraTargets, boolean last);
}
