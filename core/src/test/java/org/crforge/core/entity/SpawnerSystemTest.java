package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
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

    // Advance past the building's deploy phase (1.0f seconds) so SpawnerSystem
    // can tick the spawner. The SpawnerComponent timer (3.0f) is unaffected since
    // SpawnerSystem skips deploying entities.
    spawnerBuilding.update(1.0f);
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

  @Test
  void onDeath_shouldApplyDeathDamageAOE() {
    CombatSystem combatSystem = new CombatSystem(gameState);
    SpawnerSystem systemWithCombat = new SpawnerSystem(gameState, combatSystem);

    // Create a unit with death damage (like Ice Golem)
    SpawnerComponent deathDmgSpawner = SpawnerComponent.builder()
        .deathDamage(100)
        .deathDamageRadius(2.5f)
        .build();

    Troop iceGolem = Troop.builder()
        .name("IceGolem")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .health(new Health(1))
        .deployTime(0f)
        .spawner(deathDmgSpawner)
        .build();

    // Enemy nearby (should take damage)
    Troop nearEnemy = Troop.builder()
        .name("NearEnemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    // Enemy far away (should NOT take damage)
    Troop farEnemy = Troop.builder()
        .name("FarEnemy")
        .team(Team.RED)
        .position(new Position(20, 20))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(iceGolem);
    gameState.spawnEntity(nearEnemy);
    gameState.spawnEntity(farEnemy);
    gameState.processPending();

    // Finish deploying so they're targetable
    nearEnemy.update(1.0f);
    farEnemy.update(1.0f);

    systemWithCombat.onDeath(iceGolem);

    assertThat(nearEnemy.getHealth().getCurrent()).isEqualTo(400); // 500 - 100
    assertThat(farEnemy.getHealth().getCurrent()).isEqualTo(500); // No damage
  }

  @Test
  void onDeath_shouldSpawnResolvedDeathSpawns() {
    // Golem-like unit: dies and spawns 2 Golemites
    TroopStats golemiteStats = TroopStats.builder()
        .name("Golemite")
        .health(394)
        .damage(26)
        .speed(0.75f)
        .mass(5.0f)
        .movementType(MovementType.GROUND)
        .targetType(TargetType.ALL)
        .build();

    List<DeathSpawnEntry> deathSpawns = List.of(
        new DeathSpawnEntry(golemiteStats, 2, 1.5f)
    );

    SpawnerComponent golemSpawner = SpawnerComponent.builder()
        .deathDamage(88)
        .deathDamageRadius(2.0f)
        .deathSpawns(deathSpawns)
        .build();

    Troop golem = Troop.builder()
        .name("Golem")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .health(new Health(1))
        .deployTime(0f)
        .spawner(golemSpawner)
        .build();

    gameState.spawnEntity(golem);
    gameState.processPending();

    CombatSystem combatSystem = new CombatSystem(gameState);
    SpawnerSystem systemWithCombat = new SpawnerSystem(gameState, combatSystem);

    systemWithCombat.onDeath(golem);

    // Should spawn 2 Golemites
    assertThat(gameState.getPendingSpawns()).hasSize(2);
    assertThat(gameState.getPendingSpawns())
        .allMatch(e -> e.getName().equals("Golemite"));

    // Verify spawned unit stats
    gameState.processPending();
    Entity golemite = gameState.getEntities().stream()
        .filter(e -> e.getName().equals("Golemite"))
        .findFirst().orElse(null);
    assertThat(golemite).isNotNull();
    assertThat(golemite.getHealth().getMax()).isEqualTo(394);
  }

  @Test
  void deathOnlySpawner_shouldNotLiveSpawn() {
    // A death-only spawner (like Golem) should NOT trigger live spawning
    // Use a fresh GameState to avoid interference from setUp's tombstone
    GameState freshState = new GameState();
    SpawnerSystem freshSystem = new SpawnerSystem(freshState);

    TroopStats golemiteStats = TroopStats.builder()
        .name("Golemite")
        .health(394)
        .damage(26)
        .build();

    SpawnerComponent deathOnlySpawner = SpawnerComponent.builder()
        .deathSpawns(List.of(new DeathSpawnEntry(golemiteStats, 2, 1.5f)))
        .build();

    // hasLiveSpawn should be false since spawnPauseTime and spawnInterval are both 0
    assertThat(deathOnlySpawner.hasLiveSpawn()).isFalse();

    Troop golem = Troop.builder()
        .name("Golem")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .health(new Health(2000))
        .deployTime(0f)
        .spawner(deathOnlySpawner)
        .build();

    freshState.spawnEntity(golem);
    freshState.processPending();

    // Tick spawner multiple times -- should NOT spawn anything
    for (int i = 0; i < 100; i++) {
      freshSystem.update(0.1f);
    }
    assertThat(freshState.getPendingSpawns()).isEmpty();
  }

  @Test
  void stun_shouldPauseSpawnTimer() {
    // Tick 1s toward the 3s spawn timer
    spawnerSystem.update(1.0f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Stun the building (simulates Zap/Lightning hitting it)
    spawnerBuilding.getMovement().setMovementDisabled(ModifierSource.STATUS_EFFECT, true);

    // Tick 3s while stunned -- should NOT spawn despite exceeding the 3s timer
    spawnerSystem.update(1.0f);
    spawnerSystem.update(1.0f);
    spawnerSystem.update(1.0f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Remove stun
    spawnerBuilding.getMovement().setMovementDisabled(ModifierSource.STATUS_EFFECT, false);

    // Timer should resume from 1s already elapsed, so 2 more seconds needed
    spawnerSystem.update(1.9f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // This should push it over the 3s total (1s before stun + 1.9s + 0.2s = 3.1s)
    spawnerSystem.update(0.2f);
    assertThat(gameState.getPendingSpawns()).hasSize(1);
    assertThat(gameState.getPendingSpawns().get(0).getName()).isEqualTo("Skeleton");
  }

  @Test
  void freeze_shouldPauseSpawnTimer() {
    // Use a fresh state to isolate this test
    GameState freshState = new GameState();
    SpawnerSystem freshSystem = new SpawnerSystem(freshState);

    SpawnerComponent spawner = SpawnerComponent.builder()
        .spawnPauseTime(2.0f)
        .unitsPerWave(1)
        .spawnStats(skeletonStats)
        .build();
    spawner.initialize();

    Building hut = Building.builder()
        .name("GoblinHut")
        .team(Team.RED)
        .position(new Position(5, 5))
        .health(new Health(200))
        .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
        .lifetime(60f)
        .spawner(spawner)
        .build();

    freshState.spawnEntity(hut);
    freshState.processPending();
    hut.update(1.0f); // finish deploy

    // First spawn at 2s
    freshSystem.update(2.0f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
    freshState.processPending();

    // Tick 1s toward next spawn, then freeze
    freshSystem.update(1.0f);
    hut.getMovement().setMovementDisabled(ModifierSource.STATUS_EFFECT, true);

    // 5s frozen -- timer should not advance
    for (int i = 0; i < 5; i++) {
      freshSystem.update(1.0f);
    }
    assertThat(freshState.getPendingSpawns()).isEmpty();

    // Unfreeze -- 1s more needed to complete the 2s cycle
    hut.getMovement().setMovementDisabled(ModifierSource.STATUS_EFFECT, false);
    freshSystem.update(0.9f);
    assertThat(freshState.getPendingSpawns()).isEmpty();

    freshSystem.update(0.2f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
    assertThat(freshState.getPendingSpawns().get(0).getName()).isEqualTo("Skeleton");
  }
}
