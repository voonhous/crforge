package org.crforge.core.card;

/**
 * Immutable damage tier for the laser ball mechanic (DarkMagic). Stores base (unscaled) values
 * loaded from cards.json.
 *
 * @param damagePerSecond base DPS for this tier
 * @param crownTowerDamagePerHit crown tower damage per hit (base, unscaled)
 * @param hitFrequency seconds per hit (e.g. 0.1 = 100ms ticks); used to convert DPS to per-hit
 * @param maxTargets upper bound on target count for this tier; 0 = catch-all
 */
public record DamageTier(
    int damagePerSecond, int crownTowerDamagePerHit, float hitFrequency, int maxTargets) {}
