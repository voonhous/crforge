package org.crforge.core.ability;

/**
 * Maps a projectile or unit name to a damage multiplier for the GiantBuffer's BUFF_ALLY bonus. The
 * multiplier is in hundredths: 500 = 5x, 100 = 1x (default), 0 = no bonus.
 *
 * @param name the projectile or unit name to match
 * @param multiplier damage multiplier in hundredths (e.g. 500 = 5x)
 */
public record DamageMultiplierEntry(String name, int multiplier) {}
