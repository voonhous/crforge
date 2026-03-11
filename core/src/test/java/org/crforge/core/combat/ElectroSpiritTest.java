package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
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
 * Tests for Electro Spirit: 1-elixir kamikaze unit that fires a fast homing projectile,
 * dealing single-target damage with STUN (0.5s) and chaining to up to 9 total targets
 * within 4.0 radius.
 */
class ElectroSpiritTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  private static final int ELECTRO_SPIRIT_DAMAGE = 39;
  private static final float PROJECTILE_SPEED = 1000f / 60f; // ~16.67 t/s
  private static final float STUN_DURATION = 0.5f;
  private static final float CHAIN_RADIUS = 4.0f;
  private static final int CHAIN_HIT_COUNT = 9; // Total including primary

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void attack_damagesAndStunsTarget() {
    Troop spirit = createElectroSpirit(Team.BLUE, 5, 5);
    Troop enemy = createEnemy(Team.RED, 7, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    assertThat(spirit.getHealth().isDead())
        .as("Electro Spirit should die after attack (kamikaze)")
        .isTrue();

    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take %d damage", ELECTRO_SPIRIT_DAMAGE)
        .isEqualTo(500 - ELECTRO_SPIRIT_DAMAGE);

    assertThat(enemy.getAppliedEffects())
        .as("Enemy should be stunned")
        .anyMatch(e -> e.getType() == StatusEffectType.STUN);
  }

  @Test
  void attack_chainsToNearbyEnemies() {
    Troop spirit = createElectroSpirit(Team.BLUE, 5, 5);
    Troop enemy1 = createEnemy(Team.RED, 7, 5, 500);
    Troop enemy2 = createEnemy(Team.RED, 8, 5, 500);
    Troop enemy3 = createEnemy(Team.RED, 9, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.spawnEntity(enemy3);
    gameState.processPending();

    spirit.update(2.0f);
    enemy1.update(2.0f);
    enemy2.update(2.0f);
    enemy3.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy1);
    // Run enough ticks for primary + chain projectiles to hit
    runCombatUpdates(2.0f);

    // All three enemies should take damage
    assertThat(enemy1.getHealth().getCurrent())
        .as("Primary target should take damage")
        .isEqualTo(500 - ELECTRO_SPIRIT_DAMAGE);
    assertThat(enemy2.getHealth().getCurrent())
        .as("Chain target 1 should take damage")
        .isEqualTo(500 - ELECTRO_SPIRIT_DAMAGE);
    assertThat(enemy3.getHealth().getCurrent())
        .as("Chain target 2 should take damage")
        .isEqualTo(500 - ELECTRO_SPIRIT_DAMAGE);

    // Chain targets should also be stunned
    assertThat(enemy2.getAppliedEffects())
        .anyMatch(e -> e.getType() == StatusEffectType.STUN);
    assertThat(enemy3.getAppliedEffects())
        .anyMatch(e -> e.getType() == StatusEffectType.STUN);
  }

  @Test
  void attack_chainRespectsMaxCount() {
    Troop spirit = createElectroSpirit(Team.BLUE, 5, 5);
    // Place 11 enemies within chain radius -- more than the 9 max chain hits
    Troop[] enemies = new Troop[11];
    for (int i = 0; i < 11; i++) {
      enemies[i] = createEnemy(Team.RED, 7 + i * 0.3f, 5, 500);
      gameState.spawnEntity(enemies[i]);
    }

    gameState.spawnEntity(spirit);
    gameState.processPending();

    spirit.update(2.0f);
    for (Troop enemy : enemies) {
      enemy.update(2.0f);
    }

    spirit.getCombat().setCurrentTarget(enemies[0]);
    // Run until primary + all chain projectiles hit
    runCombatUpdates(2.0f);

    // Count how many enemies took damage
    long damagedCount = 0;
    for (Troop enemy : enemies) {
      if (enemy.getHealth().getCurrent() < 500) {
        damagedCount++;
      }
    }

    // Total hits should be exactly 9 (1 primary + 8 chains)
    assertThat(damagedCount)
        .as("Chain should hit exactly %d targets (chainedHitCount)", CHAIN_HIT_COUNT)
        .isEqualTo(CHAIN_HIT_COUNT);
  }

  private Troop createElectroSpirit(Team team, float x, float y) {
    ProjectileStats projStats = ProjectileStats.builder()
        .name("ElectroSpiritProjectile")
        .damage(ELECTRO_SPIRIT_DAMAGE)
        .speed(PROJECTILE_SPEED)
        .radius(0) // No AOE, uses chain lightning instead
        .hitEffects(List.of(EffectStats.builder()
            .type(StatusEffectType.STUN)
            .duration(STUN_DURATION)
            .buffName("ZapFreeze")
            .applyAfterDamage(true)
            .build()))
        .chainedHitRadius(CHAIN_RADIUS)
        .chainedHitCount(CHAIN_HIT_COUNT)
        .build();

    Combat combat = Combat.builder()
        .damage(ELECTRO_SPIRIT_DAMAGE)
        .range(2.5f)
        .sightRange(5.5f)
        .attackCooldown(0.3f)
        .loadTime(0.1f)
        .kamikaze(true)
        .targetType(TargetType.ALL)
        .projectileStats(projStats)
        .build();

    return Troop.builder()
        .name("ElectroSpirit")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(90))
        .movement(new Movement(2.0f, 1.0f, 0.4f, 0.4f, MovementType.GROUND))
        .deployTime(1.0f)
        .combat(combat)
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
      for (Entity e : gameState.getAliveEntities()) {
        e.update(dt);
      }
      combatSystem.update(dt);
    }
  }
}
