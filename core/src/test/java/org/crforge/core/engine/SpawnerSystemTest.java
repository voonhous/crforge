package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpawnerSystemTest {

  private Building spawnerBuilding;
  private GameState gameState;
  private SpawnerSystem spawnerSystem;
  private TroopStats skeletonStats;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    spawnerSystem = new SpawnerSystem(gameState);

    skeletonStats =
        TroopStats.builder()
            .name("Skeleton")
            .health(67)
            .damage(67)
            .speed(1.2f)
            .build();

    SpawnerComponent spawnerComponent = SpawnerComponent.builder()
        .spawnInterval(3.0f)
        .currentTimer(3.0f)
        .deathSpawnCount(4)
        .spawnStats(skeletonStats)
        .build();

    spawnerBuilding =
        Building.builder()
            .name("Tombstone")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(100))
            .movement(new Movement(0, 0, 2.0f, MovementType.BUILDING))
            .lifetime(40f)
            .spawner(spawnerComponent)
            .build();

    gameState.spawnEntity(spawnerBuilding);
    gameState.processPending();
  }

  @Test
  void update_shouldSpawnUnitPeriodically() {
    // Initial update (0s) -> No spawn yet
    spawnerSystem.update(0f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 1s -> No spawn
    spawnerSystem.update(1.0f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 2s (Total 3s) -> Should spawn
    spawnerSystem.update(2.0f);
    assertThat(gameState.getPendingSpawns()).hasSize(1);

    Entity spawned = gameState.getPendingSpawns().get(0);
    assertThat(spawned.getName()).isEqualTo("Skeleton");
    // Spread might slightly offset position
    assertThat(spawned.getPosition().getX()).isEqualTo(10f);
    assertThat(spawned.getPosition().getY()).isEqualTo(10f);

    gameState.processPending();

    // Update another 2.9s (Total 5.9s) -> No new spawn
    spawnerSystem.update(2.9f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 0.2s (Total 6.1s) -> Should spawn second unit
    spawnerSystem.update(0.2f);
    assertThat(gameState.getPendingSpawns()).hasSize(1);
  }

  @Test
  void onDeath_shouldSpawnDeathUnits() {
    spawnerSystem.onDeath(spawnerBuilding);

    // Should spawn 4 skeletons on death
    assertThat(gameState.getPendingSpawns()).hasSize(4);

    // Check that they are skeletons
    assertThat(gameState.getPendingSpawns()).allMatch(e -> e.getName().equals("Skeleton"));
  }
}
