package org.crforge.core.card;

/**
 * Configuration for periodic spawning behavior on a unit (e.g. Witch spawns Skeletons, Tombstone
 * spawns Skeletons).
 *
 * @param spawnCharacter name of the spawned unit (resolved to TroopStats at card load time)
 * @param spawnNumber units per wave
 * @param spawnPauseTime delay between waves (seconds)
 * @param spawnInterval delay between units within a wave (seconds)
 * @param spawnStartTime initial delay before first spawn (seconds)
 * @param spawnRadius formation radius for spawned units (tile units)
 * @param spawnAttach if true, spawned units attach permanently to the parent (e.g. Ram Rider)
 */
public record LiveSpawnConfig(
    String spawnCharacter,
    int spawnNumber,
    float spawnPauseTime,
    float spawnInterval,
    float spawnStartTime,
    float spawnRadius,
    boolean spawnAttach,
    int spawnLimit,
    boolean destroyAtLimit,
    boolean spawnOnAggro) {

  /** Backwards-compatible constructor without spawn limit and aggro fields. */
  public LiveSpawnConfig(
      String spawnCharacter,
      int spawnNumber,
      float spawnPauseTime,
      float spawnInterval,
      float spawnStartTime,
      float spawnRadius,
      boolean spawnAttach) {
    this(
        spawnCharacter,
        spawnNumber,
        spawnPauseTime,
        spawnInterval,
        spawnStartTime,
        spawnRadius,
        spawnAttach,
        0,
        false,
        false);
  }

  /** Backwards-compatible constructor without aggro field. */
  public LiveSpawnConfig(
      String spawnCharacter,
      int spawnNumber,
      float spawnPauseTime,
      float spawnInterval,
      float spawnStartTime,
      float spawnRadius,
      boolean spawnAttach,
      int spawnLimit,
      boolean destroyAtLimit) {
    this(
        spawnCharacter,
        spawnNumber,
        spawnPauseTime,
        spawnInterval,
        spawnStartTime,
        spawnRadius,
        spawnAttach,
        spawnLimit,
        destroyAtLimit,
        false);
  }
}
