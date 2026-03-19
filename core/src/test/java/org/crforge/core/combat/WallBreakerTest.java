package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.component.AttackStateMachine;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.EntityTimerSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Wall Breakers: 2-elixir kamikaze troop that targets only buildings, dealing melee AOE
 * damage (radius 1.5) on attack and dying immediately after.
 */
class WallBreakerTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private TargetingSystem targetingSystem;
  private final EntityTimerSystem entityTimerSystem = new EntityTimerSystem();

  private static final int WALL_BREAKER_DAMAGE = 137;
  private static final float AOE_RADIUS = 1.5f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    targetingSystem = new TargetingSystem();
  }

  @Test
  void attack_killsSelfAndDamagesBuilding() {
    Troop wallBreaker = createWallBreaker(Team.BLUE, 5, 5);
    Building tower = createBuilding(Team.RED, 6, 5, 2000);

    gameState.spawnEntity(wallBreaker);
    gameState.spawnEntity(tower);
    gameState.processPending();

    // Finish deploy
    wallBreaker.setDeployTimer(0);

    wallBreaker.getCombat().setCurrentTarget(tower);
    // Windup = max(0, attackCooldown - loadTime) = max(0, 1.2 - 1.0) = 0.2s
    runCombatUpdates(0.5f);

    assertThat(wallBreaker.getHealth().isDead())
        .as("Wall Breaker should die after attack (kamikaze)")
        .isTrue();

    assertThat(tower.getHealth().getCurrent())
        .as("Building should take %d damage", WALL_BREAKER_DAMAGE)
        .isEqualTo(2000 - WALL_BREAKER_DAMAGE);
  }

  @Test
  void attack_dealsAoeDamageToNearbyEnemies() {
    Troop wallBreaker = createWallBreaker(Team.BLUE, 5, 5);
    Building tower = createBuilding(Team.RED, 6, 5, 2000);
    // Place enemy troop within AOE radius (1.5) of the tower
    Troop enemy = createEnemy(Team.RED, 6, 6, 500);

    gameState.spawnEntity(wallBreaker);
    gameState.spawnEntity(tower);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    wallBreaker.setDeployTimer(0);
    enemy.setDeployTimer(0);

    wallBreaker.getCombat().setCurrentTarget(tower);
    runCombatUpdates(0.5f);

    assertThat(tower.getHealth().getCurrent())
        .as("Building should take AOE damage")
        .isEqualTo(2000 - WALL_BREAKER_DAMAGE);

    // Enemy is 1.0 tile from tower center; effective AOE = 1.5 + 0.4 (collision) = 1.9
    assertThat(enemy.getHealth().getCurrent())
        .as("Nearby enemy should take AOE splash damage")
        .isEqualTo(500 - WALL_BREAKER_DAMAGE);
  }

  @Test
  void aoe_doesNotDamageAllies() {
    Troop wallBreaker = createWallBreaker(Team.BLUE, 5, 5);
    Building tower = createBuilding(Team.RED, 6, 5, 2000);
    // Friendly troop right next to the target building
    Troop ally = createEnemy(Team.BLUE, 6, 6, 500);

    gameState.spawnEntity(wallBreaker);
    gameState.spawnEntity(tower);
    gameState.spawnEntity(ally);
    gameState.processPending();

    wallBreaker.setDeployTimer(0);
    ally.setDeployTimer(0);

    wallBreaker.getCombat().setCurrentTarget(tower);
    runCombatUpdates(0.5f);

    assertThat(ally.getHealth().getCurrent())
        .as("Friendly unit near target building should take no AOE damage")
        .isEqualTo(500);
  }

  @Test
  void targetsOnlyBuildings_ignoresTroops() {
    Troop wallBreaker = createWallBreaker(Team.BLUE, 5, 5);
    // Enemy troop is closer than the building
    Troop enemyTroop = createEnemy(Team.RED, 5.5f, 5, 500);
    Building tower = createBuilding(Team.RED, 10, 5, 2000);

    gameState.spawnEntity(wallBreaker);
    gameState.spawnEntity(enemyTroop);
    gameState.spawnEntity(tower);
    gameState.processPending();

    wallBreaker.setDeployTimer(0);
    enemyTroop.setDeployTimer(0);

    // Let targeting system pick the target
    targetingSystem.updateTargets(gameState.getAliveEntities());

    assertThat(wallBreaker.getCombat().getCurrentTarget())
        .as("Wall Breaker should target the building, ignoring the closer troop")
        .isEqualTo(tower);
  }

  @Test
  void aoe_doesNotDamageEntitiesOutsideRadius() {
    Troop wallBreaker = createWallBreaker(Team.BLUE, 5, 5);
    Building tower = createBuilding(Team.RED, 6, 5, 2000);
    // Place enemy well outside AOE: effective radius = 1.5 + 0.4 = 1.9 tiles from tower center
    // Distance of 3.0 tiles from tower is clearly outside the radius
    Troop farEnemy = createEnemy(Team.RED, 6, 8, 500);

    gameState.spawnEntity(wallBreaker);
    gameState.spawnEntity(tower);
    gameState.spawnEntity(farEnemy);
    gameState.processPending();

    wallBreaker.setDeployTimer(0);
    farEnemy.setDeployTimer(0);

    wallBreaker.getCombat().setCurrentTarget(tower);
    runCombatUpdates(0.5f);

    assertThat(tower.getHealth().getCurrent())
        .as("Building should take damage")
        .isEqualTo(2000 - WALL_BREAKER_DAMAGE);

    assertThat(farEnemy.getHealth().getCurrent())
        .as("Enemy outside AOE radius should take no damage")
        .isEqualTo(500);
  }

  private Troop createWallBreaker(Team team, float x, float y) {
    Combat combat =
        Combat.builder()
            .damage(WALL_BREAKER_DAMAGE)
            .range(0.5f)
            .sightRange(5.5f)
            .attackCooldown(1.2f)
            .loadTime(1.0f)
            .attackState(AttackStateMachine.withLoad(1.0f)) // Preloaded
            .aoeRadius(AOE_RADIUS)
            .kamikaze(true)
            .targetOnlyBuildings(true)
            .targetType(TargetType.GROUND)
            .build();

    return Troop.builder()
        .name("WallBreaker")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(129))
        .movement(new Movement(2.0f, 4.0f, 0.4f, 0.4f, MovementType.GROUND))
        .deployTime(1.0f)
        .combat(combat)
        .build();
  }

  private Building createBuilding(Team team, float x, float y, int hp) {
    return Building.builder()
        .name("Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  private Troop createEnemy(Team team, float x, float y, int hp) {
    return Troop.builder()
        .name("Enemy")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(1.0f, 1.0f, 0.4f, 0.4f, MovementType.GROUND))
        .deployTime(1.0f)
        .build();
  }

  private void runCombatUpdates(float duration) {
    float dt = 0.1f;
    int ticks = Math.round(duration / dt);
    for (int i = 0; i < ticks; i++) {
      gameState.refreshCaches();
      entityTimerSystem.update(gameState.getAliveEntities(), dt);
      combatSystem.update(dt);
    }
  }
}
