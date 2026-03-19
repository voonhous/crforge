package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HunterScatterTest {

  private GameState gameState;
  private CombatSystem combatSystem;

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
  }

  @Test
  void scatter_shouldFireMultipleProjectiles() {
    Troop hunter = createHunter(Team.BLUE, 9f, 10f);
    Troop target = createTarget(Team.RED, 9f, 13f);

    gameState.spawnEntity(hunter);
    gameState.spawnEntity(target);
    gameState.processPending();
    // Finish deploy
    hunter.setDeployTimer(0);
    target.setDeployTimer(0);

    // Set target and make attack ready
    hunter.getCombat().setCurrentTarget(target);
    hunter.getCombat().startAttackSequence();
    // Clear windup so attack executes
    hunter.getCombat().setCurrentWindup(0);

    combatSystem.update(1.0f / 30f);

    // Hunter should fire 10 pellets
    assertThat(gameState.getProjectiles()).hasSize(10);

    // All pellets should be piercing
    for (Projectile pellet : gameState.getProjectiles()) {
      assertThat(pellet.isPiercing()).isTrue();
    }
  }

  @Test
  void scatter_closeRange_allPelletsHitSameTarget() {
    // Place target very close so all 10 pellets hit.
    // At 1 tile distance, start offset 0.65 means remaining 0.35 tiles to target.
    // Even the widest pellet at 50 degrees: perpendicular distance = 0.35 * sin(50) = 0.268
    // which is within effective hit radius (0.07 aoe + 0.5 collision = 0.57).
    Troop hunter = createHunter(Team.BLUE, 9f, 10f);
    Troop target = createTarget(Team.RED, 9f, 11f);

    gameState.spawnEntity(hunter);
    gameState.spawnEntity(target);
    gameState.processPending();
    hunter.setDeployTimer(0);
    target.setDeployTimer(0);

    int initialHp = target.getHealth().getCurrent();

    // Set target and make attack ready
    hunter.getCombat().setCurrentTarget(target);
    hunter.getCombat().startAttackSequence();
    hunter.getCombat().setCurrentWindup(0);

    float deltaTime = 1.0f / 30f;
    combatSystem.update(deltaTime);
    // Clear target to prevent re-firing (CombatSystem now ticks combat timers)
    hunter.getCombat().clearTarget();
    // Tick enough for pellets to reach the close target
    for (int i = 0; i < 99; i++) {
      combatSystem.update(deltaTime);
    }

    // At close range, all 10 pellets should hit -> 10 * 33 = 330 total damage
    int damageTaken = initialHp - target.getHealth().getCurrent();
    assertThat(damageTaken).isEqualTo(330);
  }

  @Test
  void scatter_longRange_fewerPelletsHit() {
    // Place target at 3.5 tiles -- within attack range (4.0 + collision radii)
    // but far enough for pellets to spread. At 2.85 tiles from start, only the
    // center 3 pellets (0, +/-10 degrees) are within hit radius.
    Troop hunter = createHunter(Team.BLUE, 9f, 10f);
    Troop target = createTarget(Team.RED, 9f, 13.5f);

    gameState.spawnEntity(hunter);
    gameState.spawnEntity(target);
    gameState.processPending();
    hunter.setDeployTimer(0);
    target.setDeployTimer(0);

    int initialHp = target.getHealth().getCurrent();

    hunter.getCombat().setCurrentTarget(target);
    hunter.getCombat().startAttackSequence();
    hunter.getCombat().setCurrentWindup(0);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200; i++) {
      combatSystem.update(deltaTime);
    }

    // At long range, fewer pellets should hit due to spread
    int damageTaken = initialHp - target.getHealth().getCurrent();
    // Should hit with some pellets but not all 10
    assertThat(damageTaken).isGreaterThan(0);
    assertThat(damageTaken).isLessThan(330);
  }

  @Test
  void scatter_pelletsShouldStopOnFirstHit() {
    // Two enemies in the same line -- pellets should stop on the first enemy they hit
    Troop hunter = createHunter(Team.BLUE, 9f, 10f);
    Troop enemy1 = createTarget(Team.RED, 9f, 12f);
    Troop enemy2 = createTarget(Team.RED, 9f, 14f);

    gameState.spawnEntity(hunter);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();
    hunter.setDeployTimer(0);
    enemy1.setDeployTimer(0);
    enemy2.setDeployTimer(0);

    int enemy1InitialHp = enemy1.getHealth().getCurrent();
    int enemy2InitialHp = enemy2.getHealth().getCurrent();

    hunter.getCombat().setCurrentTarget(enemy1);
    hunter.getCombat().startAttackSequence();
    hunter.getCombat().setCurrentWindup(0);

    float deltaTime = 1.0f / 30f;
    for (int i = 0; i < 200; i++) {
      combatSystem.update(deltaTime);
    }

    // Front enemy should take damage
    assertThat(enemy1.getHealth().getCurrent()).isLessThan(enemy1InitialHp);
    // Back enemy should take zero damage (pellets stopped on first hit)
    assertThat(enemy2.getHealth().getCurrent()).isEqualTo(enemy2InitialHp);
  }

  /**
   * Creates a Hunter troop with scatter projectile stats matching the JSON data: 10 pellets, 33
   * damage each, scatter="Line", projectileRange=6.5, speed=9.167, aoeRadius=0.07
   */
  private Troop createHunter(Team team, float x, float y) {
    ProjectileStats hunterProjectile =
        ProjectileStats.builder()
            .name("HunterProjectile")
            .damage(33)
            .speed(550f / 60f) // 550 raw / 60 = 9.167 tiles/sec
            .radius(0.07f)
            .homing(false)
            .scatter("Line")
            .projectileRange(6.5f)
            .aoeToGround(true)
            .aoeToAir(true)
            .checkCollisions(true)
            .build();

    return Troop.builder()
        .name("Hunter")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(700))
        .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(33)
                .range(4.0f)
                .sightRange(5.5f)
                .attackCooldown(2.2f)
                .multipleProjectiles(10)
                .projectileStats(hunterProjectile)
                .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }

  /** Creates a target troop with enough HP to survive multiple pellet hits. */
  private Troop createTarget(Team team, float x, float y) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder().damage(50).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }
}
