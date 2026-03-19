package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.DeploymentSystem;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for The Log spell: spellAsDeploy, two-stage projectile chain (LogProjectile ->
 * LogProjectileRolling), piercing damage, directional knockback, minDistance, crown tower damage
 * reduction, and attack reset on knockback.
 */
class LogSpellTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private ProjectileSystem projectileSystem;
  private DeploymentSystem deploymentSystem;
  private PhysicsSystem physicsSystem;

  // Log projectile stats (matching real data at level 1)
  private static final int LOG_PROJECTILE_DAMAGE = 0; // Stage 1 does no damage
  private static final float LOG_PROJECTILE_SPEED = 10f / 60f; // Scaled from raw speed
  private static final int ROLLING_BASE_DAMAGE = 240; // LogProjectileRolling base damage
  private static final float ROLLING_SPEED = 480f / 60f; // 8.0 tiles/sec
  private static final float ROLLING_RANGE = 11.5f; // projectileRange
  private static final float ROLLING_RADIUS = 1.0f; // projectileRadius for hit detection
  private static final float ROLLING_PUSHBACK = 1.5f; // pushback / 1000
  private static final float ROLLING_MIN_DISTANCE = 2.0f; // minDistance
  private static final int ROLLING_CTDP = -85; // crownTowerDamagePercent

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    projectileSystem = new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    deploymentSystem = new DeploymentSystem(gameState, aoeDamageService);
    Arena arena = new Arena("TestArena");
    physicsSystem = new PhysicsSystem(arena);
    physicsSystem.setGameState(gameState);
  }

  @Test
  void spellAsDeploy_projectileStartsAtDeployLocation() {
    // Deploy Log at (9, 14) for blue team
    float deployX = 9f;
    float deployY = 14f;

    Card logCard = createLogCard();
    ProjectileStats proj = logCard.getProjectile();

    // Simulate what DeploymentSystem.castSpell does for spellAsDeploy
    float startX = deployX;
    float startY = deployY;
    float forward = proj.getMinDistance() > 0 ? proj.getMinDistance() / 1000f : 3.0f;
    float destY = startY + forward; // Blue team goes forward (positive Y)

    Projectile p =
        new Projectile(
            Team.BLUE,
            startX,
            startY,
            deployX,
            destY,
            0,
            proj.getRadius(),
            proj.getSpeed(),
            Collections.emptyList());

    // Projectile should start AT the deploy point, not 10 tiles behind
    assertThat(p.getPosition().getX()).isEqualTo(deployX);
    assertThat(p.getPosition().getY()).isEqualTo(deployY);
  }

  @Test
  void spellProjectile_spawnsSubProjectileOnArrival() {
    // Create stage 1 projectile with spawnProjectile wired
    ProjectileStats rollingStats = createRollingStats();
    ProjectileStats stage1Stats =
        ProjectileStats.builder()
            .name("LogProjectile")
            .damage(0)
            .speed(LOG_PROJECTILE_SPEED)
            .spawnProjectile(rollingStats)
            .build();

    // Fire stage 1 from (9, 14) to (9, 14.5) -- short distance
    Projectile stage1 =
        new Projectile(
            Team.BLUE, 9f, 14f, 9f, 14.5f, 0, 0f, LOG_PROJECTILE_SPEED, Collections.emptyList());
    stage1.setSpawnProjectile(rollingStats);
    stage1.setSpellRarity(Rarity.LEGENDARY);
    stage1.setSpellLevel(1);

    gameState.spawnProjectile(stage1);

    // Advance until stage 1 hits
    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    int ticks = 0;
    while (stage1.isActive() && ticks < 300) {
      combatSystem.update(dt);
      ticks++;
    }

    // Stage 1 should have spawned a sub-projectile (the rolling log)
    List<Projectile> projectiles = gameState.getProjectiles();
    assertThat(projectiles).hasSizeGreaterThanOrEqualTo(1);

    // The rolling sub-projectile should be piercing
    Projectile rolling = projectiles.get(projectiles.size() - 1);
    assertThat(rolling.isPiercing()).isTrue();
    assertThat(rolling.isActive()).isTrue();
  }

  @Test
  void rollingProjectile_hitsMultipleGroundEnemies() {
    // Three ground enemies in a line along the rolling path
    Troop enemy1 = createGroundTroop(Team.RED, 9f, 17f);
    Troop enemy2 = createGroundTroop(Team.RED, 9f, 19f);
    Troop enemy3 = createGroundTroop(Team.RED, 9f, 21f);

    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.spawnEntity(enemy3);
    gameState.processPending();
    skipDeployTime(enemy1, enemy2, enemy3);

    // Create rolling projectile traveling upward from (9, 15)
    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // All three enemies should have taken damage
    assertThat(enemy1.getHealth().getCurrent()).isLessThan(500);
    assertThat(enemy2.getHealth().getCurrent()).isLessThan(500);
    assertThat(enemy3.getHealth().getCurrent()).isLessThan(500);
  }

  @Test
  void rollingProjectile_hitsEachEnemyOnlyOnce() {
    Troop enemy = createGroundTroop(Team.RED, 9f, 17f);

    gameState.spawnEntity(enemy);
    gameState.processPending();
    skipDeployTime(enemy);

    int initialHp = enemy.getHealth().getCurrent();

    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // Should only take damage once
    int expectedHp = initialHp - ROLLING_BASE_DAMAGE;
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(expectedHp);
  }

  @Test
  void rollingProjectile_doesNotHitAirUnits() {
    // Air unit in the rolling path
    Troop airUnit = createTroop(Team.RED, 9f, 17f, MovementType.AIR);

    gameState.spawnEntity(airUnit);
    gameState.processPending();
    skipDeployTime(airUnit);

    int initialHp = airUnit.getHealth().getCurrent();

    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // Air unit should NOT have been hit
    assertThat(airUnit.getHealth().getCurrent()).isEqualTo(initialHp);
  }

  @Test
  void rollingProjectile_appliesDirectionalPushback() {
    Troop enemy = createGroundTroop(Team.RED, 9f, 17f);

    gameState.spawnEntity(enemy);
    gameState.processPending();
    skipDeployTime(enemy);

    // Rolling projectile traveling upward (dir 0, 1)
    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    // Tick until the enemy is hit
    for (int i = 0; i < 300 && !enemy.getMovement().isKnockedBack(); i++) {
      combatSystem.update(dt);
    }

    assertThat(enemy.getMovement().isKnockedBack()).isTrue();

    // Record Y before physics tick
    float yBefore = enemy.getPosition().getY();
    physicsSystem.update(gameState.getAliveEntities(), dt);

    // Knockback should be in the projectile's travel direction (positive Y = upward)
    assertThat(enemy.getPosition().getY()).isGreaterThan(yBefore);
  }

  @Test
  void rollingProjectile_reducesDamageOnCrownTowers() {
    // Create a princess tower in the rolling path
    Tower crownTower =
        Tower.builder()
            .name("PrincessTower")
            .team(Team.RED)
            .position(new Position(9f, 17f))
            .health(new Health(3000))
            .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
            .combat(
                Combat.builder()
                    .damage(100)
                    .range(7.5f)
                    .sightRange(7.5f)
                    .attackCooldown(0.8f)
                    .build())
            .build();

    gameState.spawnEntity(crownTower);
    gameState.processPending();

    int initialHp = crownTower.getHealth().getCurrent();

    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // Crown tower should take reduced damage: 240 * (100 + (-85)) / 100 = 240 * 15% = 36
    int expectedDamage = ROLLING_BASE_DAMAGE * (100 + ROLLING_CTDP) / 100;
    assertThat(crownTower.getHealth().getCurrent()).isEqualTo(initialHp - expectedDamage);
  }

  @Test
  void rollingProjectile_minDistance_preventsEarlyHits() {
    // Place enemy very close to the start -- within minDistance
    Troop closeEnemy = createGroundTroop(Team.RED, 9f, 15.5f);

    gameState.spawnEntity(closeEnemy);
    gameState.processPending();
    skipDeployTime(closeEnemy);

    int initialHp = closeEnemy.getHealth().getCurrent();

    // Create rolling projectile starting at (9, 15) with minDistance = 2.0
    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // Enemy within minDistance should NOT have been hit -- the projectile passes through
    // before it has traveled far enough for hits to register
    assertThat(closeEnemy.getHealth().getCurrent()).isEqualTo(initialHp);
  }

  @Test
  void knockback_resetsAttackAnimation() {
    // Create an attacker mid-attack
    Troop attacker = createGroundTroop(Team.RED, 9f, 17f);
    Troop target = createGroundTroop(Team.BLUE, 10f, 17f);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();
    skipDeployTime(attacker, target);

    // Set attacker into attacking state
    attacker.getCombat().setCurrentTarget(target);
    attacker.getCombat().startAttackSequence();
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    // Apply knockback to attacker
    attacker.getMovement().startKnockback(0f, 1f, 1.0f, 0.5f, 1.0f);

    // Process combat -- knockback should reset the attack state
    combatSystem.update(1.0f / GameEngine.TICKS_PER_SECOND);

    assertThat(attacker.getCombat().isAttacking()).isFalse();
    assertThat(attacker.getCombat().getCurrentWindup()).isEqualTo(0);
  }

  @Test
  void spellAsDeploy_deployOnAlliedSide_shouldBeAccepted() {
    Standard1v1Match match = new Standard1v1Match();
    Player bluePlayer = createPlayerWithLogInHand();
    match.addPlayer(bluePlayer);

    // Blue zone placement (y=10 is well within blue's own side)
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 10f);
    assertThat(match.validateAction(bluePlayer, action)).isTrue();
  }

  @Test
  void spellAsDeploy_deployOnEnemySide_shouldBeRejected() {
    Standard1v1Match match = new Standard1v1Match();
    Player bluePlayer = createPlayerWithLogInHand();
    match.addPlayer(bluePlayer);

    // Red zone placement (y=25 is enemy territory for blue)
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    assertThat(match.validateAction(bluePlayer, action)).isFalse();
  }

  @Test
  void spellAsDeploy_deployOnBridge_shouldBeRejected() {
    Standard1v1Match match = new Standard1v1Match();
    Player bluePlayer = createPlayerWithLogInHand();
    match.addPlayer(bluePlayer);

    // Bridge location
    PlayerActionDTO action =
        PlayerActionDTO.play(0, Arena.LEFT_BRIDGE_X + 1.0f, Arena.RIVER_Y - 0.5f);
    assertThat(match.validateAction(bluePlayer, action)).isFalse();
  }

  // -- Helper methods --

  /** Creates a player with The Log as the first card in hand. */
  private Player createPlayerWithLogInHand() {
    List<Card> cards = new ArrayList<>();
    cards.add(createLogCard());
    for (int i = 1; i < 8; i++) {
      cards.add(Card.builder().name("Filler" + i).type(CardType.TROOP).cost(3).build());
    }
    return new Player(Team.BLUE, new Deck(cards), false);
  }

  /** Creates a Card matching The Log spell configuration. */
  private Card createLogCard() {
    ProjectileStats rollingStats = createRollingStats();

    ProjectileStats logProjectileStats =
        ProjectileStats.builder()
            .name("LogProjectile")
            .damage(0)
            .speed(LOG_PROJECTILE_SPEED)
            .spawnProjectile(rollingStats)
            .minDistance(ROLLING_MIN_DISTANCE * 1000f) // raw CSV units
            .build();

    return Card.builder()
        .id("log")
        .name("The Log")
        .type(CardType.SPELL)
        .cost(2)
        .rarity(Rarity.LEGENDARY)
        .projectile(logProjectileStats)
        .spellAsDeploy(true)
        .build();
  }

  /** Creates the rolling sub-projectile stats (LogProjectileRolling). */
  private ProjectileStats createRollingStats() {
    return ProjectileStats.builder()
        .name("LogProjectileRolling")
        .damage(ROLLING_BASE_DAMAGE)
        .speed(ROLLING_SPEED)
        .radius(0f) // No AOE splash
        .projectileRadius(ROLLING_RADIUS)
        .projectileRange(ROLLING_RANGE)
        .aoeToGround(true)
        .aoeToAir(false)
        .pushback(ROLLING_PUSHBACK)
        .pushbackAll(true)
        .minDistance(ROLLING_MIN_DISTANCE)
        .crownTowerDamagePercent(ROLLING_CTDP)
        .build();
  }

  /**
   * Creates a rolling piercing projectile traveling in the given direction. Simulates the
   * LogProjectileRolling sub-projectile after stage 1 impact.
   */
  private Projectile createRollingProjectile(
      Team team, float startX, float startY, float dirX, float dirY) {
    float targetX = startX + dirX * ROLLING_RANGE;
    float targetY = startY + dirY * ROLLING_RANGE;

    Projectile proj =
        new Projectile(
            team,
            startX,
            startY,
            targetX,
            targetY,
            ROLLING_BASE_DAMAGE,
            ROLLING_RADIUS,
            ROLLING_SPEED,
            Collections.emptyList(),
            ROLLING_CTDP);

    proj.configurePiercing(dirX, dirY, ROLLING_RANGE, true, false);
    proj.setPushback(ROLLING_PUSHBACK);
    proj.setPushbackAll(true);
    proj.setMinDistance(ROLLING_MIN_DISTANCE);

    return proj;
  }

  private Troop createGroundTroop(Team team, float x, float y) {
    return createTroop(team, x, y, MovementType.GROUND);
  }

  private Troop createTroop(Team team, float x, float y, MovementType movementType) {
    return Troop.builder()
        .name("TestTroop")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, movementType))
        .combat(
            Combat.builder().damage(50).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }

  /** Skip deploy time so troops are ready for combat. */
  private void skipDeployTime(Troop... troops) {
    for (Troop t : troops) {
      t.setDeployTimer(0);
    }
  }
}
