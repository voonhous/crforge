package org.crforge.core.entity.effect;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Tower;
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
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Zap")
            .radius(2.5f)
            .lifeDuration(0.001f)
            .hitsGround(true)
            .hitsAir(true)
            .damage(75)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Zap")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(75)
            .remainingLifetime(0.001f)
            .build();

    // Enemy in range
    Troop nearEnemy =
        Troop.builder()
            .name("NearEnemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    // Enemy out of range
    Troop farEnemy =
        Troop.builder()
            .name("FarEnemy")
            .team(Team.RED)
            .position(new Position(20, 20))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    // Friendly unit (should not be hit)
    Troop friendly =
        Troop.builder()
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
    nearEnemy.setDeployTimer(0);
    farEnemy.setDeployTimer(0);
    friendly.setDeployTimer(0);

    system.update(1.0f / 30);

    assertThat(nearEnemy.getHealth().getCurrent()).isEqualTo(425); // 500 - 75
    assertThat(farEnemy.getHealth().getCurrent()).isEqualTo(500); // Out of range
    assertThat(friendly.getHealth().getCurrent()).isEqualTo(500); // Same team
  }

  @Test
  void oneShot_shouldApplyBuffToEnemies() {
    // Zap-like effect with stun
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Zap")
            .radius(2.5f)
            .lifeDuration(0.001f)
            .hitsGround(true)
            .hitsAir(true)
            .damage(75)
            .buffApplication(BuffApplication.of("ZapFreeze", 0.5f))
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Zap")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(75)
            .remainingLifetime(0.001f)
            .build();

    Troop enemy =
        Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

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
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Poison")
            .radius(3.5f)
            .lifeDuration(3.0f)
            .hitsGround(true)
            .hitsAir(true)
            .hitSpeed(1.0f)
            .damage(50)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Poison")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(50)
            .remainingLifetime(3.0f)
            .build();

    Troop enemy =
        Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

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
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Earthquake")
            .radius(3.5f)
            .lifeDuration(0.001f)
            .hitsGround(true)
            .hitsAir(false)
            .damage(100)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Earthquake")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(100)
            .remainingLifetime(0.001f)
            .build();

    Troop groundEnemy =
        Troop.builder()
            .name("GroundEnemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();

    Troop airEnemy =
        Troop.builder()
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
    groundEnemy.setDeployTimer(0);
    airEnemy.setDeployTimer(0);

    system.update(1.0f / 30);

    assertThat(groundEnemy.getHealth().getCurrent()).isEqualTo(400); // Hit
    assertThat(airEnemy.getHealth().getCurrent()).isEqualTo(500); // Not hit
  }

  @Test
  void effect_shouldExpireAfterLifetime() {
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Poison")
            .radius(3.5f)
            .lifeDuration(1.0f)
            .hitsGround(true)
            .hitsAir(true)
            .hitSpeed(0.5f)
            .damage(50)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Poison")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(50)
            .remainingLifetime(1.0f)
            .build();

    Troop enemy =
        Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

    // Tick 0.5s -> 1 damage tick
    system.update(0.5f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(450);

    // Tick another 0.5s -> 2nd damage tick
    system.update(0.5f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(400);

    // Effect should now be dead (lifetime expired)
    assertThat(effect.isAlive()).isFalse();

    // No more damage after expiration
    system.update(0.5f);
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(400);
  }

  @Test
  void oneShot_shouldSurviveAndGetProcessed() {
    // One-shot effects with very short lifeDuration (like Zap at 0.001s) must
    // survive and get processed by AreaEffectSystem. The one-shot guard inside
    // AreaEffectSystem ensures the effect is applied before being marked dead.
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Zap")
            .radius(2.5f)
            .lifeDuration(0.001f)
            .hitsGround(true)
            .hitsAir(true)
            .damage(75)
            .buffApplication(BuffApplication.of("ZapFreeze", 0.5f))
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Zap")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(75)
            .remainingLifetime(0.001f)
            .build();

    Troop enemy =
        Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

    float dt = 1.0f / 30;
    system.update(dt); // should process and apply damage + buff

    assertThat(enemy.getHealth().getCurrent()).isEqualTo(425); // 500 - 75
    assertThat(enemy.getAppliedEffects()).hasSize(1);
    assertThat(enemy.getAppliedEffects().get(0).getType())
        .isEqualTo(org.crforge.core.effect.StatusEffectType.STUN);

    // After processing, the one-shot effect should be dead
    assertThat(effect.isAlive()).isFalse();
  }

  @Test
  void freeze_shouldApplyFreezeBuffToEnemies() {
    // Freeze spell: damage + FREEZE buff
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Freeze")
            .radius(3.0f)
            .lifeDuration(0.001f)
            .hitsGround(true)
            .hitsAir(true)
            .damage(58)
            .buffApplication(BuffApplication.of("Freeze", 4.0f))
            .crownTowerDamagePercent(-70)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Freeze")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(58)
            .remainingLifetime(0.001f)
            .build();

    Troop enemy =
        Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.setDeployTimer(0);

    system.update(1.0f / 30);

    assertThat(enemy.getHealth().getCurrent()).isEqualTo(442); // 500 - 58
    assertThat(enemy.getAppliedEffects()).hasSize(1);
    assertThat(enemy.getAppliedEffects().get(0).getType())
        .isEqualTo(org.crforge.core.effect.StatusEffectType.FREEZE);
    assertThat(enemy.getAppliedEffects().get(0).getRemainingDuration())
        .isCloseTo(4.0f, org.assertj.core.api.Assertions.within(0.01f));
  }

  // --- hitBiggestTargets (Lightning) tests ---

  private AreaEffectStats lightningStats() {
    return AreaEffectStats.builder()
        .name("Lightning")
        .radius(3.5f)
        .lifeDuration(1.5f)
        .hitsGround(true)
        .hitsAir(true)
        .hitBiggestTargets(true)
        .hitSpeed(0.46f)
        .damage(413)
        .buffApplication(BuffApplication.of("ZapFreeze", 0.5f))
        .crownTowerDamagePercent(-73)
        .onlyEnemies(true)
        .build();
  }

  private AreaEffect createLightningEffect() {
    AreaEffectStats stats = lightningStats();
    return AreaEffect.builder()
        .name("Lightning")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(413)
        .remainingLifetime(1.5f)
        .build();
  }

  private Troop createEnemyTroop(String name, int hp, float x, float y) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.RED)
            .position(new Position(x, y))
            .health(new Health(hp))
            .deployTime(0f)
            .build();
    troop.setDeployTimer(0); // make targetable
    return troop;
  }

  @Test
  void hitBiggestTargets_shouldHitHighestHpFirst() {
    // 3 enemies with different HP. Lightning should strike highest HP first each tick.
    AreaEffect effect = createLightningEffect();
    Troop low = createEnemyTroop("Low", 200, 11, 10);
    Troop mid = createEnemyTroop("Mid", 500, 10, 11);
    Troop high = createEnemyTroop("High", 1000, 10, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(low);
    gameState.spawnEntity(mid);
    gameState.spawnEntity(high);
    gameState.processPending();

    // Tick 1: hits highest HP (1000)
    system.update(0.46f);
    assertThat(high.getHealth().getCurrent()).isEqualTo(1000 - 413);
    assertThat(mid.getHealth().getCurrent()).isEqualTo(500);
    assertThat(low.getHealth().getCurrent()).isEqualTo(200);

    // Tick 2: hits next highest (500)
    system.update(0.46f);
    assertThat(mid.getHealth().getCurrent()).isEqualTo(500 - 413);
    assertThat(low.getHealth().getCurrent()).isEqualTo(200);

    // Tick 3: hits last (200)
    system.update(0.46f);
    assertThat(low.getHealth().getCurrent()).isLessThan(200);
  }

  @Test
  void hitBiggestTargets_shouldNotRehitSameTarget() {
    // Only 1 enemy in range -- should be hit once, then no more hits
    AreaEffect effect = createLightningEffect();
    Troop solo = createEnemyTroop("Solo", 1000, 10, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(solo);
    gameState.processPending();

    // Tick 1: hit
    system.update(0.46f);
    assertThat(solo.getHealth().getCurrent()).isEqualTo(1000 - 413);

    // Tick 2: no new target, damage should not increase
    system.update(0.46f);
    assertThat(solo.getHealth().getCurrent()).isEqualTo(1000 - 413);

    // Tick 3: still no rehit
    system.update(0.46f);
    assertThat(solo.getHealth().getCurrent()).isEqualTo(1000 - 413);
  }

  @Test
  void hitBiggestTargets_fewerThanThreeTargets() {
    // 2 enemies -- both hit once, third tick does nothing
    AreaEffect effect = createLightningEffect();
    Troop a = createEnemyTroop("A", 800, 10, 10);
    Troop b = createEnemyTroop("B", 600, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(a);
    gameState.spawnEntity(b);
    gameState.processPending();

    system.update(0.46f);
    assertThat(a.getHealth().getCurrent()).isEqualTo(800 - 413); // highest hit first

    system.update(0.46f);
    assertThat(b.getHealth().getCurrent()).isEqualTo(600 - 413);

    // Tick 3: no targets left
    int aHpBefore = a.getHealth().getCurrent();
    int bHpBefore = b.getHealth().getCurrent();
    system.update(0.46f);
    assertThat(a.getHealth().getCurrent()).isEqualTo(aHpBefore);
    assertThat(b.getHealth().getCurrent()).isEqualTo(bHpBefore);
  }

  @Test
  void hitBiggestTargets_shouldApplyBuff() {
    AreaEffect effect = createLightningEffect();
    Troop enemy = createEnemyTroop("Target", 1000, 10, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    system.update(0.46f);

    assertThat(enemy.getAppliedEffects()).hasSize(1);
    assertThat(enemy.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.STUN);
  }

  @Test
  void hitBiggestTargets_shouldConsiderShieldForSorting() {
    // Enemy A: 200 HP + 500 shield = 700 effective. Enemy B: 600 HP, no shield.
    // A should be struck first since effective HP is higher.
    AreaEffect effect = createLightningEffect();

    Troop shielded =
        Troop.builder()
            .name("Shielded")
            .team(Team.RED)
            .position(new Position(10, 10))
            .health(new Health(200, 500))
            .deployTime(0f)
            .build();
    shielded.setDeployTimer(0);

    Troop unshielded = createEnemyTroop("Unshielded", 600, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(shielded);
    gameState.spawnEntity(unshielded);
    gameState.processPending();

    system.update(0.46f);

    // Shielded (700 effective HP) should be hit first -- shield absorbs the damage
    assertThat(shielded.getHealth().getShield()).isLessThan(500);
    assertThat(unshielded.getHealth().getCurrent()).isEqualTo(600);

    // Tick 2: unshielded gets hit
    system.update(0.46f);
    assertThat(unshielded.getHealth().getCurrent()).isEqualTo(600 - 413);
  }

  @Test
  void hitBiggestTargets_shouldApplyCrownTowerDamage() {
    AreaEffect effect = createLightningEffect();

    // Crown tower with high HP (should be the biggest target)
    Tower tower = Tower.createPrincessTower(Team.RED, 10, 10, 1);
    tower.setDeployTimer(0);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(tower);
    gameState.processPending();

    int towerHpBefore = tower.getHealth().getCurrent();
    system.update(0.46f);

    // Expected: 413 * (100 + (-73)) / 100 = 413 * 27 / 100 = 111
    int expectedDamage = 413 * 27 / 100;
    assertThat(tower.getHealth().getCurrent()).isEqualTo(towerHpBefore - expectedDamage);
  }
}
