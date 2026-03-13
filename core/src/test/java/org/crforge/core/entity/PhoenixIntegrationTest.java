package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Phoenix death -> egg -> respawn chain: Phoenix (AIR) dies ->
 * PhoenixFireball AOE at death position -> spawns PhoenixEgg (GROUND) -> PhoenixEgg waits 4.3s ->
 * spawns PhoenixNoRespawn (AIR) -> PhoenixEgg self-destructs -> PhoenixNoRespawn dies permanently.
 */
class PhoenixIntegrationTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private SpawnerSystem spawnerSystem;

  // Pre-built stats for the Phoenix chain
  private TroopStats phoenixNoRespawnStats;
  private TroopStats phoenixEggStats;
  private ProjectileStats phoenixFireballStats;
  private TroopStats phoenixStats;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState, new AoeDamageService(gameState));
    spawnerSystem = new SpawnerSystem(gameState, new AoeDamageService(gameState));
    combatSystem.setUnitSpawner(spawnerSystem::spawnUnit);
    gameState.setDeathHandler(spawnerSystem::onDeath);

    // Build the chain bottom-up: PhoenixNoRespawn -> PhoenixEgg -> PhoenixFireball -> Phoenix
    phoenixNoRespawnStats =
        TroopStats.builder()
            .name("PhoenixNoRespawn")
            .health(411)
            .damage(85)
            .speed(1.0f)
            .range(1.6f)
            .attackCooldown(1.0f)
            .movementType(MovementType.AIR)
            .targetType(TargetType.ALL)
            .deployTime(0.733f)
            .build();

    phoenixEggStats =
        TroopStats.builder()
            .name("PhoenixEgg")
            .health(94)
            .damage(0)
            .speed(0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .liveSpawn(
                new LiveSpawnConfig("PhoenixNoRespawn", 1, 4.3f, 0f, 4.3f, 0f, false, 1, true))
            .spawnTemplate(phoenixNoRespawnStats)
            .build();

    phoenixFireballStats =
        ProjectileStats.builder()
            .name("PhoenixFireball")
            .damage(64)
            .speed(10.0f)
            .radius(2.5f)
            .homing(false)
            .aoeToAir(true)
            .aoeToGround(true)
            .pushback(2.0f)
            .spawnCharacterName("PhoenixEgg")
            .spawnCharacter(phoenixEggStats)
            .spawnCharacterCount(1)
            .build();

    phoenixStats =
        TroopStats.builder()
            .name("Phoenix")
            .health(411)
            .damage(85)
            .speed(1.0f)
            .range(1.6f)
            .attackCooldown(1.0f)
            .movementType(MovementType.AIR)
            .targetType(TargetType.ALL)
            .deathSpawnProjectile(phoenixFireballStats)
            .build();
  }

  /** Helper to spawn a Phoenix troop with proper SpawnerComponent wiring. */
  private Troop spawnPhoenix(Team team, float x, float y) {
    spawnerSystem.spawnUnit(x, y, team, phoenixStats, Rarity.LEGENDARY, 1);
    gameState.processPending();
    return gameState.getEntitiesOfType(Troop.class).stream()
        .filter(t -> t.getName().equals("Phoenix"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void phoenixDeath_shouldFirePhoenixFireball() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Kill the Phoenix
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();

    // The death should have spawned a PhoenixFireball projectile
    List<Projectile> projectiles = gameState.getProjectiles();
    assertThat(projectiles).hasSize(1);
    Projectile fireball = projectiles.get(0);
    assertThat(fireball.getDamage()).isGreaterThan(0);
    assertThat(fireball.getAoeRadius()).isCloseTo(2.5f, org.assertj.core.data.Offset.offset(0.01f));
    assertThat(fireball.getSpawnCharacterStats()).isNotNull();
    assertThat(fireball.getSpawnCharacterStats().getName()).isEqualTo("PhoenixEgg");
  }

  @Test
  void phoenixFireball_shouldDealAoeDamage() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Place an enemy near the Phoenix (deployTime=0 so it's immediately targetable)
    Troop enemy =
        Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(9f, 16.5f))
            .health(new Health(500))
            .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    gameState.spawnEntity(enemy);
    gameState.processPending();

    int hpBefore = enemy.getHealth().getCurrent();

    // Kill the Phoenix
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();

    // Process the projectile hitting (position-targeted at same position, so it arrives next tick)
    combatSystem.update(GameEngine.DELTA_TIME);

    // Process pending to get the spawned egg
    gameState.processPending();

    // Enemy should have taken damage from the AOE
    assertThat(enemy.getHealth().getCurrent()).isLessThan(hpBefore);
  }

  @Test
  void phoenixFireball_shouldSpawnPhoenixEgg() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Kill the Phoenix
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();

    // Process the projectile (it arrives instantly since start == dest)
    combatSystem.update(GameEngine.DELTA_TIME);
    gameState.processPending();

    // A PhoenixEgg should have been spawned
    List<Troop> eggs =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixEgg"))
            .toList();
    assertThat(eggs).hasSize(1);
    Troop egg = eggs.get(0);
    assertThat(egg.getTeam()).isEqualTo(Team.BLUE);
    // Egg should have a spawner component with liveSpawn capability
    assertThat(egg.getSpawner()).isNotNull();
    assertThat(egg.getSpawner().hasLiveSpawn()).isTrue();
  }

  @Test
  void phoenixEgg_shouldSpawnPhoenixNoRespawnAfterDelay() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Kill Phoenix -> projectile -> egg
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();
    combatSystem.update(GameEngine.DELTA_TIME);
    gameState.processPending();

    Troop egg =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixEgg"))
            .findFirst()
            .orElseThrow();

    // Advance past egg's deploy time (instant for spawned units) to allow spawner ticking
    egg.update(0f);

    // Wait less than 4.3s -- no spawn yet
    spawnerSystem.update(4.0f);
    gameState.processPending();
    List<Troop> noRespawns =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixNoRespawn"))
            .toList();
    assertThat(noRespawns).as("PhoenixNoRespawn should not spawn before 4.3s").isEmpty();

    // Wait past 4.3s total
    spawnerSystem.update(0.4f);
    gameState.processPending();
    noRespawns =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixNoRespawn"))
            .toList();
    assertThat(noRespawns).hasSize(1);
    assertThat(noRespawns.get(0).getTeam()).isEqualTo(Team.BLUE);
    assertThat(noRespawns.get(0).getMovementType()).isEqualTo(MovementType.AIR);
  }

  @Test
  void phoenixEgg_shouldSelfDestructAfterSpawning() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Kill Phoenix -> projectile -> egg
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();
    combatSystem.update(GameEngine.DELTA_TIME);
    gameState.processPending();

    Troop egg =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixEgg"))
            .findFirst()
            .orElseThrow();
    egg.update(0f);

    // Wait past 4.3s to trigger spawn
    spawnerSystem.update(4.4f);
    gameState.processPending();

    // The egg should have been killed (spawnLimit=1, destroyAtLimit=true)
    assertThat(egg.isAlive()).isFalse();

    // PhoenixNoRespawn should be alive
    List<Troop> noRespawns =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixNoRespawn"))
            .toList();
    assertThat(noRespawns).hasSize(1);
    assertThat(noRespawns.get(0).isAlive()).isTrue();
  }

  @Test
  void phoenixNoRespawn_shouldDiePermanently() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Full chain: Phoenix dies -> egg -> hatch
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();
    combatSystem.update(GameEngine.DELTA_TIME);
    gameState.processPending();

    Troop egg =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixEgg"))
            .findFirst()
            .orElseThrow();
    egg.update(0f);
    spawnerSystem.update(4.4f);
    gameState.processPending();
    gameState.processDeaths();
    gameState.processPending();

    Troop noRespawn =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixNoRespawn"))
            .findFirst()
            .orElseThrow();

    // Kill PhoenixNoRespawn
    noRespawn.getHealth().takeDamage(noRespawn.getHealth().getCurrent());
    gameState.processDeaths();
    gameState.processPending();

    // It should stay dead -- no new eggs, no new projectiles, no new spawns
    assertThat(noRespawn.isAlive()).isFalse();
    List<Troop> aliveTroops =
        gameState.getEntitiesOfType(Troop.class).stream().filter(Entity::isAlive).toList();
    assertThat(aliveTroops)
        .as("No Phoenix units should remain alive after PhoenixNoRespawn dies")
        .isEmpty();
  }

  @Test
  void killingPhoenixEgg_shouldPreventRespawn() {
    Troop phoenix = spawnPhoenix(Team.BLUE, 9f, 16f);

    // Phoenix dies -> egg spawns
    phoenix.getHealth().takeDamage(phoenix.getHealth().getCurrent());
    gameState.processDeaths();
    combatSystem.update(GameEngine.DELTA_TIME);
    gameState.processPending();

    Troop egg =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixEgg"))
            .findFirst()
            .orElseThrow();
    egg.update(0f);

    // Wait 2s (before hatch time)
    spawnerSystem.update(2.0f);
    gameState.processPending();

    // Kill the egg before it hatches
    egg.getHealth().takeDamage(egg.getHealth().getCurrent());
    gameState.processDeaths();
    gameState.processPending();

    // Wait past 4.3s total -- no PhoenixNoRespawn should appear
    spawnerSystem.update(3.0f);
    gameState.processPending();

    List<Troop> noRespawns =
        gameState.getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("PhoenixNoRespawn"))
            .toList();
    assertThat(noRespawns).as("Killing egg before hatch should prevent respawn").isEmpty();
  }
}
