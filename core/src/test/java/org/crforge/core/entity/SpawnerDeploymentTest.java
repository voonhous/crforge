package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpawnerDeploymentTest {

  private GameState gameState;
  private SpawnerSystem spawnerSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    spawnerSystem = new SpawnerSystem(gameState);
  }

  @Test
  void spawnerShouldNotTickDuringDeployment() {
    // Setup spawner with 1s pause
    SpawnerComponent spawner = SpawnerComponent.builder()
        .spawnPauseTime(1.0f)
        .currentTimer(1.0f) // Explicitly set start timer
        .unitsPerWave(1)
        .spawnStats(TroopStats.builder().name("Skeleton").build())
        .build();

    Building building = Building.builder()
        .name("Spawner")
        .team(Team.BLUE)
        .deployTime(2.0f) // 2s deploy
        .spawner(spawner)
        .build();

    gameState.spawnEntity(building);
    gameState.processPending();

    // 1. Update for 1.5s (Still deploying)
    spawnerSystem.update(1.5f);

    // The building is still deploying (2.0s > 1.5s).
    // The spawner should NOT have fired yet.
    assertThat(gameState.getPendingSpawns())
        .as("Should not spawn units while deploying")
        .isEmpty();
  }
}
