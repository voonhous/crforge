package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
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

    skeletonStats = TroopStats.builder().name("Skeleton").health(67).damage(67).speed(1.2f).build();

    // With new logic, single-unit waves use spawnPauseTime for the delay between spawns.
    // We set spawnPauseTime to 3.0 so it waits 3s, spawns, then waits 3s again.
    SpawnerComponent spawnerComponent =
        SpawnerComponent.builder()
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
    // First tick -- timer starts at 0, so first wave spawns immediately
    spawnerSystem.update(0f);
    assertThat(gameState.getPendingSpawns()).hasSize(1);

    Entity spawned = gameState.getPendingSpawns().get(0);
    assertThat(spawned.getName()).isEqualTo("Skeleton");
    // Spread might slightly offset position
    assertThat(spawned.getPosition().getX()).isBetween(9.0f, 11.0f);
    assertThat(spawned.getPosition().getY()).isBetween(9.0f, 11.0f);

    gameState.processPending();

    // After first spawn, timer reloads to spawnPauseTime (3.0s).
    // Update 2.9s -> Timer at 0.1. No spawn yet.
    spawnerSystem.update(2.9f);
    assertThat(gameState.getPendingSpawns()).isEmpty();

    // Update 0.2s (Total 3.1s after first spawn) -> Timer <= 0. Should spawn second unit.
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
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    CombatSystem combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    SpawnerSystem systemWithCombat = new SpawnerSystem(gameState, new AoeDamageService(gameState));

    // Create a unit with death damage (like Ice Golem)
    SpawnerComponent deathDmgSpawner =
        SpawnerComponent.builder().deathDamage(100).deathDamageRadius(2.5f).build();

    Troop iceGolem =
        Troop.builder()
            .name("IceGolem")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(deathDmgSpawner)
            .build();

    // Enemy nearby (should take damage)
    Troop nearEnemy =
        Troop.builder()
            .name("NearEnemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    // Enemy far away (should NOT take damage)
    Troop farEnemy =
        Troop.builder()
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
    TroopStats golemiteStats =
        TroopStats.builder()
            .name("Golemite")
            .health(394)
            .damage(26)
            .speed(0.75f)
            .mass(5.0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    List<DeathSpawnEntry> deathSpawns =
        List.of(new DeathSpawnEntry(golemiteStats, 2, 1.5f, 0f, 0f, null, null));

    SpawnerComponent golemSpawner =
        SpawnerComponent.builder()
            .deathDamage(88)
            .deathDamageRadius(2.0f)
            .deathSpawns(deathSpawns)
            .build();

    Troop golem =
        Troop.builder()
            .name("Golem")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(golemSpawner)
            .build();

    gameState.spawnEntity(golem);
    gameState.processPending();

    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    CombatSystem combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    SpawnerSystem systemWithCombat = new SpawnerSystem(gameState, new AoeDamageService(gameState));

    systemWithCombat.onDeath(golem);

    // Should spawn 2 Golemites
    assertThat(gameState.getPendingSpawns()).hasSize(2);
    assertThat(gameState.getPendingSpawns()).allMatch(e -> e.getName().equals("Golemite"));

    // Verify spawned unit stats
    gameState.processPending();
    Entity golemite =
        gameState.getEntities().stream()
            .filter(e -> e.getName().equals("Golemite"))
            .findFirst()
            .orElse(null);
    assertThat(golemite).isNotNull();
    assertThat(golemite.getHealth().getMax()).isEqualTo(394);
  }

  @Test
  void deathOnlySpawner_shouldNotLiveSpawn() {
    // A death-only spawner (like Golem) should NOT trigger live spawning
    // Use a fresh GameState to avoid interference from setUp's tombstone
    GameState freshState = new GameState();
    SpawnerSystem freshSystem = new SpawnerSystem(freshState);

    TroopStats golemiteStats = TroopStats.builder().name("Golemite").health(394).damage(26).build();

    SpawnerComponent deathOnlySpawner =
        SpawnerComponent.builder()
            .deathSpawns(List.of(new DeathSpawnEntry(golemiteStats, 2, 1.5f, 0f, 0f, null, null)))
            .build();

    // hasLiveSpawn should be false since spawnPauseTime and spawnInterval are both 0
    assertThat(deathOnlySpawner.hasLiveSpawn()).isFalse();

    Troop golem =
        Troop.builder()
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
    // First wave spawns immediately
    spawnerSystem.update(0f);
    assertThat(gameState.getPendingSpawns()).hasSize(1);
    gameState.processPending();

    // Tick 1s toward the 3s pause between waves
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

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .spawnPauseTime(2.0f)
            .unitsPerWave(1)
            .spawnStats(skeletonStats)
            .build();
    spawner.initialize();

    Building hut =
        Building.builder()
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

    // First spawn is immediate (no spawnStartTime)
    freshSystem.update(0f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
    freshState.processPending();

    // Tick 1s toward next spawn (2s pause), then freeze
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

  @Test
  void spawner_shouldSpawnFirstWaveImmediately() {
    // A spawner with no spawnStartTime should spawn its first wave on the first tick
    GameState freshState = new GameState();
    SpawnerSystem freshSystem = new SpawnerSystem(freshState);

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .spawnPauseTime(3.5f)
            .unitsPerWave(2)
            .spawnInterval(0.5f)
            .spawnStats(skeletonStats)
            .build();
    spawner.initialize();

    Building tombstone =
        Building.builder()
            .name("Tombstone")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(100))
            .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
            .lifetime(40f)
            .spawner(spawner)
            .build();

    freshState.spawnEntity(tombstone);
    freshState.processPending();
    tombstone.update(1.0f); // finish deploy

    // First tick -- first unit of the wave spawns immediately
    freshSystem.update(0f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
    assertThat(freshState.getPendingSpawns().get(0).getName()).isEqualTo("Skeleton");
    freshState.processPending();

    // After 0.5s spawnInterval, second unit of the wave spawns
    freshSystem.update(0.5f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
    freshState.processPending();

    // Now waiting for spawnPauseTime (3.5s) before next wave
    freshSystem.update(3.4f);
    assertThat(freshState.getPendingSpawns()).isEmpty();

    freshSystem.update(0.2f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
  }

  @Test
  void deathSpawn_bombEntity_shouldExplodeAfterDeployTime() {
    // BalloonBomb-like entity: health=0, deployTime=3.0, deathDamage=100, radius=2.0
    GameState freshState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(freshState);
    ProjectileSystem projectileSystem = new ProjectileSystem(freshState, aoeDamageService);
    CombatSystem combatSystem = new CombatSystem(freshState, aoeDamageService, projectileSystem);
    SpawnerSystem freshSystem = new SpawnerSystem(freshState, new AoeDamageService(freshState));
    freshState.setDeathHandler(freshSystem::onDeath);

    TroopStats bombStats =
        TroopStats.builder()
            .name("BalloonBomb")
            .health(0)
            .deployTime(3.0f)
            .deathDamage(100)
            .deathDamageRadius(2.0f)
            .build();

    // Parent entity (Balloon) that death-spawns the bomb
    SpawnerComponent balloonSpawner =
        SpawnerComponent.builder()
            .deathSpawns(List.of(new DeathSpawnEntry(bombStats, 1, 0f, 0f, 0f, null, null)))
            .build();

    Troop balloon =
        Troop.builder()
            .name("Balloon")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(balloonSpawner)
            .build();

    // Enemy nearby (should take bomb death damage)
    Troop nearEnemy =
        Troop.builder()
            .name("NearEnemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    // Enemy far away (should NOT take bomb death damage)
    Troop farEnemy =
        Troop.builder()
            .name("FarEnemy")
            .team(Team.RED)
            .position(new Position(20, 20))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    freshState.spawnEntity(balloon);
    freshState.spawnEntity(nearEnemy);
    freshState.spawnEntity(farEnemy);
    freshState.processPending();

    // Finish deploying enemies so they're targetable
    nearEnemy.update(1.0f);
    farEnemy.update(1.0f);

    // Kill the balloon -> should death-spawn a BalloonBomb
    freshState.processDeaths();
    balloon.getHealth().takeDamage(1);
    freshState.processDeaths();
    assertThat(freshState.getPendingSpawns()).hasSize(1);

    Entity bomb = freshState.getPendingSpawns().get(0);
    assertThat(bomb.getName()).isEqualTo("BalloonBomb");
    assertThat(bomb).isInstanceOf(Troop.class);

    // Bomb should have SpawnerComponent with selfDestruct and death damage
    assertThat(bomb.getSpawner()).isNotNull();
    assertThat(bomb.getSpawner().isSelfDestruct()).isTrue();
    assertThat(bomb.getSpawner().getDeathDamage()).isEqualTo(100);

    // Bomb should have 1 HP (not 0, so it survives deploy phase)
    assertThat(bomb.getHealth().getMax()).isEqualTo(1);

    freshState.processPending();

    // Bomb is deploying -- should NOT be targetable
    Troop bombTroop = (Troop) bomb;
    assertThat(bombTroop.isDeploying()).isTrue();
    assertThat(bombTroop.isTargetable()).isFalse();

    // SpawnerSystem should not self-destruct during deploy
    freshSystem.update(0.1f);
    assertThat(bomb.getHealth().isAlive()).isTrue();

    // Advance the bomb's deploy timer past 3.0s
    bombTroop.update(3.1f);
    assertThat(bombTroop.isDeploying()).isFalse();

    // Now SpawnerSystem should self-destruct the bomb
    freshSystem.update(0.1f);
    assertThat(bomb.getHealth().isAlive()).isFalse();

    // processDeaths fires onDeath -> death damage AOE
    freshState.processDeaths();

    assertThat(nearEnemy.getHealth().getCurrent()).isEqualTo(400); // 500 - 100
    assertThat(farEnemy.getHealth().getCurrent()).isEqualTo(500); // No damage
  }

  @Test
  void deathSpawn_normalUnitWithDeathMechanics_shouldGetSpawnerComponent() {
    // Golemite-like unit: has health AND deathDamage
    GameState freshState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(freshState);
    ProjectileSystem projectileSystem = new ProjectileSystem(freshState, aoeDamageService);
    CombatSystem combatSystem = new CombatSystem(freshState, aoeDamageService, projectileSystem);
    SpawnerSystem freshSystem = new SpawnerSystem(freshState, new AoeDamageService(freshState));
    freshState.setDeathHandler(freshSystem::onDeath);

    TroopStats golemiteStats =
        TroopStats.builder()
            .name("Golemite")
            .health(394)
            .damage(26)
            .deathDamage(50)
            .deathDamageRadius(1.5f)
            .speed(0.75f)
            .mass(5.0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    // Parent Golem that death-spawns Golemites
    SpawnerComponent golemSpawner =
        SpawnerComponent.builder()
            .deathSpawns(List.of(new DeathSpawnEntry(golemiteStats, 2, 1.5f, 0f, 0f, null, null)))
            .build();

    Troop golem =
        Troop.builder()
            .name("Golem")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(golemSpawner)
            .build();

    // Enemy nearby to verify death damage propagation
    Troop nearEnemy =
        Troop.builder()
            .name("NearEnemy")
            .team(Team.RED)
            .position(new Position(10.5f, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    freshState.spawnEntity(golem);
    freshState.spawnEntity(nearEnemy);
    freshState.processPending();
    nearEnemy.update(1.0f); // finish deploy

    // Kill Golem -> spawns 2 Golemites
    golem.getHealth().takeDamage(1);
    freshState.processDeaths();
    assertThat(freshState.getPendingSpawns()).hasSize(2);

    // Golemites should have SpawnerComponent with death damage (NOT selfDestruct since health > 0)
    Entity golemite = freshState.getPendingSpawns().get(0);
    assertThat(golemite.getSpawner()).isNotNull();
    assertThat(golemite.getSpawner().getDeathDamage()).isEqualTo(50);
    assertThat(golemite.getSpawner().isSelfDestruct()).isFalse();

    freshState.processPending();

    // Kill a Golemite -> should fire its death damage
    golemite.getHealth().takeDamage(golemite.getHealth().getCurrent());
    freshState.processDeaths();

    assertThat(nearEnemy.getHealth().getCurrent()).isEqualTo(450); // 500 - 50
  }

  @Test
  void spawner_shouldRespectSpawnStartTimeWhenSet() {
    // A spawner with explicit spawnStartTime should wait that duration before first wave
    GameState freshState = new GameState();
    SpawnerSystem freshSystem = new SpawnerSystem(freshState);

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .spawnPauseTime(5.0f)
            .spawnStartTime(1.0f)
            .unitsPerWave(1)
            .spawnStats(skeletonStats)
            .build();
    spawner.initialize();

    Building witchBuilding =
        Building.builder()
            .name("TestWitch")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(100))
            .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
            .lifetime(40f)
            .spawner(spawner)
            .build();

    freshState.spawnEntity(witchBuilding);
    freshState.processPending();
    witchBuilding.update(1.0f); // finish deploy

    // First tick -- should NOT spawn (spawnStartTime=1.0)
    freshSystem.update(0f);
    assertThat(freshState.getPendingSpawns()).isEmpty();

    // At 0.9s -- still waiting
    freshSystem.update(0.9f);
    assertThat(freshState.getPendingSpawns()).isEmpty();

    // At 1.1s -- spawnStartTime elapsed, first wave spawns
    freshSystem.update(0.2f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
    assertThat(freshState.getPendingSpawns().get(0).getName()).isEqualTo("Skeleton");
    freshState.processPending();

    // After first wave, subsequent waves use spawnPauseTime (5.0s)
    freshSystem.update(4.9f);
    assertThat(freshState.getPendingSpawns()).isEmpty();

    freshSystem.update(0.2f);
    assertThat(freshState.getPendingSpawns()).hasSize(1);
  }

  @Test
  void onDeath_shouldApplyDeathPushback() {
    // Golem-like unit with death damage + pushback
    GameState freshState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(freshState);
    ProjectileSystem projectileSystem = new ProjectileSystem(freshState, aoeDamageService);
    CombatSystem combatSystem = new CombatSystem(freshState, aoeDamageService, projectileSystem);
    SpawnerSystem freshSystem = new SpawnerSystem(freshState, new AoeDamageService(freshState));

    SpawnerComponent golemSpawner =
        SpawnerComponent.builder()
            .deathDamage(259)
            .deathDamageRadius(2.0f)
            .deathPushback(1.8f)
            .build();

    Troop golem =
        Troop.builder()
            .name("Golem")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(golemSpawner)
            .build();

    // Enemy nearby -- should take damage AND be knocked back
    Troop nearEnemy =
        Troop.builder()
            .name("NearEnemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();

    freshState.spawnEntity(golem);
    freshState.spawnEntity(nearEnemy);
    freshState.processPending();
    nearEnemy.update(1.0f); // finish deploy

    freshSystem.onDeath(golem);

    // Should take death damage
    assertThat(nearEnemy.getHealth().getCurrent()).isEqualTo(241); // 500 - 259
    // Should be knocked back
    assertThat(nearEnemy.getMovement().isKnockedBack()).isTrue();
  }

  @Test
  void onDeath_deathPushbackShouldRespectIgnorePushback() {
    // Same setup but enemy has ignorePushback=true
    GameState freshState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(freshState);
    ProjectileSystem projectileSystem = new ProjectileSystem(freshState, aoeDamageService);
    CombatSystem combatSystem = new CombatSystem(freshState, aoeDamageService, projectileSystem);
    SpawnerSystem freshSystem = new SpawnerSystem(freshState, new AoeDamageService(freshState));

    SpawnerComponent golemSpawner =
        SpawnerComponent.builder()
            .deathDamage(259)
            .deathDamageRadius(2.0f)
            .deathPushback(1.8f)
            .build();

    Troop golem =
        Troop.builder()
            .name("Golem")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(golemSpawner)
            .build();

    // Enemy with ignorePushback -- should take damage but NOT be knocked back
    Movement immuneMovement = new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND);
    immuneMovement.setIgnorePushback(true);

    Troop immune =
        Troop.builder()
            .name("ImmuneEnemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(immuneMovement)
            .deployTime(0f)
            .build();

    freshState.spawnEntity(golem);
    freshState.spawnEntity(immune);
    freshState.processPending();
    immune.update(1.0f); // finish deploy

    freshSystem.onDeath(golem);

    // Should take death damage
    assertThat(immune.getHealth().getCurrent()).isEqualTo(241); // 500 - 259
    // Should NOT be knocked back (ignorePushback)
    assertThat(immune.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void deathSpawn_withDeployTime_shouldSpawnInDeployingState() {
    // GoblinCage-like building: death-spawns a GoblinBrawler with 0.5s deploy delay
    GameState freshState = new GameState();
    SpawnerSystem freshSystem = new SpawnerSystem(freshState);
    freshState.setDeathHandler(freshSystem::onDeath);

    TroopStats brawlerStats =
        TroopStats.builder()
            .name("GoblinBrawler")
            .health(500)
            .damage(120)
            .speed(1.0f)
            .mass(1.0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    // Death spawn entry with 0.5s deploy time
    List<DeathSpawnEntry> deathSpawns =
        List.of(new DeathSpawnEntry(brawlerStats, 1, 0f, 0.5f, 0f, null, null));

    SpawnerComponent cageSpawner = SpawnerComponent.builder().deathSpawns(deathSpawns).build();

    Building cage =
        Building.builder()
            .name("GoblinCage")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
            .lifetime(30f)
            .spawner(cageSpawner)
            .build();

    freshState.spawnEntity(cage);
    freshState.processPending();

    // Kill the cage -> triggers death spawn
    cage.getHealth().takeDamage(1);
    freshState.processDeaths();

    assertThat(freshState.getPendingSpawns()).hasSize(1);
    Entity spawned = freshState.getPendingSpawns().get(0);
    assertThat(spawned.getName()).isEqualTo("GoblinBrawler");
    assertThat(spawned).isInstanceOf(Troop.class);

    freshState.processPending();
    Troop brawler = (Troop) spawned;

    // Brawler should be in deploying state (0.5s deploy delay from death spawn)
    assertThat(brawler.isDeploying()).isTrue();
    assertThat(brawler.isTargetable()).isFalse();

    // Advance partially -- still deploying
    brawler.update(0.4f);
    assertThat(brawler.isDeploying()).isTrue();
    assertThat(brawler.isTargetable()).isFalse();

    // Advance past deploy time
    brawler.update(0.2f);
    assertThat(brawler.isDeploying()).isFalse();
    assertThat(brawler.isTargetable()).isTrue();
  }
}
