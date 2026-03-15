package org.crforge.core.card;

/**
 * Describes a group of units to spawn when an entity dies. For example, Golem -> 2 Golemites,
 * LavaHound -> 6 LavaPups.
 *
 * @param stats the TroopStats template for the spawned unit
 * @param count number of units to spawn
 * @param radius spread radius around the death position
 * @param deployTime deploy delay in seconds before the spawned unit becomes active (e.g. 1.0s for
 *     BattleRam's Barbarians)
 * @param spawnDelay delay in seconds from parent death to this spawn event (0 = immediate)
 * @param relativeX explicit x offset from death position in tiles (null = use FormationLayout)
 * @param relativeY explicit y offset from death position in tiles (null = use FormationLayout)
 */
public record DeathSpawnEntry(
    TroopStats stats,
    int count,
    float radius,
    float deployTime,
    float spawnDelay,
    Float relativeX,
    Float relativeY) {}
