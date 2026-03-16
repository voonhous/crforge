package org.crforge.core.card;

/**
 * Immutable damage tier for the laser ball mechanic (DarkMagic). Stores base (unscaled) values
 * loaded from cards.json.
 *
 * @param damagePerSecond base DPS for this tier
 * @param crownTowerDamagePerHit crown tower damage per 100ms tick (base, unscaled)
 * @param maxTargets upper bound on target count for this tier; 0 = catch-all
 */
public record DamageTier(int damagePerSecond, int crownTowerDamagePerHit, int maxTargets) {}
