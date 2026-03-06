package org.crforge.core.card;

/**
 * Describes a group of units to spawn when an entity dies.
 * For example, Golem -> 2 Golemites, LavaHound -> 6 LavaPups.
 *
 * @param stats  the TroopStats template for the spawned unit
 * @param count  number of units to spawn
 * @param radius spread radius around the death position
 */
public record DeathSpawnEntry(TroopStats stats, int count, float radius) {

}
