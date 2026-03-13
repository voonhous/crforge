package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
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
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
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

  // BattleHealer heal on hit: 40 HP via BattleHealerAll buff (healPerSecond=40, buffDuration=1.0)
  private static final int HEAL_ON_HIT_AMOUNT = 40;
  private static final float HEAL_ON_HIT_RADIUS = 4.0f;

  // BattleHealer heal on deploy: 79 HP via BattleHealerSpawnBuff
  private static final int HEAL_ON_DEPLOY_AMOUNT = 79;
  private static final float HEAL_ON_DEPLOY_RADIUS = 2.5f;

  private static final int BATTLE_HEALER_HP = 671;
  private static final int BATTLE_HEALER_DAMAGE = 58;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState, new AoeDamageService(gameState));
    areaEffectSystem = new AreaEffectSystem(gameState);

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
    healer.update(2.0f);
    enemy.update(2.0f);
    friendly.update(2.0f);

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

    healer.update(2.0f);
    enemy.update(2.0f);

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
    friendly.update(2.0f);

    // Deploy a BattleHealer heal zone (simulating the deploy effect)
    AreaEffectStats deployEffect =
        AreaEffectStats.builder()
            .name("BattleHealerSpawnHeal")
            .radius(HEAL_ON_DEPLOY_RADIUS)
            .lifeDuration(1.0f)
            .hitsGround(true)
            .hitsAir(true)
            .buff("BattleHealerSpawnBuff")
            .buffDuration(1.0f)
            .build();

    AreaEffect effect =
        AreaEffect.builder()
            .name("BattleHealerSpawnHeal")
            .team(Team.BLUE)
            .position(new Position(5, 10))
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
            .position(new Position(5.5f, 10))
            .health(new Health(1000))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .lifetime(0f)
            .remainingLifetime(0f)
            .build();
    friendlyBuilding.getHealth().takeDamage(200);
    int buildingHpBefore = friendlyBuilding.getHealth().getCurrent();

    gameState.spawnEntity(healer);
    gameState.spawnEntity(enemy);
    gameState.spawnEntity(friendlyBuilding);
    gameState.processPending();

    healer.update(2.0f);
    enemy.update(2.0f);

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
            .radius(4.0f)
            .lifeDuration(0.05f)
            .hitsGround(true)
            .hitsAir(true)
            .buff("BattleHealerAll")
            .buffDuration(1.0f)
            .build();

    TroopStats stats =
        TroopStats.builder()
            .name("BattleHealer")
            .health(671)
            .damage(58)
            .range(1.6f)
            .attackCooldown(1.5f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .areaEffectOnHit(aoeOnHit)
            .build();

    Combat combat =
        Combat.builder()
            .damage(58)
            .range(1.6f)
            .attackCooldown(1.5f)
            .targetType(TargetType.GROUND)
            .areaEffectOnHit(stats.getAreaEffectOnHit())
            .build();

    assertThat(combat.getAreaEffectOnHit()).isNotNull();
    assertThat(combat.getAreaEffectOnHit().getName()).isEqualTo("BattleHealerHeal");
    assertThat(combat.getAreaEffectOnHit().getRadius()).isEqualTo(4.0f);
    assertThat(combat.getAreaEffectOnHit().getBuff()).isEqualTo("BattleHealerAll");
  }

  @Test
  void dataLoading_spawnAreaEffectIsPromotedToDeployEffect() {
    // Verify unit-level spawnAreaEffect is promoted to card deployEffect
    AreaEffectStats spawnAE =
        AreaEffectStats.builder()
            .name("BattleHealerSpawnHeal")
            .radius(2.5f)
            .lifeDuration(1.0f)
            .buff("BattleHealerSpawnBuff")
            .buffDuration(1.0f)
            .build();

    TroopStats unitStats =
        TroopStats.builder()
            .name("BattleHealer")
            .health(671)
            .damage(58)
            .range(1.6f)
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
    assertThat(card.getDeployEffect().getRadius()).isEqualTo(2.5f);
    assertThat(card.getDeployEffect().getBuff()).isEqualTo("BattleHealerSpawnBuff");
  }

  private Troop createBattleHealer(Team team, float x, float y) {
    AreaEffectStats aoeOnHit =
        AreaEffectStats.builder()
            .name("BattleHealerHeal")
            .radius(HEAL_ON_HIT_RADIUS)
            .lifeDuration(0.05f)
            .hitsGround(true)
            .hitsAir(true)
            .buff("BattleHealerAll")
            .buffDuration(1.0f)
            .build();

    Combat combat =
        Combat.builder()
            .damage(BATTLE_HEALER_DAMAGE)
            .range(1.6f)
            .sightRange(5.5f)
            .attackCooldown(1.5f)
            .loadTime(1.2f)
            .accumulatedLoadTime(1.2f)
            .targetType(TargetType.GROUND)
            .areaEffectOnHit(aoeOnHit)
            .build();

    return Troop.builder()
        .name("BattleHealer")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(BATTLE_HEALER_HP))
        .movement(new Movement(1.0f, 6.0f, 0.5f, 0.5f, MovementType.GROUND))
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
    float dt = 1.0f / 30f;
    int ticks = Math.round(duration / dt);
    for (int i = 0; i < ticks; i++) {
      gameState.refreshCaches();
      for (Entity e : gameState.getAliveEntities()) {
        e.update(dt);
      }
      combatSystem.update(dt);
      gameState.processPending();
      areaEffectSystem.update(dt);
    }
  }
}
