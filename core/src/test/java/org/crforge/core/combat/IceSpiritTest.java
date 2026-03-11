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
 * Tests for Ice Spirit: 1-elixir kamikaze unit that fires a homing projectile,
 * dealing AOE damage and applying Freeze (1.1s) to all enemies in radius 1.5.
 */
class IceSpiritTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  private static final int ICE_SPIRIT_DAMAGE = 43;
  private static final float PROJECTILE_SPEED = 400f / 60f; // ~6.67 t/s
  private static final float AOE_RADIUS = 1.5f;
  private static final float FREEZE_DURATION = 1.1f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void attack_dealsAoeDamageAndFreezesAndDies() {
    Troop spirit = createIceSpirit(Team.BLUE, 5, 5);
    Troop enemy = createEnemy(Team.RED, 7, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Finish deploy
    spirit.update(2.0f);
    enemy.update(2.0f);

    // Set target and run combat
    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // Spirit should be dead (kamikaze)
    assertThat(spirit.getHealth().isDead())
        .as("Ice Spirit should die after attack (kamikaze)")
        .isTrue();

    // Enemy should take AOE damage
    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take %d damage", ICE_SPIRIT_DAMAGE)
        .isEqualTo(500 - ICE_SPIRIT_DAMAGE);

    // Enemy should have FREEZE effect
    assertThat(enemy.getAppliedEffects())
        .as("Enemy should be frozen")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void attack_freezesMultipleEnemiesInAoe() {
    Troop spirit = createIceSpirit(Team.BLUE, 5, 5);
    Troop enemy1 = createEnemy(Team.RED, 7, 5, 500);
    Troop enemy2 = createEnemy(Team.RED, 7, 5.5f, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();

    spirit.update(2.0f);
    enemy1.update(2.0f);
    enemy2.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy1);
    runCombatUpdates(1.0f);

    // Both enemies should take damage
    assertThat(enemy1.getHealth().getCurrent())
        .as("Primary target should take damage")
        .isEqualTo(500 - ICE_SPIRIT_DAMAGE);
    assertThat(enemy2.getHealth().getCurrent())
        .as("Secondary target in AOE should take damage")
        .isEqualTo(500 - ICE_SPIRIT_DAMAGE);

    // Both should be frozen
    assertThat(enemy1.getAppliedEffects())
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(enemy2.getAppliedEffects())
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void attack_doesNotAffectAllies() {
    Troop spirit = createIceSpirit(Team.BLUE, 5, 5);
    Troop enemy = createEnemy(Team.RED, 7, 5, 500);
    Troop friendly = createEnemy(Team.BLUE, 7.5f, 5, 500);

    gameState.spawnEntity(spirit);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    spirit.update(2.0f);
    enemy.update(2.0f);
    friendly.update(2.0f);

    spirit.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // Friendly should be unaffected
    assertThat(friendly.getHealth().getCurrent())
        .as("Friendly unit near target should not take damage")
        .isEqualTo(500);
    assertThat(friendly.getAppliedEffects())
        .as("Friendly unit should not have any effects")
        .isEmpty();
  }

  private Troop createIceSpirit(Team team, float x, float y) {
    ProjectileStats projStats = ProjectileStats.builder()
        .name("IceSpiritsProjectile")
        .damage(ICE_SPIRIT_DAMAGE)
        .speed(PROJECTILE_SPEED)
        .radius(AOE_RADIUS)
        .hitEffects(List.of(EffectStats.builder()
            .type(StatusEffectType.FREEZE)
            .duration(FREEZE_DURATION)
            .buffName("Freeze")
            .applyAfterDamage(true)
            .build()))
        .build();

    Combat combat = Combat.builder()
        .damage(ICE_SPIRIT_DAMAGE)
        .range(2.5f)
        .sightRange(5.5f)
        .attackCooldown(0.3f)
        .loadTime(0.1f)
        .kamikaze(true)
        .targetType(TargetType.ALL)
        .projectileStats(projStats)
        .build();

    return Troop.builder()
        .name("IceSpirit")
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
