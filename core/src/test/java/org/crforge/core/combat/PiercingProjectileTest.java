package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PiercingProjectileTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private PhysicsSystem physicsSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    Arena arena = new Arena("TestArena");
    physicsSystem = new PhysicsSystem(arena);
    physicsSystem.setGameState(gameState);
  }

  @Test
  void piercingProjectile_shouldTravelFullRange() {
    // Create a piercing projectile traveling upward with range 7.5 tiles
    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    // Speed is 2.833 tiles/sec at 30fps -> ~0.0944 tiles/tick
    // 7.5 / 0.0944 ~= 79.5 ticks to travel full range
    float deltaTime = 1.0f / 30f;
    int ticks = 0;
    while (boulder.isActive() && ticks < 200) {
      combatSystem.update(deltaTime);
      ticks++;
    }

    assertThat(boulder.isActive()).isFalse();
    // Should have deactivated after traveling ~7.5 tiles
    assertThat(boulder.getPosition().getY()).isGreaterThan(17f);
  }

  @Test
  void piercingProjectile_shouldHitMultipleEnemiesAlongPath() {
    // Three enemies in a line along the projectile's path
    Troop enemy1 = createTroop(Team.RED, 9f, 12f, false, MovementType.GROUND);
    Troop enemy2 = createTroop(Team.RED, 9f, 14f, false, MovementType.GROUND);
    Troop enemy3 = createTroop(Team.RED, 9f, 16f, false, MovementType.GROUND);

    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.spawnEntity(enemy3);
    gameState.processPending();
    enemy1.update(2.0f);
    enemy2.update(2.0f);
    enemy3.update(2.0f);

    // Fire boulder upward from (9, 10)
    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200 && boulder.isActive(); i++) {
      combatSystem.update(deltaTime);
    }

    // All three enemies should have taken damage
    assertThat(enemy1.getHealth().getCurrent()).isLessThan(500);
    assertThat(enemy2.getHealth().getCurrent()).isLessThan(500);
    assertThat(enemy3.getHealth().getCurrent()).isLessThan(500);
  }

  @Test
  void piercingProjectile_shouldHitEachEntityOnlyOnce() {
    // Place enemy right in the boulder's path, large collision radius means it overlaps for
    // multiple ticks
    Troop enemy = createTroop(Team.RED, 9f, 12f, false, MovementType.GROUND);

    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

    int initialHp = enemy.getHealth().getCurrent();

    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200 && boulder.isActive(); i++) {
      combatSystem.update(deltaTime);
    }

    // Should only take damage once (113 from the boulder)
    int expectedHp = initialHp - 113;
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(expectedHp);
  }

  @Test
  void piercingProjectile_shouldApplyDirectionalKnockback() {
    // Enemy in the boulder's path (traveling upward, dir = 0, 1)
    Troop enemy = createTroop(Team.RED, 9f, 12f, false, MovementType.GROUND);

    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    float deltaTime = 1.0f / 30f;
    // Tick until the enemy is hit
    for (int i = 0; i < 200 && !enemy.getMovement().isKnockedBack(); i++) {
      combatSystem.update(deltaTime);
    }

    assertThat(enemy.getMovement().isKnockedBack()).isTrue();

    // Record Y before physics tick
    float yBefore = enemy.getPosition().getY();

    // Tick physics to apply knockback displacement
    physicsSystem.update(gameState.getAliveEntities(), deltaTime);

    // Knockback should be in the projectile's travel direction (positive Y = upward)
    assertThat(enemy.getPosition().getY()).isGreaterThan(yBefore);
  }

  @Test
  void piercingProjectile_shouldNotHitAirUnits() {
    // Air unit in the boulder's path
    Troop airUnit = createTroop(Team.RED, 9f, 12f, false, MovementType.AIR);

    gameState.spawnEntity(airUnit);
    gameState.processPending();
    airUnit.update(2.0f);

    int initialHp = airUnit.getHealth().getCurrent();

    // Boulder with aoeToGround=true, aoeToAir=false
    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200 && boulder.isActive(); i++) {
      combatSystem.update(deltaTime);
    }

    // Air unit should NOT have been hit
    assertThat(airUnit.getHealth().getCurrent()).isEqualTo(initialHp);
  }

  @Test
  void piercingProjectile_shouldNotKnockbackBuildings() {
    // Building in the boulder's path -- should take damage but not be knocked back
    Building building =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(9f, 12f))
            .health(new Health(500))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .lifetime(30f)
            .remainingLifetime(30f)
            .deployTime(1.0f)
            .deployTimer(0f)
            .build();

    gameState.spawnEntity(building);
    gameState.processPending();

    int initialHp = building.getHealth().getCurrent();

    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200 && boulder.isActive(); i++) {
      combatSystem.update(deltaTime);
    }

    // Building should take damage
    assertThat(building.getHealth().getCurrent()).isLessThan(initialHp);
    // Building should NOT be knocked back
    assertThat(building.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void piercingProjectile_shouldNotKnockbackIgnorePushbackEntities() {
    // Troop with ignorePushback in the boulder's path
    Troop immune = createTroop(Team.RED, 9f, 12f, true, MovementType.GROUND);

    gameState.spawnEntity(immune);
    gameState.processPending();
    immune.update(2.0f);

    int initialHp = immune.getHealth().getCurrent();

    Projectile boulder = createPiercingBoulder(Team.BLUE, 9f, 10f, 0f, 1f, 7.5f);
    gameState.spawnProjectile(boulder);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200 && boulder.isActive(); i++) {
      combatSystem.update(deltaTime);
    }

    // Should take damage
    assertThat(immune.getHealth().getCurrent()).isLessThan(initialHp);
    // Should NOT be knocked back
    assertThat(immune.getMovement().isKnockedBack()).isFalse();
  }

  /**
   * Creates a piercing projectile simulating a Bowler boulder. Speed: 2.833 tiles/sec, damage: 113,
   * aoeRadius: 1.8, pushback: 1.0 tile, aoeToGround: true, aoeToAir: false.
   */
  private Projectile createPiercingBoulder(
      Team team, float startX, float startY, float dirX, float dirY, float range) {
    // Use a dummy source troop for the entity-targeted constructor
    Troop source =
        Troop.builder()
            .name("Bowler")
            .team(team)
            .position(new Position(startX, startY))
            .health(new Health(1000))
            .movement(new Movement(0.7f, 0.7f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(113)
                    .range(5.0f)
                    .sightRange(5.5f)
                    .attackCooldown(2.5f)
                    .build())
            .deployTime(1.0f)
            .deployTimer(1.0f)
            .build();

    // Create a position-targeted projectile so we don't need a real target entity
    float targetX = startX + dirX * range;
    float targetY = startY + dirY * range;
    Projectile proj =
        new Projectile(
            team, startX, startY, targetX, targetY, 113, 1.8f, 2.833f, Collections.emptyList());
    proj.setPushback(1.0f);
    proj.configurePiercing(dirX, dirY, range, true, false);
    return proj;
  }

  /** Creates a troop with the given movement type for piercing projectile testing. */
  private Troop createTroop(
      Team team, float x, float y, boolean ignorePushback, MovementType movementType) {
    Movement movement = new Movement(1.0f, 1.0f, 0.5f, 0.5f, movementType);
    movement.setIgnorePushback(ignorePushback);

    return Troop.builder()
        .name("TestTroop")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .movement(movement)
        .combat(
            Combat.builder().damage(50).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }
}
