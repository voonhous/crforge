package org.crforge.core.card;

/**
 * Level-scaled damage tier for the laser ball mechanic (DarkMagic). Values are per-tick (100ms
 * interval) and already scaled by card level and rarity.
 *
 * @param damagePerTick scaled damage per 100ms tick
 * @param ctDamagePerTick scaled crown tower damage per 100ms tick
 * @param maxTargets upper bound on target count for this tier; 0 = catch-all
 */
public record ScaledDamageTier(int damagePerTick, int ctDamagePerTick, int maxTargets) {}
