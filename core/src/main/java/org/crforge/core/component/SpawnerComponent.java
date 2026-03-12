package org.crforge.core.component;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;

/**
 * Data component for entities that can spawn other units. Supports wave-based spawning (e.g.
 * Tombstone spawning 2 Skeletons rapidly, then waiting).
 */
@Getter
@Setter
@Builder
public class SpawnerComponent {

  /** Time to wait before starting a new wave. (e.g. 3.5s for Tombstone). */
  private float spawnPauseTime;

  /** Time interval between units WITHIN a wave. (e.g. 0.5s between Skeletons in a wave). */
  private float spawnInterval;

  /** Number of units to spawn per wave. */
  @Builder.Default private int unitsPerWave = 1;

  /**
   * Initial delay before the first wave starts (e.g. Witch 1.0s). Only used once at spawn; after
   * that, spawnPauseTime is used.
   */
  @Builder.Default private float spawnStartTime = 0f;

  private int deathSpawnCount;
  private TroopStats spawnStats;

  /** Rarity and level of the parent card, used to scale spawned unit stats. */
  @Builder.Default private final Rarity rarity = Rarity.COMMON;

  @Builder.Default private final int level = 1;

  /**
   * Formation radius for live spawn placement (in tile units). Used by SpawnerSystem to arrange
   * spawned units in a circle.
   */
  @Builder.Default private float formationRadius = 0f;

  /**
   * When true, SpawnerSystem kills the entity as soon as it finishes deploying, triggering its
   * death mechanics. Used for bomb entities (BalloonBomb, GiantSkeletonBomb, etc.) that
   * fall/animate for their deployTime, then explode via death damage.
   */
  @Builder.Default private boolean selfDestruct = false;

  // Death mechanics
  @Builder.Default private int deathDamage = 0;
  @Builder.Default private float deathDamageRadius = 0f;
  @Builder.Default private float deathPushback = 0f;
  @Builder.Default private List<DeathSpawnEntry> deathSpawns = new ArrayList<>();

  // Death area effect (e.g. RageBarbarianBottle drops a Rage zone on death)
  private AreaEffectStats deathAreaEffect;

  // Runtime state
  @Builder.Default private float currentTimer = 0f;
  @Builder.Default private int unitsSpawnedInCurrentWave = 0;
  @Builder.Default private SpawnerState state = SpawnerState.WAITING_FOR_WAVE;

  public enum SpawnerState {
    WAITING_FOR_WAVE, // Counting down spawnPauseTime
    SPAWNING_WAVE // Counting down spawnInterval between units
  }

  /**
   * Returns the zero-based index of the most recently spawned unit within the current wave. Used by
   * FormationLayout to calculate circular formation offsets.
   */
  public int getLastSpawnIndex() {
    return unitsSpawnedInCurrentWave - 1;
  }

  /**
   * Returns true if this spawner has periodic (live) spawning capability. Death-only spawners (like
   * Golem) return false.
   */
  public boolean hasLiveSpawn() {
    return spawnPauseTime > 0 || spawnInterval > 0;
  }

  /** Returns true if this spawner has any death mechanics (damage or spawns). */
  public boolean hasDeathMechanics() {
    return deathDamage > 0 || deathSpawnCount > 0 || !deathSpawns.isEmpty() || deathAreaEffect != null;
  }

  /**
   * Initialize timer state (usually called after creation). Uses spawnStartTime for the initial
   * delay if set, otherwise spawnPauseTime.
   */
  public void initialize() {
    this.currentTimer = spawnStartTime > 0 ? spawnStartTime : 0;
    this.state = SpawnerState.WAITING_FOR_WAVE;
  }

  /**
   * Ticks the spawner logic.
   *
   * @param deltaTime Time elapsed
   * @return true if a unit should be spawned this tick
   */
  public boolean tick(float deltaTime) {
    currentTimer -= deltaTime;

    if (currentTimer <= 0) {
      if (state == SpawnerState.WAITING_FOR_WAVE) {
        // Wave delay finished -> Start wave and spawn first unit immediately
        state = SpawnerState.SPAWNING_WAVE;
        unitsSpawnedInCurrentWave = 0;
        return processSpawn();

      } else if (state == SpawnerState.SPAWNING_WAVE) {
        // Interval between units finished -> Spawn next unit
        return processSpawn();
      }
    }
    return false;
  }

  private boolean processSpawn() {
    unitsSpawnedInCurrentWave++;

    // Check if wave is complete
    if (unitsSpawnedInCurrentWave >= unitsPerWave) {
      // Wave finished, go back to waiting
      state = SpawnerState.WAITING_FOR_WAVE;
      currentTimer = spawnPauseTime;
    } else {
      // Wave continues, wait for interval before next unit
      currentTimer = spawnInterval;
    }

    return true;
  }
}
