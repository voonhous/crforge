package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Heal Spirit: 1-elixir kamikaze unit that fires a homing projectile, dealing AOE damage
 * to enemies and healing friendly units near the impact point via a spawned area effect with
 * HealSpiritBuff.
 */
class HealSpiritTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private AreaEffectSystem areaEffectSystem;

  private static final int HEAL_SPIRIT_DAMAGE = 43;
  private static final float PROJECTILE_SPEED = 400f / 60f; // ~6.67 t/s
  private static final float AOE_RADIUS = 1.5f;
  private static final int HEAL_AMOUNT = 157;
  private static final float HEAL_AREA_RADIUS = 2.5f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    areaEffectSystem = new AreaEffectSystem(gameState);

    // Register HealSpiritBuff so AreaEffectSystem can resolve healing
    BuffRegistry.clear();
    BuffRegistry.register(
        "HealSpiritBuff",
        BuffDefinition.builder()
            .name("HealSpiritBuff")
            .healPerSecond(157)
            .hitFrequency(0.25f)
            .enableStacking(true)
            .build());
  }

  @Test
  void attack_dealsAoeDamageToEnemiesAndDies() {
    Troop spirit = createHealSpirit(Team.BLUE, 5, 5);
    Troop enemy = createTroop(Team.RED, 7, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    assertThat(spirit.getHealth().isDead())
        .as("Heal Spirit should die after attack (kamikaze)")
        .isTrue();

    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take %d damage", HEAL_SPIRIT_DAMAGE)
        .isEqualTo(500 - HEAL_SPIRIT_DAMAGE);
  }

  @Test
  void attack_healsFriendlyUnitsOnImpact() {
    Troop spirit = createHealSpirit(Team.BLUE, 5, 5);
    Troop enemy = createTroop(Team.RED, 7, 5, 500);
    Troop friendly = createTroop(Team.BLUE, 7.5f, 5, 500);

    // Damage friendly so we can observe healing
    friendly.getHealth().takeDamage(200);
    assertThat(friendly.getHealth().getCurrent()).isEqualTo(300);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);
    friendly.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // Friendly should be healed by healAmount
    assertThat(friendly.getHealth().getCurrent())
        .as("Friendly unit should be healed by %d", HEAL_AMOUNT)
        .isEqualTo(300 + HEAL_AMOUNT);
  }

  @Test
  void attack_healsMultipleFriendliesInRadius() {
    Troop spirit = createHealSpirit(Team.BLUE, 5, 5);
    Troop enemy = createTroop(Team.RED, 7, 5, 500);
    Troop friendly1 = createTroop(Team.BLUE, 7.5f, 5, 500);
    Troop friendly2 = createTroop(Team.BLUE, 7, 5.5f, 500);

    friendly1.getHealth().takeDamage(200);
    friendly2.getHealth().takeDamage(200);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendly1);
    gameState.spawnEntity(friendly2);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);
    friendly1.update(2.0f);
    friendly2.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    assertThat(friendly1.getHealth().getCurrent())
        .as("First friendly should be healed")
        .isEqualTo(300 + HEAL_AMOUNT);
    assertThat(friendly2.getHealth().getCurrent())
        .as("Second friendly should be healed")
        .isEqualTo(300 + HEAL_AMOUNT);
  }

  @Test
  void attack_doesNotHealEnemies() {
    Troop spirit = createHealSpirit(Team.BLUE, 5, 5);
    Troop enemy = createTroop(Team.RED, 7, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // Enemy should only have lost HP, not gained any
    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should only take damage, not be healed")
        .isEqualTo(500 - HEAL_SPIRIT_DAMAGE);
  }

  @Test
  void attack_doesNotOverheal() {
    Troop spirit = createHealSpirit(Team.BLUE, 5, 5);
    Troop enemy = createTroop(Team.RED, 7, 5, 500);
    Troop friendly = createTroop(Team.BLUE, 7.5f, 5, 100);

    // Only 10 damage -- heal of 157 should cap at max HP (100)
    friendly.getHealth().takeDamage(10);
    assertThat(friendly.getHealth().getCurrent()).isEqualTo(90);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);
    friendly.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    assertThat(friendly.getHealth().getCurrent()).as("HP should not exceed max").isEqualTo(100);
  }

  private Troop createHealSpirit(Team team, float x, float y) {
    AreaEffectStats healAreaEffect =
        AreaEffectStats.builder()
            .name("HealSpirit")
            .radius(HEAL_AREA_RADIUS)
            .lifeDuration(1.0f)
            .hitsGround(true)
            .hitsAir(true)
            .buff("HealSpiritBuff")
            .buffDuration(1.0f)
            .build();

    ProjectileStats projStats =
        ProjectileStats.builder()
            .name("HealSpiritProjectile")
            .damage(HEAL_SPIRIT_DAMAGE)
            .speed(PROJECTILE_SPEED)
            .radius(AOE_RADIUS)
            .spawnAreaEffect(healAreaEffect)
            .build();

    Combat combat =
        Combat.builder()
            .damage(HEAL_SPIRIT_DAMAGE)
            .range(2.5f)
            .sightRange(5.5f)
            .attackCooldown(0.3f)
            .loadTime(0.1f)
            .kamikaze(true)
            .targetType(TargetType.ALL)
            .projectileStats(projStats)
            .build();

    return Troop.builder()
        .name("HealSpirit")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(90))
        .movement(new Movement(2.0f, 1.0f, 0.4f, 0.4f, MovementType.GROUND))
        .deployTime(1.0f)
        .combat(combat)
        .build();
  }

  private Troop createTroop(Team team, float x, float y, int hp) {
    return Troop.builder()
        .name("Troop")
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
      for (Entity e : gameState.getAliveEntities()) {
        e.update(dt);
      }
      combatSystem.update(dt);
      // Process pending entities (area effects spawned by projectile impact)
      gameState.processPending();
      // Run area effect system to apply healing from spawned area effects
      areaEffectSystem.update(dt);
    }
  }
}
