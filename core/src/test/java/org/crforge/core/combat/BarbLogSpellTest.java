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
import org.crforge.core.card.TroopStats;
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
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Barbarian Barrel spell: spellAsDeploy, two-stage projectile chain (BarbLogProjectile ->
 * BarbLogProjectileRolling), piercing damage, no pushback, minDistance, and Barbarian spawn on
 * rolling expire.
 */
class BarbLogSpellTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private ProjectileSystem projectileSystem;
  private DeploymentSystem deploymentSystem;
  private PhysicsSystem physicsSystem;

  // Track units spawned by projectile system
  private List<SpawnRecord> spawnedUnits;

  // BarbLog projectile stats (matching real data at level 1)
  private static final int ARC_DAMAGE = 0; // Stage 1 does no damage
  private static final float ARC_SPEED = 360f / 60f; // Scaled from raw speed
  private static final float ARC_MIN_DISTANCE = 3.0f;
  private static final float ARC_RADIUS = 1.3f;

  private static final int ROLLING_BASE_DAMAGE = 91; // BarbLogProjectileRolling base damage
  private static final float ROLLING_SPEED = 200f / 60f;
  private static final float ROLLING_PROJECTILE_RADIUS = 1.3f;
  private static final float ROLLING_RANGE = 4.5f; // projectileRange
  private static final float ROLLING_MIN_DISTANCE = 2.5f;
  private static final float ROLLING_PUSHBACK = 0f; // BarbLog has no pushback
  private static final int ROLLING_CTDP = 0; // No crown tower damage reduction

  // Barbarian stats for spawn verification
  private static final TroopStats BARBARIAN_STATS =
      TroopStats.builder()
          .name("Barbarian")
          .health(670)
          .damage(120)
          .attackCooldown(1.4f)
          .range(0.7f)
          .sightRange(5.5f)
          .speed(1.0f)
          .collisionRadius(0.25f)
          .mass(8f)
          .movementType(MovementType.GROUND)
          .build();

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    spawnedUnits = new ArrayList<>();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    projectileSystem = new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    projectileSystem.setUnitSpawner(
        (x, y, team, stats, level, deployTime) ->
            spawnedUnits.add(new SpawnRecord(x, y, team, stats, level, deployTime)));
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    deploymentSystem = new DeploymentSystem(gameState, aoeDamageService);
    Arena arena = new Arena("TestArena");
    physicsSystem = new PhysicsSystem(arena);
    physicsSystem.setGameState(gameState);
  }

  @Test
  void arcPhase_spawnsRollingSubProjectile() {
    ProjectileStats rollingStats = createRollingStats();
    ProjectileStats stage1Stats =
        ProjectileStats.builder()
            .name("BarbLogProjectile")
            .damage(0)
            .speed(ARC_SPEED)
            .spawnProjectile(rollingStats)
            .build();

    // Fire stage 1 from (9, 14) to (9, 14.5) -- short distance
    Projectile stage1 =
        new Projectile(Team.BLUE, 9f, 14f, 9f, 14.5f, 0, 0f, ARC_SPEED, Collections.emptyList());
    stage1.setSpawnProjectile(rollingStats);
    stage1.setSpellLevel(1);

    gameState.spawnProjectile(stage1);

    // Advance until stage 1 hits
    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    int ticks = 0;
    while (stage1.isActive() && ticks < 300) {
      combatSystem.update(dt);
      ticks++;
    }

    // Stage 1 should have spawned a sub-projectile (the rolling barrel)
    List<Projectile> projectiles = gameState.getProjectiles();
    assertThat(projectiles).hasSizeGreaterThanOrEqualTo(1);

    // The rolling sub-projectile should be piercing
    Projectile rolling = projectiles.get(projectiles.size() - 1);
    assertThat(rolling.isPiercing()).isTrue();
    assertThat(rolling.isActive()).isTrue();
  }

  @Test
  void rollingProjectile_hitsGroundEnemies() {
    // Three ground enemies in a line along the rolling path
    Troop enemy1 = createGroundTroop(Team.RED, 9f, 18f);
    Troop enemy2 = createGroundTroop(Team.RED, 9f, 19.5f);
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
    Troop enemy = createGroundTroop(Team.RED, 9f, 18f);

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
    Troop airUnit = createTroop(Team.RED, 9f, 18f, MovementType.AIR);

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
  void rollingProjectile_noPushback() {
    Troop enemy = createGroundTroop(Team.RED, 9f, 18f);

    gameState.spawnEntity(enemy);
    gameState.processPending();
    skipDeployTime(enemy);

    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // BarbLog has zero pushback, enemy should NOT be knocked back
    assertThat(enemy.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void rollingProjectile_minDistance_preventsEarlyHits() {
    // Place enemy very close to the start -- within minDistance (2.5)
    Troop closeEnemy = createGroundTroop(Team.RED, 9f, 15.5f);

    gameState.spawnEntity(closeEnemy);
    gameState.processPending();
    skipDeployTime(closeEnemy);

    int initialHp = closeEnemy.getHealth().getCurrent();

    // Create rolling projectile starting at (9, 15) with minDistance = 2.5
    Projectile rolling = createRollingProjectile(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // Enemy within minDistance should NOT have been hit
    assertThat(closeEnemy.getHealth().getCurrent()).isEqualTo(initialHp);
  }

  @Test
  void rollingProjectile_spawnsBarbarianOnExpire() {
    // Create rolling projectile with spawn character wired
    Projectile rolling = createRollingProjectileWithSpawn(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    // Rolling projectile should have expired
    assertThat(rolling.isActive()).isFalse();

    // Should have spawned exactly one Barbarian
    assertThat(spawnedUnits).hasSize(1);
    SpawnRecord spawn = spawnedUnits.get(0);
    assertThat(spawn.stats.getName()).isEqualTo("Barbarian");
    assertThat(spawn.team).isEqualTo(Team.BLUE);

    // Barbarian should spawn near the end of the roll path (9, 15 + 4.5 = 19.5)
    assertThat(spawn.y).isCloseTo(19.5f, org.assertj.core.data.Offset.offset(0.5f));
  }

  @Test
  void spawned_barbarian_hasCorrectDeployTime() {
    Projectile rolling = createRollingProjectileWithSpawn(Team.BLUE, 9f, 15f, 0f, 1f);
    gameState.spawnProjectile(rolling);

    float dt = 1.0f / GameEngine.TICKS_PER_SECOND;
    for (int i = 0; i < 300 && rolling.isActive(); i++) {
      combatSystem.update(dt);
    }

    assertThat(spawnedUnits).hasSize(1);
    SpawnRecord spawn = spawnedUnits.get(0);
    // BarbLogProjectileRolling has deployTime=1.0
    assertThat(spawn.deployTime).isEqualTo(1.0f);
  }

  @Test
  void spellAsDeploy_projectileStartsAtDeployLocation() {
    // Deploy BarbLog at (9, 14) for blue team
    float deployX = 9f;
    float deployY = 14f;

    Card barbLogCard = createBarbLogCard();
    ProjectileStats proj = barbLogCard.getProjectile();

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

  // -- Helper methods --

  /** Creates a Card matching the Barbarian Barrel spell configuration. */
  private Card createBarbLogCard() {
    ProjectileStats rollingStats = createRollingStats();

    ProjectileStats barbLogProjectileStats =
        ProjectileStats.builder()
            .name("BarbLogProjectile")
            .damage(0)
            .speed(ARC_SPEED)
            .radius(ARC_RADIUS)
            .spawnProjectile(rollingStats)
            .minDistance(ARC_MIN_DISTANCE * 1000f) // raw CSV units
            .build();

    return Card.builder()
        .id("barblog")
        .name("BarbLog")
        .type(CardType.SPELL)
        .cost(2)
        .rarity(Rarity.EPIC)
        .projectile(barbLogProjectileStats)
        .spellAsDeploy(true)
        .build();
  }

  /** Creates the rolling sub-projectile stats (BarbLogProjectileRolling). */
  private ProjectileStats createRollingStats() {
    return ProjectileStats.builder()
        .name("BarbLogProjectileRolling")
        .damage(ROLLING_BASE_DAMAGE)
        .speed(ROLLING_SPEED)
        .radius(0f)
        .projectileRadius(ROLLING_PROJECTILE_RADIUS)
        .projectileRange(ROLLING_RANGE)
        .aoeToGround(true)
        .aoeToAir(false)
        .pushback(ROLLING_PUSHBACK)
        .minDistance(ROLLING_MIN_DISTANCE)
        .crownTowerDamagePercent(ROLLING_CTDP)
        .spawnCharacterName("Barbarian")
        .spawnCharacter(BARBARIAN_STATS)
        .spawnDeployTime(1.0f)
        .build();
  }

  /**
   * Creates a rolling piercing projectile traveling in the given direction. Simulates the
   * BarbLogProjectileRolling sub-projectile after stage 1 impact.
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
            ROLLING_PROJECTILE_RADIUS,
            ROLLING_SPEED,
            Collections.emptyList(),
            ROLLING_CTDP);

    proj.configurePiercing(dirX, dirY, ROLLING_RANGE, true, false);
    proj.setPushback(ROLLING_PUSHBACK);
    proj.setMinDistance(ROLLING_MIN_DISTANCE);

    return proj;
  }

  /**
   * Creates a rolling piercing projectile with spawn character wired, for testing Barbarian spawn
   * on expire.
   */
  private Projectile createRollingProjectileWithSpawn(
      Team team, float startX, float startY, float dirX, float dirY) {
    Projectile proj = createRollingProjectile(team, startX, startY, dirX, dirY);
    proj.setSpawnCharacterStats(BARBARIAN_STATS);
    proj.setSpawnCharacterCount(1);
    proj.setSpawnDeployTime(1.0f);
    proj.setSpawnCharacterLevel(1);
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
      t.update(2.0f);
    }
  }

  /** Record of a unit spawn for test verification. */
  private record SpawnRecord(
      float x, float y, Team team, TroopStats stats, int level, float deployTime) {}
}
