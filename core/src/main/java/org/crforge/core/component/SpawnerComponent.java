package org.crforge.core.component;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.TroopStats;

/**
 * Data component for entities that can spawn other units. Replaces the SpawnerBuilding class
 * inheritance.
 */
@Getter
@Setter
@Builder
public class SpawnerComponent {

  private float spawnInterval;
  private float currentTimer;
  private int deathSpawnCount;
  private TroopStats spawnStats; // What to spawn

  // Logic helper
  public boolean tick(float deltaTime) {
    if (spawnInterval <= 0) {
      return false;
    }

    currentTimer -= deltaTime;
    if (currentTimer <= 0) {
      currentTimer += spawnInterval; // Keep sync
      return true; // Ready to spawn
    }
    return false;
  }
}
