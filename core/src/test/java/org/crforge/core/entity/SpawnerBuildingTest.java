package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.TroopStats;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpawnerBuildingTest {

  private SpawnerBuilding spawner;
  private List<Entity> spawnedEntities;
  private TroopStats skeletonStats;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    spawnedEntities = new ArrayList<>();

    skeletonStats =
        TroopStats.builder()
            .name("Skeleton")
            .health(67)
            .damage(67)
            .speed(1.2f)
            .build();

    spawner =
        SpawnerBuilding.builder()
            .name("Tombstone")
            .team(Team.BLUE)
            .position(10, 10)
            .spawnInterval(3.0f)
            .deathSpawnCount(4)
            .spawnStats(skeletonStats)
            .lifetime(40f)
            .maxHealth(100) // Small health to test decay easily
            .build();

    spawner.setSpawnCallback(spawnedEntities::add);
    spawner.onSpawn();
  }

  @Test
  void update_shouldSpawnUnitPeriodically() {
    // Initial update (0s) -> No spawn yet
    spawner.update(0f);
    assertThat(spawnedEntities).isEmpty();

    // Update 1s -> No spawn
    spawner.update(1.0f);
    assertThat(spawnedEntities).isEmpty();

    // Update 2s (Total 3s) -> Should spawn
    spawner.update(2.0f);
    assertThat(spawnedEntities).hasSize(1);

    Entity spawned = spawnedEntities.get(0);
    assertThat(spawned.getName()).isEqualTo("Skeleton");
    assertThat(spawned.getPosition().getX()).isEqualTo(10f);
    assertThat(spawned.getPosition().getY()).isEqualTo(10f);

    // Update another 2.9s (Total 5.9s) -> No new spawn
    spawner.update(2.9f);
    assertThat(spawnedEntities).hasSize(1);

    // Update 0.2s (Total 6.1s) -> Should spawn second unit
    spawner.update(0.2f);
    assertThat(spawnedEntities).hasSize(2);
  }

  @Test
  void onDeath_shouldSpawnDeathUnits() {
    spawner.onDeath();

    // Should spawn 4 skeletons on death
    assertThat(spawnedEntities).hasSize(4);

    // Check that they are skeletons
    assertThat(spawnedEntities).allMatch(e -> e.getName().equals("Skeleton"));
  }

  @Test
  void health_shouldDecayOverTime() {
    // Lifetime 40s, Health 100
    // Decay rate = 100 / 40 = 2.5 HP/s
    int initialHealth = spawner.getHealth().getCurrent();

    // Run for 4 seconds -> should lose 10 HP
    spawner.update(4.0f);

    assertThat(spawner.getHealth().getCurrent()).isEqualTo(initialHealth - 10);
  }
}
