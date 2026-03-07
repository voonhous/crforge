package org.crforge.core.card;

/**
 * Configuration for periodic spawning behavior on a unit (e.g. Witch spawns Skeletons, Tombstone
 * spawns Skeletons).
 *
 * @param spawnCharacter name of the spawned unit (resolved to TroopStats at card load time)
 * @param spawnNumber    units per wave
 * @param spawnPauseTime delay between waves (seconds)
 * @param spawnInterval  delay between units within a wave (seconds)
 * @param spawnStartTime initial delay before first spawn (seconds)
 * @param spawnRadius    formation radius for spawned units (tile units)
 */
public record LiveSpawnConfig(
    String spawnCharacter,
    int spawnNumber,
    float spawnPauseTime,
    float spawnInterval,
    float spawnStartTime,
    float spawnRadius
) {

}
