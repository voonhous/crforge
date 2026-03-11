package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
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
 * Tests for Fire Spirit: 1-elixir kamikaze unit that fires a homing projectile,
 * dealing AOE damage (81) to all enemies within radius 2.3. No status effects.
 */
class FireSpiritTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  private static final int FIRE_SPIRIT_DAMAGE = 81;
  private static final float PROJECTILE_SPEED = 400f / 60f; // ~6.67 t/s
  private static final float AOE_RADIUS = 2.3f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void attack_dealsAoeDamageAndDies() {
    Troop spirit = createFireSpirit(Team.BLUE, 5, 5);
    Troop enemy = createEnemy(Team.RED, 7, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Finish deploy
    spirit.update(2.0f);
    enemy.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    assertThat(spirit.getHealth().isDead())
        .as("Fire Spirit should die after attack (kamikaze)")
        .isTrue();

    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take %d damage", FIRE_SPIRIT_DAMAGE)
        .isEqualTo(500 - FIRE_SPIRIT_DAMAGE);
  }

  @Test
  void attack_damagesMultipleEnemiesInAoe() {
    Troop spirit = createFireSpirit(Team.BLUE, 5, 5);
    Troop enemy1 = createEnemy(Team.RED, 7, 5, 500);
    Troop enemy2 = createEnemy(Team.RED, 7, 6, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();

    spirit.update(2.0f);
    enemy1.update(2.0f);
    enemy2.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy1);
    runCombatUpdates(1.0f);

    // Both within 2.3 AOE radius should take damage
    assertThat(enemy1.getHealth().getCurrent())
        .as("Primary target should take damage")
        .isEqualTo(500 - FIRE_SPIRIT_DAMAGE);
    assertThat(enemy2.getHealth().getCurrent())
        .as("Secondary target in AOE should take damage")
        .isEqualTo(500 - FIRE_SPIRIT_DAMAGE);
  }

  @Test
  void attack_appliesNoStatusEffects() {
    Troop spirit = createFireSpirit(Team.BLUE, 5, 5);
    Troop enemy = createEnemy(Team.RED, 7, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    assertThat(enemy.getAppliedEffects())
        .as("Fire Spirit should not apply any status effects")
        .isEmpty();
  }

  private Troop createFireSpirit(Team team, float x, float y) {
    ProjectileStats projStats = ProjectileStats.builder()
        .name("FireSpiritsProjectile")
        .damage(FIRE_SPIRIT_DAMAGE)
        .speed(PROJECTILE_SPEED)
        .radius(AOE_RADIUS)
        .build();

    Combat combat = Combat.builder()
        .damage(FIRE_SPIRIT_DAMAGE)
        .range(2.5f)
        .sightRange(5.5f)
        .attackCooldown(0.3f)
        .loadTime(0.1f)
        .kamikaze(true)
        .targetType(TargetType.ALL)
        .projectileStats(projStats)
        .build();

    return Troop.builder()
        .name("FireSpirit")
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
