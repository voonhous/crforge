package org.crforge.core.entity.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.ability.VariableDamageStage;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectSystem;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TornadoTest {

  private static final float DT = 1.0f / 30; // One game tick

  private GameState gameState;
  private AreaEffectSystem areaEffectSystem;
  private StatusEffectSystem statusEffectSystem;
  private Map<String, BuffDefinition> buffSnapshot;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    areaEffectSystem = new AreaEffectSystem(gameState, new DefaultCombatAbilityBridge());
    statusEffectSystem = new StatusEffectSystem();

    // Save existing BuffRegistry state and register Tornado buff for tests
    buffSnapshot = BuffRegistry.snapshot();
    BuffRegistry.register(
        "Tornado",
        BuffDefinition.builder()
            .name("Tornado")
            .damagePerSecond(60)
            .crownTowerDamagePercent(-70)
            .attractPercentage(360)
            .pushSpeedFactor(100)
            .hitFrequency(0.55f)
            .enableStacking(true)
            .controlledByParent(true)
            .build());
  }

  @AfterEach
  void tearDown() {
    BuffRegistry.restore(buffSnapshot);
  }

  private AreaEffectStats tornadoStats() {
    return AreaEffectStats.builder()
        .name("Tornado")
        .radius(5.5f)
        .lifeDuration(1.05f)
        .hitsGround(true)
        .hitsAir(true)
        .hitSpeed(0.05f)
        .buffApplication(BuffApplication.of("Tornado", 0.5f))
        .controlsBuff(true)
        .onlyEnemies(true)
        .build();
  }

  private AreaEffect createTornado(float x, float y) {
    AreaEffectStats stats = tornadoStats();
    return AreaEffect.builder()
        .name("Tornado")
        .team(Team.BLUE)
        .position(new Position(x, y))
        .stats(stats)
        .scaledDamage(0) // Tornado damage comes from buff DPS, not direct damage
        .remainingLifetime(stats.getLifeDuration())
        .build();
  }

  private Troop createTroop(
      String name, Team team, float x, float y, int hp, float mass, MovementType movementType) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(team)
            .position(new Position(x, y))
            .health(new Health(hp))
            .movement(new Movement(1.0f, mass, 0.5f, 0.5f, movementType))
            .deployTime(0f)
            .build();
    troop.setDeployTimer(0); // Make targetable
    return troop;
  }

  private Troop createGroundTroop(String name, Team team, float x, float y, int hp, float mass) {
    return createTroop(name, team, x, y, hp, mass, MovementType.GROUND);
  }

  // --- Pull mechanic tests ---

  @Test
  void tornado_pullsLightTroopMoreThanHeavy() {
    // HogRider (mass=4) should be pulled more than Giant (mass=18) after same number of ticks
    AreaEffect tornado = createTornado(10, 10);
    Troop hogRider = createGroundTroop("HogRider", Team.RED, 13, 10, 1000, 4);
    Troop giant = createGroundTroop("Giant", Team.RED, 13, 10, 3000, 18);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(hogRider);
    gameState.spawnEntity(giant);
    gameState.processPending();

    float hogStartX = hogRider.getPosition().getX();
    float giantStartX = giant.getPosition().getX();

    // Run several ticks of pull
    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    float hogDisplacement = hogStartX - hogRider.getPosition().getX();
    float giantDisplacement = giantStartX - giant.getPosition().getX();

    assertThat(hogDisplacement).isGreaterThan(0); // Both pulled toward center
    assertThat(giantDisplacement).isGreaterThan(0);
    assertThat(hogDisplacement).isGreaterThan(giantDisplacement); // Light troop pulled more
  }

  @Test
  void tornado_pullsGroundTroopTowardCenter() {
    AreaEffect tornado = createTornado(10, 10);
    Troop troop = createGroundTroop("Knight", Team.RED, 13, 10, 1000, 6);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(troop);
    gameState.processPending();

    float startX = troop.getPosition().getX();

    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    // X should decrease (pulled toward center at x=10)
    assertThat(troop.getPosition().getX()).isLessThan(startX);
  }

  @Test
  void tornado_pullsAirTroopTowardCenter() {
    AreaEffect tornado = createTornado(10, 10);
    Troop airTroop = createTroop("Minion", Team.RED, 13, 10, 500, 3, MovementType.AIR);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(airTroop);
    gameState.processPending();

    float startX = airTroop.getPosition().getX();

    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    assertThat(airTroop.getPosition().getX()).isLessThan(startX);
  }

  @Test
  void tornado_doesNotPullBuildings() {
    AreaEffect tornado = createTornado(10, 10);

    Building building =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(13, 10))
            .health(new Health(500))
            .movement(new Movement(0, 10, 0.5f, 0.5f, MovementType.BUILDING))
            .combat(null)
            .deployTime(0f)
            .build();
    building.setDeployTimer(0); // Make targetable

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(building);
    gameState.processPending();

    float startX = building.getPosition().getX();

    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    assertThat(building.getPosition().getX()).isEqualTo(startX);
  }

  @Test
  void tornado_doesNotPullFriendlyUnits() {
    AreaEffect tornado = createTornado(10, 10);
    // Same team as tornado (BLUE)
    Troop friendly = createGroundTroop("Knight", Team.BLUE, 13, 10, 1000, 6);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    float startX = friendly.getPosition().getX();

    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    assertThat(friendly.getPosition().getX()).isEqualTo(startX);
  }

  @Test
  void tornado_doesNotAffectEntitiesOutOfRange() {
    AreaEffect tornado = createTornado(10, 10);
    // Well outside 5.5 tile radius
    Troop farTroop = createGroundTroop("Knight", Team.RED, 20, 10, 1000, 6);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(farTroop);
    gameState.processPending();

    float startX = farTroop.getPosition().getX();

    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    assertThat(farTroop.getPosition().getX()).isEqualTo(startX);
  }

  @Test
  void tornado_pullDoesNotOvershootCenter() {
    AreaEffect tornado = createTornado(10, 10);
    // Very close to center -- displacement should be capped
    Troop troop = createGroundTroop("Knight", Team.RED, 10.01f, 10, 1000, 6);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(troop);
    gameState.processPending();

    areaEffectSystem.update(DT);

    // Should not overshoot past center
    assertThat(troop.getPosition().getX()).isGreaterThanOrEqualTo(10.0f);
    assertThat(troop.getPosition().getX()).isCloseTo(10.0f, within(0.02f));
  }

  @Test
  void tornado_stunnedEntitySkipsPull() {
    AreaEffect tornado = createTornado(10, 10);
    Troop troop = createGroundTroop("Knight", Team.RED, 13, 10, 1000, 6);

    // Apply STUN effect to the troop
    troop.addEffect(new AppliedEffect(StatusEffectType.STUN, 5.0f, "ZapFreeze"));

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(troop);
    gameState.processPending();

    float startX = troop.getPosition().getX();

    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
    }

    // Stunned troop should not be pulled
    assertThat(troop.getPosition().getX()).isEqualTo(startX);
  }

  // --- Damage tests ---

  @Test
  void tornado_dealsDamageOverTime() {
    AreaEffect tornado = createTornado(10, 10);
    // Tornado stats: hitSpeed=0.05, damage from buff DPS
    // The area effect has damage=0 but the buff ticks apply damage
    // Actually looking at AreaEffectSystem, damage comes from effect.getEffectiveDamage()
    // For proper damage testing, we need scaledDamage > 0
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Tornado")
            .radius(5.5f)
            .lifeDuration(1.05f)
            .hitsGround(true)
            .hitsAir(true)
            .hitSpeed(0.05f)
            .damage(3) // Low per-tick damage (DPS 60 * hitSpeed 0.05 = 3 per tick)
            .buffApplication(BuffApplication.of("Tornado", 0.5f))
            .controlsBuff(true)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Tornado")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(3)
            .remainingLifetime(1.05f)
            .build();

    Troop troop = createGroundTroop("Knight", Team.RED, 11, 10, 1000, 6);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(troop);
    gameState.processPending();

    // First tick at 0.05s
    areaEffectSystem.update(0.05f);
    assertThat(troop.getHealth().getCurrent()).isEqualTo(997); // 1000 - 3

    // Second tick
    areaEffectSystem.update(0.05f);
    assertThat(troop.getHealth().getCurrent()).isEqualTo(994); // 997 - 3
  }

  @Test
  void tornado_crownTowerDamageReduction() {
    AreaEffectStats stats =
        AreaEffectStats.builder()
            .name("Tornado")
            .radius(5.5f)
            .lifeDuration(1.05f)
            .hitsGround(true)
            .hitsAir(true)
            .hitSpeed(0.05f)
            .damage(100) // Use round number for easy verification
            .buffApplication(BuffApplication.of("Tornado", 0.5f))
            .crownTowerDamagePercent(-70)
            .controlsBuff(true)
            .onlyEnemies(true)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("Tornado")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(100)
            .remainingLifetime(1.05f)
            .build();

    Tower tower = Tower.createPrincessTower(Team.RED, 10, 10, 1);
    tower.setDeployTimer(0);

    gameState.spawnEntity(effect);
    gameState.spawnEntity(tower);
    gameState.processPending();

    int hpBefore = tower.getHealth().getCurrent();
    areaEffectSystem.update(0.05f);

    // Crown tower damage: 100 * (100 + (-70)) / 100 = 100 * 30 / 100 = 30
    assertThat(tower.getHealth().getCurrent()).isEqualTo(hpBefore - 30);
  }

  // --- controlsBuff lifecycle tests ---

  @Test
  void tornado_controlsBuffCleansUpOnExpiry() {
    AreaEffect tornado = createTornado(10, 10);
    Troop troop = createGroundTroop("Knight", Team.RED, 11, 10, 1000, 6);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(troop);
    gameState.processPending();

    // Run one tick to apply buff
    areaEffectSystem.update(0.05f);
    assertThat(troop.getAppliedEffects()).isNotEmpty();
    assertThat(troop.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.TORNADO);

    // Kill the area effect by expiring it
    tornado.markDead();
    assertThat(tornado.isAlive()).isFalse();

    // Next update should clean up the buff
    areaEffectSystem.update(DT);

    assertThat(troop.getAppliedEffects()).isEmpty();
  }

  @Test
  void tornado_controlledByParentPreventsBuffSelfExpiry() {
    // Register Tornado buff, apply it to a troop, and verify StatusEffectSystem
    // does not count down its duration
    Troop troop = createGroundTroop("Knight", Team.RED, 11, 10, 1000, 6);
    troop.addEffect(new AppliedEffect(StatusEffectType.TORNADO, 0.5f, "Tornado"));

    gameState.spawnEntity(troop);
    gameState.processPending();

    // Run StatusEffectSystem for a long time -- buff should not expire
    for (int i = 0; i < 60; i++) {
      statusEffectSystem.update(gameState, DT);
    }

    // After 60 ticks (2 seconds), the 0.5s buff should still be present
    assertThat(troop.getAppliedEffects()).hasSize(1);
    assertThat(troop.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.TORNADO);
  }

  // --- Charge/Variable damage interaction tests ---

  @Test
  void tornado_doesNotResetChargeAbility() {
    // Create a troop with charge ability (like Prince)
    AbilityComponent chargeAbility = new AbilityComponent(new ChargeAbility(200, 1.5f));
    chargeAbility.setCharged(true);

    Troop chargeTroop =
        Troop.builder()
            .name("Prince")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(1000))
            .movement(new Movement(1.0f, 6.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .ability(chargeAbility)
            .build();
    chargeTroop.update(1.0f);

    AreaEffect tornado = createTornado(10, 10);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(chargeTroop);
    gameState.processPending();

    // Apply one tick to trigger buff application
    areaEffectSystem.update(0.05f);

    // Charge state should NOT be reset by Tornado (it's not STUN/FREEZE)
    assertThat(chargeAbility.isCharged()).isTrue();
  }

  @Test
  void tornado_doesNotResetVariableDamage() {
    // Create a troop with variable damage (like InfernoDragon)
    VariableDamageAbility varDmgData =
        new VariableDamageAbility(
            List.of(
                new VariableDamageStage(30, 1.0f),
                new VariableDamageStage(60, 1.0f),
                new VariableDamageStage(120, 1.0f)));
    AbilityComponent variableAbility = new AbilityComponent(varDmgData);
    variableAbility.setCurrentStage(2); // Simulate being at stage 3

    Combat combat = Combat.builder().damage(30).range(4.0f).attackCooldown(0.4f).build();

    Troop infernoDragon =
        Troop.builder()
            .name("InfernoDragon")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(1000))
            .movement(new Movement(1.0f, 5.0f, 0.5f, 0.5f, MovementType.AIR))
            .deployTime(0f)
            .ability(variableAbility)
            .combat(combat)
            .build();
    infernoDragon.update(1.0f);

    AreaEffect tornado = createTornado(10, 10);

    gameState.spawnEntity(tornado);
    gameState.spawnEntity(infernoDragon);
    gameState.processPending();

    areaEffectSystem.update(0.05f);

    // Variable damage stage should NOT be reset by Tornado
    assertThat(variableAbility.getCurrentStage()).isEqualTo(2);
  }

  // --- Stacking test ---

  @Test
  void tornado_twoOverlappingTornadosStackPull() {
    AreaEffect tornado1 = createTornado(10, 10);
    AreaEffect tornado2 = createTornado(10, 10);
    Troop troop = createGroundTroop("Knight", Team.RED, 13, 10, 1000, 6);

    // Single tornado reference troop
    Troop singleRefTroop = createGroundTroop("KnightRef", Team.RED, 13, 10, 1000, 6);

    gameState.spawnEntity(tornado1);
    gameState.spawnEntity(tornado2);
    gameState.spawnEntity(troop);
    gameState.processPending();

    // Create a separate game state for single tornado comparison
    GameState singleState = new GameState();
    AreaEffectSystem singleSystem =
        new AreaEffectSystem(singleState, new DefaultCombatAbilityBridge());
    AreaEffect singleTornado = createTornado(10, 10);
    singleState.spawnEntity(singleTornado);
    singleState.spawnEntity(singleRefTroop);
    singleState.processPending();

    // Run 10 ticks
    for (int i = 0; i < 10; i++) {
      areaEffectSystem.update(DT);
      singleSystem.update(DT);
    }

    float doubleDisplacement = 13.0f - troop.getPosition().getX();
    float singleDisplacement = 13.0f - singleRefTroop.getPosition().getX();

    assertThat(doubleDisplacement).isGreaterThan(singleDisplacement);
  }
}
