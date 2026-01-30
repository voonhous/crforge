package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
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

    // With new logic, single-unit waves use spawnPauseTime for the delay between spawns.
    // We set spawnPauseTime to 3.0 so it waits 3s, spawns, then waits 3s again.
    SpawnerComponent spawnerComponent = SpawnerComponent.builder()
        .spawnPauseTime(3.0f)
        .unitsPerWave(1)
        .deathSpawnCount(4)
        .spawnStats(skeletonStats)
        .build();

    // Manually initialize to ensure timer is set correctly for test
    spawnerComponent.initialize();

    spawnerBuilding =
        Building.builder()
            .name("Tombstone")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(100))
            .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
            .lifetime(40f)
            .spawner(spawnerComponent)
            .build();

    gameState.spawnEntity(spawnerBuilding);
    gameState.processPending();
  }

  @Test
  void update_shouldSpawnUnitPeriodically() {
    // Initial update (0s) -> Timer starts at 3.0. No spawn.
    spawnerSystem.update(0f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 1s -> Timer at 2.0. No spawn.
    spawnerSystem.update(1.0f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 2s (Total 3s) -> Timer <= 0. Should spawn.
    spawnerSystem.update(2.0f);
    assertThat(gameState.getPendingSpawns()).hasSize(1);

    Entity spawned = gameState.getPendingSpawns().get(0);
    assertThat(spawned.getName()).isEqualTo("Skeleton");
    // Spread might slightly offset position
    assertThat(spawned.getPosition().getX()).isBetween(9.0f, 11.0f);
    assertThat(spawned.getPosition().getY()).isBetween(9.0f, 11.0f);

    gameState.processPending();

    // Update another 2.9s (Total 5.9s) -> Timer reloaded to 3.0, now at 0.1. No spawn.
    spawnerSystem.update(2.9f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 0.2s (Total 6.1s) -> Timer <= 0. Should spawn second unit.
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
