package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.Map;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.AttackStateMachine;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.engine.EntityTimerSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for BattleHealer: 4-elixir Rare melee troop with two heal mechanics:
 *
 * <p>1. Heal on Hit: each attack spawns a one-shot AreaEffect healing friendlies within 4.0 tiles
 * (40 HP)
 *
 * <p>2. Heal on Deploy: spawns a heal zone on deploy healing friendlies within 2.5 tiles (79 HP)
 */
class BattleHealerTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private AreaEffectSystem areaEffectSystem;
  private final EntityTimerSystem entityTimerSystem = new EntityTimerSystem();
  private Map<String, BuffDefinition> savedBuffs;

  // BattleHealer heal on hit: 40 HP via BattleHealerAll buff (healPerSecond=40, buffDuration=1.0)
  private static final int HEAL_ON_HIT_AMOUNT = 40;
  private static final int HEAL_ON_HIT_RADIUS = tiles(4.0);

  // BattleHealer heal on deploy: 79 HP via BattleHealerSpawnBuff
  private static final int HEAL_ON_DEPLOY_AMOUNT = 79;
  private static final int HEAL_ON_DEPLOY_RADIUS = tiles(2.5);

  private static final int BATTLE_HEALER_HP = 671;
  private static final int BATTLE_HEALER_DAMAGE = 58;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    areaEffectSystem = new AreaEffectSystem(gameState, abilityBridge);

    savedBuffs = BuffRegistry.snapshot();
    BuffRegistry.clear();
    BuffRegistry.register(
        "BattleHealerAll",
        BuffDefinition.builder()
            .name("BattleHealerAll")
            .healPerSecond(40)
            .hitFrequency(0.25f)
            .enableStacking(true)
            .build());
    BuffRegistry.register(
        "BattleHealerSpawnBuff",
        BuffDefinition.builder()
            .name("BattleHealerSpawnBuff")
            .healPerSecond(79)
            .hitFrequency(0.25f)
            .enableStacking(true)
            .build());
  }

  @AfterEach
  void tearDown() {
    BuffRegistry.restore(savedBuffs);
  }

  @Test
  void healOnHit_spawnsAreaEffectAndHealsFriendly() {
    Troop healer = createBattleHealer(Team.BLUE, 5, 10);
    Troop enemy = createTroop(Team.RED, 6, 10, 500);
    Troop friendly = createTroop(Team.BLUE, 6, 10, 500);

    // Damage friendly so we can observe healing
    friendly.getHealth().takeDamage(200);
    assertThat(friendly.getHealth().getCurrent()).isEqualTo(300);

    gameState.spawnEntity(healer);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    // Skip deploy time
    healer.setDeployTimer(0);
    enemy.setDeployTimer(0);
    friendly.setDeployTimer(0);

    // Set target and run combat (1.0s is enough for exactly one attack: windup = 1.5 - 1.2 = 0.3s)
    healer.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // Friendly should be healed by the heal-on-hit area effect
    assertThat(friendly.getHealth().getCurrent())
        .as("Friendly unit should be healed by %d", HEAL_ON_HIT_AMOUNT)
        .isEqualTo(300 + HEAL_ON_HIT_AMOUNT);
  }

  @Test
  void healOnHit_healsSelf() {
    Troop healer = createBattleHealer(Team.BLUE, 5, 10);
    Troop enemy = createTroop(Team.RED, 6, 10, 500);

    // Damage healer so we can observe self-heal
    healer.getHealth().takeDamage(200);
    int hpBeforeAttack = healer.getHealth().getCurrent();

    gameState.spawnEntity(healer);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    healer.setDeployTimer(0);
    enemy.setDeployTimer(0);

    healer.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // BattleHealer should heal herself (she's within her own 4.0-tile radius)
    assertThat(healer.getHealth().getCurrent())
        .as("BattleHealer should self-heal by %d", HEAL_ON_HIT_AMOUNT)
        .isEqualTo(hpBeforeAttack + HEAL_ON_HIT_AMOUNT);
  }

  @Test
  void healOnDeploy_healsDamagedFriendly() {
    // Create a damaged friendly troop near where the BattleHealer will be deployed
    Troop friendly = createTroop(Team.BLUE, 5.5f, 10, 500);
    friendly.getHealth().takeDamage(200);
    assertThat(friendly.getHealth().getCurrent()).isEqualTo(300);

    gameState.spawnEntity(friendly);
    gameState.processPending();
    friendly.setDeployTimer(0);

    // Deploy a BattleHealer heal zone (simulating the deploy effect)
    AreaEffectStats deployEffect =
        AreaEffectStats.builder()
            .name("BattleHealerSpawnHeal")
            .radius(HEAL_ON_DEPLOY_RADIUS)
            .lifeDuration(1.0f)
            .hitsGround(true)
            .hitsAir(true)
            .buffApplication(BuffApplication.of("BattleHealerSpawnBuff", 1.0f))
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("BattleHealerSpawnHeal")
            .team(Team.BLUE)
            .position(new Position(tiles(5), tiles(10)))
            .stats(deployEffect)
            .remainingLifetime(1.0f)
            .build();

    gameState.spawnEntity(effect);
    gameState.processPending();
    gameState.refreshCaches();

    // Process the area effect
    areaEffectSystem.update(1.0f / 30f);

    assertThat(friendly.getHealth().getCurrent())
        .as("Friendly should be healed by deploy effect for %d HP", HEAL_ON_DEPLOY_AMOUNT)
        .isEqualTo(300 + HEAL_ON_DEPLOY_AMOUNT);
  }

  @Test
  void healOnHit_doesNotHealBuildings() {
    Troop healer = createBattleHealer(Team.BLUE, 5, 10);
    Troop enemy = createTroop(Team.RED, 6, 10, 500);

    // Create a friendly building within heal radius (lifetime=0 means no lifetime limit/drain)
    Building friendlyBuilding =
        Building.builder()
            .name("FriendlyTower")
            .team(Team.BLUE)
            .position(new Position(tiles(5.5), tiles(10)))
            .health(new Health(1000))
            .movement(new Movement(0, 0, tiles(0.5), tiles(0.5), MovementType.BUILDING))
            .lifetime(0f)
            .remainingLifetime(0f)
            .build();
    friendlyBuilding.getHealth().takeDamage(200);
    int buildingHpBefore = friendlyBuilding.getHealth().getCurrent();

    gameState.spawnEntity(healer);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendlyBuilding);
    gameState.processPending();

    healer.setDeployTimer(0);
    enemy.setDeployTimer(0);

    healer.getCombat().setCurrentTarget(enemy);
    runCombatUpdates(1.0f);

    // Building should NOT be healed
    assertThat(friendlyBuilding.getHealth().getCurrent())
        .as("Friendly building should NOT be healed by BattleHealer")
        .isEqualTo(buildingHpBefore);
  }

  @Test
  void dataLoading_areaEffectOnHitIsResolvedOnCombatComponent() {
    // Verify the areaEffectOnHit field flows through the full pipeline:
    // TroopStats -> Combat component
    AreaEffectStats aoeOnHit =
        AreaEffectStats.builder()
            .name("BattleHealerHeal")
            .radius(tiles(4.0))
            .lifeDuration(0.05f)
            .hitsGround(true)
            .hitsAir(true)
            .buffApplication(BuffApplication.of("BattleHealerAll", 1.0f))
            .build();

    TroopStats stats =
        TroopStats.builder()
            .name("BattleHealer")
            .health(671)
            .damage(58)
            .range(tiles(1.6))
            .attackCooldown(1.5f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .areaEffectOnHit(aoeOnHit)
            .build();

    Combat combat =
        Combat.builder()
            .damage(58)
            .range(tiles(1.6))
            .attackCooldown(1.5f)
            .targetType(TargetType.GROUND)
            .areaEffectOnHit(stats.getAreaEffectOnHit())
            .build();

    assertThat(combat.getAreaEffectOnHit()).isNotNull();
    assertThat(combat.getAreaEffectOnHit().getName()).isEqualTo("BattleHealerHeal");
    assertThat(combat.getAreaEffectOnHit().getRadius()).isEqualTo(tiles(4.0));
    assertThat(combat.getAreaEffectOnHit().getBuff()).isEqualTo("BattleHealerAll");
  }

  @Test
  void dataLoading_spawnAreaEffectIsPromotedToDeployEffect() {
    // Verify unit-level spawnAreaEffect is promoted to card deployEffect
    AreaEffectStats spawnAE =
        AreaEffectStats.builder()
            .name("BattleHealerSpawnHeal")
            .radius(tiles(2.5))
            .lifeDuration(1.0f)
            .buffApplication(BuffApplication.of("BattleHealerSpawnBuff", 1.0f))
            .build();

    TroopStats unitStats =
        TroopStats.builder()
            .name("BattleHealer")
            .health(671)
            .damage(58)
            .range(tiles(1.6))
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .spawnAreaEffect(spawnAE)
            .build();

    // Simulate what CardLoader.convert does: promote spawnAreaEffect to deployEffect
    Card card =
        Card.builder()
            .id("battlehealer")
            .name("BattleHealer")
            .type(CardType.TROOP)
            .rarity(Rarity.RARE)
            .cost(4)
            .unitStats(unitStats)
            .deployEffect(unitStats.getSpawnAreaEffect())
            .build();

    assertThat(card.getDeployEffect()).isNotNull();
    assertThat(card.getDeployEffect().getName()).isEqualTo("BattleHealerSpawnHeal");
    assertThat(card.getDeployEffect().getRadius()).isEqualTo(tiles(2.5));
    assertThat(card.getDeployEffect().getBuff()).isEqualTo("BattleHealerSpawnBuff");
  }

  /** Creates a BattleHealer at tile coordinates. */
  private Troop createBattleHealer(Team team, float x, float y) {
    AreaEffectStats aoeOnHit =
        AreaEffectStats.builder()
            .name("BattleHealerHeal")
            .radius(HEAL_ON_HIT_RADIUS)
            .lifeDuration(0.05f)
            .hitsGround(true)
            .hitsAir(true)
            .buffApplication(BuffApplication.of("BattleHealerAll", 1.0f))
            .build();

    Combat combat =
        Combat.builder()
            .damage(BATTLE_HEALER_DAMAGE)
            .range(tiles(1.6))
            .sightRange(tiles(5.5))
            .attackCooldown(1.5f)
            .loadTime(1.2f)
            .attackState(AttackStateMachine.withLoad(1.2f))
            .targetType(TargetType.GROUND)
            .areaEffectOnHit(aoeOnHit)
            .build();

    return Troop.builder()
        .name("BattleHealer")
        .team(team)
        .position(new Position(tiles(x), tiles(y)))
        .health(new Health(BATTLE_HEALER_HP))
        .movement(new Movement(tiles(1.0), 6.0f, tiles(0.5), tiles(0.5), MovementType.GROUND))
        .deployTime(1.0f)
        .combat(combat)
        .build();
  }

  /** Creates a plain troop at tile coordinates. */
  private Troop createTroop(Team team, float x, float y, int hp) {
    return Troop.builder()
        .name("Troop")
        .team(team)
        .position(new Position(tiles(x), tiles(y)))
        .health(new Health(hp))
        .movement(new Movement(tiles(1.0), 1.0f, tiles(0.4), tiles(0.4), MovementType.GROUND))
        .deployTime(1.0f)
        .build();
  }

  private void runCombatUpdates(float duration) {
    float dt = 1.0f / 30f;
    int ticks = Math.round(duration / dt);
    for (int i = 0; i < ticks; i++) {
      gameState.refreshCaches();
      entityTimerSystem.update(gameState.getAliveEntities(), dt);
      combatSystem.update(dt);
      gameState.processPending();
      areaEffectSystem.update(dt);
    }
  }
}
