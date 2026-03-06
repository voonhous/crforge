package org.crforge.core.entity.effect;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AreaEffectSystemTest {

  private GameState gameState;
  private AreaEffectSystem system;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    system = new AreaEffectSystem(gameState);
  }

  @Test
  void oneShot_shouldDamageEnemiesInRadius() {
    // One-shot area effect (like Zap)
    AreaEffectStats stats = AreaEffectStats.builder()
        .name("Zap")
        .radius(2.5f)
        .lifeDuration(0.001f)
        .hitsGround(true)
        .hitsAir(true)
        .damage(75)
        .build();

    AreaEffect effect = AreaEffect.builder()
        .name("Zap")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(75)
        .remainingLifetime(0.001f)
        .build();

    // Enemy in range
    Troop nearEnemy = Troop.builder()
        .name("NearEnemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    // Enemy out of range
    Troop farEnemy = Troop.builder()
        .name("FarEnemy")
        .team(Team.RED)
        .position(new Position(20, 20))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    // Friendly unit (should not be hit)
    Troop friendly = Troop.builder()
        .name("Friendly")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(nearEnemy);
    gameState.spawnEntity(farEnemy);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    // Make targets targetable
    nearEnemy.update(1.0f);
    farEnemy.update(1.0f);
    friendly.update(1.0f);

    system.update(1.0f / 30);

    assertThat(nearEnemy.getHealth().getCurrent()).isEqualTo(425); // 500 - 75
    assertThat(farEnemy.getHealth().getCurrent()).isEqualTo(500); // Out of range
    assertThat(friendly.getHealth().getCurrent()).isEqualTo(500); // Same team
  }

  @Test
  void oneShot_shouldApplyBuffToEnemies() {
    // Zap-like effect with stun
    AreaEffectStats stats = AreaEffectStats.builder()
        .name("Zap")
        .radius(2.5f)
        .lifeDuration(0.001f)
        .hitsGround(true)
        .hitsAir(true)
        .damage(75)
        .buff("ZapFreeze")
        .buffDuration(0.5f)
        .build();

    AreaEffect effect = AreaEffect.builder()
        .name("Zap")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(75)
        .remainingLifetime(0.001f)
        .build();

    Troop enemy = Troop.builder()
        .name("Enemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(1.0f);

    system.update(1.0f / 30);

    // Should have STUN effect applied (ZapFreeze -> STUN)
    assertThat(enemy.getAppliedEffects()).hasSize(1);
    assertThat(enemy.getAppliedEffects().get(0).getType())
        .isEqualTo(org.crforge.core.effect.StatusEffectType.STUN);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(425);
  }

  @Test
  void ticking_shouldDamageRepeatedly() {
    // Poison-like effect: hitSpeed=1.0, lifeDuration=3.0 -> 3 ticks
    AreaEffectStats stats = AreaEffectStats.builder()
        .name("Poison")
        .radius(3.5f)
        .lifeDuration(3.0f)
        .hitsGround(true)
        .hitsAir(true)
        .hitSpeed(1.0f)
        .damage(50)
        .build();

    AreaEffect effect = AreaEffect.builder()
        .name("Poison")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(50)
        .remainingLifetime(3.0f)
        .build();

    Troop enemy = Troop.builder()
        .name("Enemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(1.0f);

    // After 1.0s -> 1 tick of damage
    system.update(1.0f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(450); // 500 - 50

    // After another 1.0s -> 2nd tick
    system.update(1.0f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(400); // 450 - 50

    // After another 1.0s -> 3rd tick
    system.update(1.0f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(350); // 400 - 50
  }

  @Test
  void hitsGround_shouldFilterAirUnits() {
    // Earthquake: hitsGround=true, hitsAir=false
    AreaEffectStats stats = AreaEffectStats.builder()
        .name("Earthquake")
        .radius(3.5f)
        .lifeDuration(0.001f)
        .hitsGround(true)
        .hitsAir(false)
        .damage(100)
        .build();

    AreaEffect effect = AreaEffect.builder()
        .name("Earthquake")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(100)
        .remainingLifetime(0.001f)
        .build();

    Troop groundEnemy = Troop.builder()
        .name("GroundEnemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.GROUND))
        .deployTime(0f)
        .build();

    Troop airEnemy = Troop.builder()
        .name("AirEnemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.AIR))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(groundEnemy);
    gameState.spawnEntity(airEnemy);
    gameState.processPending();
    groundEnemy.update(1.0f);
    airEnemy.update(1.0f);

    system.update(1.0f / 30);

    assertThat(groundEnemy.getHealth().getCurrent()).isEqualTo(400); // Hit
    assertThat(airEnemy.getHealth().getCurrent()).isEqualTo(500); // Not hit
  }

  @Test
  void effect_shouldExpireAfterLifetime() {
    AreaEffectStats stats = AreaEffectStats.builder()
        .name("Poison")
        .radius(3.5f)
        .lifeDuration(1.0f)
        .hitsGround(true)
        .hitsAir(true)
        .hitSpeed(0.5f)
        .damage(50)
        .build();

    AreaEffect effect = AreaEffect.builder()
        .name("Poison")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(50)
        .remainingLifetime(1.0f)
        .build();

    Troop enemy = Troop.builder()
        .name("Enemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(1.0f);

    // Tick 0.5s -> 1 damage tick
    system.update(0.5f);
    effect.update(0.5f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(450);

    // Tick another 0.5s -> 2nd damage tick
    system.update(0.5f);
    effect.update(0.5f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(400);

    // Effect should now be dead (lifetime expired)
    assertThat(effect.isAlive()).isFalse();

    // No more damage after expiration
    system.update(0.5f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(400);
  }

  @Test
  void freeze_shouldApplyFreezeBuffToEnemies() {
    // Freeze spell: damage + FREEZE buff
    AreaEffectStats stats = AreaEffectStats.builder()
        .name("Freeze")
        .radius(3.0f)
        .lifeDuration(0.001f)
        .hitsGround(true)
        .hitsAir(true)
        .damage(58)
        .buff("Freeze")
        .buffDuration(4.0f)
        .crownTowerDamagePercent(-70)
        .build();

    AreaEffect effect = AreaEffect.builder()
        .name("Freeze")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(58)
        .remainingLifetime(0.001f)
        .build();

    Troop enemy = Troop.builder()
        .name("Enemy")
        .team(Team.RED)
        .position(new Position(11, 10))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(1.0f);

    system.update(1.0f / 30);

    assertThat(enemy.getHealth().getCurrent()).isEqualTo(442); // 500 - 58
    assertThat(enemy.getAppliedEffects()).hasSize(1);
    assertThat(enemy.getAppliedEffects().get(0).getType())
        .isEqualTo(org.crforge.core.effect.StatusEffectType.FREEZE);
    assertThat(enemy.getAppliedEffects().get(0).getRemainingDuration()).isCloseTo(4.0f,
        org.assertj.core.api.Assertions.within(0.01f));
  }
}
