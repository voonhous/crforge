package org.crforge.core.card;

/**
 * Level-scaled damage tier for the laser ball mechanic (DarkMagic). Values are per-hit (one hit per
 * scan) and already scaled by card level.
 *
 * @param damagePerHit scaled damage per hit
 * @param ctDamagePerHit scaled crown tower damage per hit
 * @param maxTargets upper bound on target count for this tier; 0 = catch-all
 */
public record ScaledDamageTier(int damagePerHit, int ctDamagePerHit, int maxTargets) {}
