package org.crforge.core.entity.effect;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.HidingAbility;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.BuildingTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Earthquake spell. Earthquake is a ground-only ticking area effect that deals damage
 * over 3 seconds, slows enemies by 50%, and deals 4.5x bonus damage to buildings.
 *
 * <p>Level 1 stats: DPS=32, hitSpeed=0.1s, per-tick damage = round(32 * 0.1) = 3, 30 ticks over
 * 3.0s. Building damage per tick: 3 * (100+350)/100 = 13. Crown tower per tick: 13 * (100+(-35))
 * /100 = 8.
 */
class EarthquakeSpellTest {

  private static final float DT = 1.0f / 30;

  private GameState gameState;
  private AreaEffectSystem system;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    system = new AreaEffectSystem(gameState);

    // Register the Earthquake buff (matching buffs.json)
    BuffRegistry.register(
        "Earthquake",
        BuffDefinition.builder()
            .name("Earthquake")
            .damagePerSecond(32)
            .speedMultiplier(-50)
            .crownTowerDamagePercent(-35)
            .buildingDamagePercent(350)
            .hitFrequency(1.0f)
            .enableStacking(true)
            .build());
  }

  @AfterEach
  void tearDown() {
    BuffRegistry.clear();
  }

  // -- Helpers --

  private AreaEffectStats earthquakeStats() {
    return AreaEffectStats.builder()
        .name("Earthquake")
        .radius(3.5f)
        .lifeDuration(3.0f)
        .hitsGround(true)
        .hitsAir(false)
        .hitSpeed(0.1f)
        .buff("Earthquake")
        .buffDuration(1.0f)
        .capBuffTimeToAreaEffectTime(true)
        .build();
  }

  private AreaEffect createEarthquakeEffect() {
    AreaEffectStats stats = earthquakeStats();
    return AreaEffect.builder()
        .name("Earthquake")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .stats(stats)
        .scaledDamage(3) // round(32 * 0.1)
        .resolvedCrownTowerDamagePercent(-35)
        .buildingDamagePercent(350)
        .remainingLifetime(3.0f)
        .build();
  }

  private Troop createGroundEnemy(String name, int hp, float x, float y) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.RED)
            .position(new Position(x, y))
            .health(new Health(hp))
            .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();
    troop.update(1.0f); // make targetable
    return troop;
  }

  private Troop createAirEnemy(String name, int hp, float x, float y) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.RED)
            .position(new Position(x, y))
            .health(new Health(hp))
            .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.AIR))
            .deployTime(0f)
            .build();
    troop.update(1.0f);
    return troop;
  }

  // -- Tests --

  @Test
  void earthquake_damagesGroundEnemies() {
    AreaEffect effect = createEarthquakeEffect();
    Troop enemy = createGroundEnemy("Knight", 500, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Run for 3.0 seconds (90 ticks at DT=1/30) to process all 30 damage ticks (hitSpeed=0.1s)
    for (int i = 0; i < 90; i++) {
      system.update(DT);
      effect.update(DT);
    }

    // 30 ticks * 3 damage = 90 total
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(500 - 90);
  }

  @Test
  void earthquake_doesNotHitAirUnits() {
    AreaEffect effect = createEarthquakeEffect();
    Troop airUnit = createAirEnemy("BabyDragon", 500, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(airUnit);
    gameState.processPending();

    // Run a few ticks
    for (int i = 0; i < 10; i++) {
      system.update(DT);
    }

    assertThat(airUnit.getHealth().getCurrent()).isEqualTo(500);
  }

  @Test
  void earthquake_appliesSlowDebuff() {
    AreaEffect effect = createEarthquakeEffect();
    Troop enemy = createGroundEnemy("Knight", 500, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // First tick applies buff
    system.update(0.1f);

    assertThat(enemy.getAppliedEffects()).isNotEmpty();
    assertThat(enemy.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.EARTHQUAKE);
    assertThat(enemy.getAppliedEffects().get(0).getBuffName()).isEqualTo("Earthquake");
  }

  @Test
  void earthquake_dealsBonusDamageToBuildings() {
    AreaEffect effect = createEarthquakeEffect();

    // Building within range, no lifetime (avoid health decay interference)
    Building building =
        BuildingTemplate.defense("Cannon", Team.RED).at(11, 10).hp(1000).lifetime(0f).build();
    gameState.spawnEntity(building);
    gameState.spawnEntity(effect);
    gameState.processPending();
    building.onSpawn();

    // One tick of damage
    system.update(0.1f);

    // Building damage: 3 * (100 + 350) / 100 = 3 * 450 / 100 = 13
    assertThat(building.getHealth().getCurrent()).isEqualTo(1000 - 13);
  }

  @Test
  void earthquake_crownTowerDamage() {
    AreaEffect effect = createEarthquakeEffect();

    // Crown tower at level 1
    Tower tower = Tower.createPrincessTower(Team.RED, 10, 10, 1);
    tower.update(1.0f);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(tower);
    gameState.processPending();

    int hpBefore = tower.getHealth().getCurrent();
    system.update(0.1f);

    // Crown tower is a building, so building bonus applies first: 3 * (100+350)/100 = 13
    // Then crown tower reduction: 13 * (100+(-35))/100 = 13 * 65/100 = 8
    assertThat(tower.getHealth().getCurrent()).isEqualTo(hpBefore - 8);
  }

  @Test
  void earthquake_bypasses_hiddenTesla() {
    AreaEffect effect = createEarthquakeEffect();

    // Hidden Tesla -- ability with HidingAbility, manually set to underground state
    AbilityComponent teslaAbility = new AbilityComponent(new HidingAbility(0.5f, 0.5f));
    teslaAbility.setHidingState(AbilityComponent.HidingState.HIDDEN);
    Building tesla =
        BuildingTemplate.defense("Tesla", Team.RED)
            .at(11, 10)
            .hp(1000)
            .lifetime(0f)
            .ability(teslaAbility)
            .build();
    gameState.spawnEntity(tesla);
    gameState.spawnEntity(effect);
    gameState.processPending();
    tesla.onSpawn();

    // Tesla should be hidden
    assertThat(tesla.isHidden()).isTrue();
    assertThat(tesla.isTargetable()).isFalse();

    // Earthquake should still hit it
    system.update(0.1f);

    // Building damage: 3 * (100 + 350) / 100 = 13
    assertThat(tesla.getHealth().getCurrent()).isEqualTo(1000 - 13);

    // Tesla should remain hidden (Earthquake does NOT force reveal, unlike Freeze)
    assertThat(tesla.isHidden()).isTrue();
  }

  @Test
  void earthquake_expiresAfterDuration() {
    AreaEffect effect = createEarthquakeEffect();
    Troop enemy = createGroundEnemy("Knight", 500, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Run past 3.0 seconds (91 ticks to account for float precision -- 90 * 1/30 may not
    // exactly reach 3.0 due to floating point)
    for (int i = 0; i < 91; i++) {
      system.update(DT);
      effect.update(DT);
    }

    assertThat(effect.isAlive()).isFalse();

    int hpAfterExpiry = enemy.getHealth().getCurrent();

    // More ticks should not cause additional damage
    for (int i = 0; i < 10; i++) {
      system.update(DT);
    }

    assertThat(enemy.getHealth().getCurrent()).isEqualTo(hpAfterExpiry);
  }

  @Test
  void earthquake_onlyHitsEnemies() {
    AreaEffect effect = createEarthquakeEffect();

    // Friendly ground troop (same team as the effect)
    Troop friendly =
        Troop.builder()
            .name("FriendlyKnight")
            .team(Team.BLUE)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();
    friendly.update(1.0f);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    for (int i = 0; i < 10; i++) {
      system.update(DT);
    }

    assertThat(friendly.getHealth().getCurrent()).isEqualTo(500);
  }

  @Test
  void earthquake_stacking_twoEarthquakes() {
    // Two overlapping Earthquake effects should deal independent damage
    AreaEffect effect1 = createEarthquakeEffect();
    AreaEffect effect2 =
        AreaEffect.builder()
            .name("Earthquake2")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(earthquakeStats())
            .scaledDamage(3)
            .resolvedCrownTowerDamagePercent(-35)
            .buildingDamagePercent(350)
            .remainingLifetime(3.0f)
            .build();

    Troop enemy = createGroundEnemy("Knight", 500, 11, 10);

    gameState.spawnEntity(effect1);
    gameState.spawnEntity(effect2);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // One tick: both effects apply damage independently
    system.update(0.1f);

    // 3 damage from each = 6 total
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(500 - 6);

    // Stacking: enemy should have 2 independent EARTHQUAKE effects
    long earthquakeEffects =
        enemy.getAppliedEffects().stream()
            .filter(e -> e.getType() == StatusEffectType.EARTHQUAKE)
            .count();
    assertThat(earthquakeEffects).isEqualTo(2);
  }

  @Test
  void earthquake_capBuffTime_slowDoesNotOutlastEffect() {
    // Create earthquake with only 0.2s remaining lifetime
    AreaEffectStats stats = earthquakeStats();
    AreaEffect effect =
        AreaEffect.builder()
            .name("Earthquake")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(3)
            .resolvedCrownTowerDamagePercent(-35)
            .buildingDamagePercent(350)
            .remainingLifetime(0.2f) // Only 0.2s left
            .build();

    Troop enemy = createGroundEnemy("Knight", 500, 11, 10);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Apply one tick -- buff duration should be capped to remaining lifetime (0.2s)
    // instead of the full 1.0s buffDuration
    system.update(0.1f);

    assertThat(enemy.getAppliedEffects()).isNotEmpty();
    // Buff duration should be <= remaining lifetime (0.2s), not the full 1.0s
    assertThat(enemy.getAppliedEffects().get(0).getRemainingDuration()).isLessThanOrEqualTo(0.2f);
  }
}
