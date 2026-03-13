package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the Lumberjack death chain: Lumberjack dies -> RageBarbarianBottle spawns (deathSpawn) ->
 * bottle self-destructs -> Rage AreaEffect spawns (deathAreaEffect) -> friendly troops get RAGE
 * buff.
 */
class LumberjackDeathEffectTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private SpawnerSystem spawnerSystem;
  private AreaEffectSystem areaEffectSystem;

  // Rage area effect stats matching units.json RageBarbarianBottle data
  private AreaEffectStats rageAreaEffect;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    spawnerSystem = new SpawnerSystem(gameState, new AoeDamageService(gameState));
    gameState.setDeathHandler(spawnerSystem::onDeath);
    areaEffectSystem = new AreaEffectSystem(gameState);

    // Register the Rage buff (matches buffs.json)
    BuffRegistry.clear();
    BuffRegistry.register(
        "Rage",
        BuffDefinition.builder()
            .name("Rage")
            .speedMultiplier(130)
            .hitSpeedMultiplier(130)
            .spawnSpeedMultiplier(130)
            .build());

    rageAreaEffect =
        AreaEffectStats.builder()
            .name("BarbarianRage")
            .radius(3.0f)
            .lifeDuration(5.5f)
            .hitsGround(true)
            .hitsAir(true)
            .hitSpeed(0.3f)
            .buff("Rage")
            .buffDuration(1.0f)
            .build();
  }

  @Test
  void deathAreaEffect_shouldSpawnAreaEffectOnDeath() {
    // RageBarbarianBottle: health=0 bomb entity with deathAreaEffect
    SpawnerComponent bottleSpawner =
        SpawnerComponent.builder().deathAreaEffect(rageAreaEffect).selfDestruct(true).build();

    Troop bottle =
        Troop.builder()
            .name("RageBarbarianBottle")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .deployTime(0f)
            .spawner(bottleSpawner)
            .build();

    gameState.spawnEntity(bottle);
    gameState.processPending();

    // Kill the bottle -> should spawn a Rage AreaEffect
    spawnerSystem.onDeath(bottle);
    gameState.processPending();

    List<AreaEffect> effects = gameState.getEntitiesOfType(AreaEffect.class);
    assertThat(effects).hasSize(1);

    AreaEffect rage = effects.get(0);
    assertThat(rage.getName()).isEqualTo("BarbarianRage");
    assertThat(rage.getTeam()).isEqualTo(Team.BLUE);
    assertThat(rage.getPosition().getX()).isEqualTo(10f);
    assertThat(rage.getPosition().getY()).isEqualTo(10f);
    assertThat(rage.getStats().getRadius()).isEqualTo(3.0f);
    assertThat(rage.getStats().getLifeDuration()).isEqualTo(5.5f);
  }

  @Test
  void deathAreaEffect_rageBuffAppliesToFriendlies() {
    // Spawn a Rage AreaEffect directly and verify it buffs friendlies
    AreaEffect rageEffect =
        AreaEffect.builder()
            .name("BarbarianRage")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(rageAreaEffect)
            .remainingLifetime(5.5f)
            .build();

    // Friendly troop within range
    Troop friendly =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(rageEffect);
    gameState.spawnEntity(friendly);
    gameState.processPending();

    // Tick the area effect system -- should apply Rage buff to the friendly
    areaEffectSystem.update(0.3f);

    assertThat(friendly.getAppliedEffects()).isNotEmpty();
    assertThat(friendly.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.RAGE);
  }

  @Test
  void deathAreaEffect_shouldNotAffectEnemies() {
    // Spawn a Rage AreaEffect and verify it does NOT buff enemies
    AreaEffect rageEffect =
        AreaEffect.builder()
            .name("BarbarianRage")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(rageAreaEffect)
            .remainingLifetime(5.5f)
            .build();

    // Enemy troop within range
    Troop enemy =
        Troop.builder()
            .name("Knight")
            .team(Team.RED)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(rageEffect);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    areaEffectSystem.update(0.3f);

    // Enemy should NOT have any effects applied
    assertThat(enemy.getAppliedEffects()).isEmpty();
    // Enemy should NOT take any damage (Rage has no damage)
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(500);
  }

  @Test
  void lumberjack_fullDeathChain() {
    // Full chain: Lumberjack -> RageBarbarianBottle (deathSpawn) -> Rage AreaEffect
    // (deathAreaEffect)
    // -> friendly troops get RAGE buff

    // RageBarbarianBottle stats: health=0, deployTime=0.5, deathAreaEffect=BarbarianRage
    TroopStats bottleStats =
        TroopStats.builder()
            .name("RageBarbarianBottle")
            .health(0)
            .deployTime(0.5f)
            .movementType(MovementType.GROUND)
            .deathAreaEffect(rageAreaEffect)
            .build();

    // Lumberjack stats
    TroopStats lumberjackStats =
        TroopStats.builder()
            .name("RageBarbarian")
            .health(1000)
            .damage(200)
            .speed(1.2f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .deathSpawns(List.of(new DeathSpawnEntry(bottleStats, 1, 0f, 0f)))
            .build();

    // Create lumberjack with death spawn wired
    SpawnerComponent ljSpawner =
        SpawnerComponent.builder()
            .deathSpawns(List.of(new DeathSpawnEntry(bottleStats, 1, 0f, 0f)))
            .build();

    Troop lumberjack =
        Troop.builder()
            .name("RageBarbarian")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(1))
            .movement(new Movement(1.2f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .spawner(ljSpawner)
            .build();

    // Friendly troop nearby that should get Rage
    Troop friendlyKnight =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(11, 10))
            .health(new Health(500))
            .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(lumberjack);
    gameState.spawnEntity(friendlyKnight);
    gameState.processPending();

    // Step 1: Kill Lumberjack -> should death-spawn RageBarbarianBottle
    lumberjack.getHealth().takeDamage(1);
    gameState.processDeaths();

    assertThat(gameState.getPendingSpawns()).hasSize(1);
    Entity bottle = gameState.getPendingSpawns().get(0);
    assertThat(bottle.getName()).isEqualTo("RageBarbarianBottle");
    assertThat(bottle.getSpawner()).isNotNull();
    assertThat(bottle.getSpawner().isSelfDestruct()).isTrue();
    assertThat(bottle.getSpawner().getDeathAreaEffect()).isNotNull();

    gameState.processPending();

    // Step 2: Bottle is deploying (deployTime=0.5s)
    Troop bottleTroop = (Troop) bottle;
    assertThat(bottleTroop.isDeploying()).isTrue();

    // SpawnerSystem should not self-destruct during deploy
    spawnerSystem.update(0.1f);
    assertThat(bottle.getHealth().isAlive()).isTrue();

    // Advance deploy timer past 0.5s
    bottleTroop.update(0.6f);
    assertThat(bottleTroop.isDeploying()).isFalse();

    // Step 3: SpawnerSystem self-destructs the bottle
    spawnerSystem.update(0.1f);
    assertThat(bottle.getHealth().isAlive()).isFalse();

    // processDeaths fires onDeath -> death area effect creates Rage AreaEffect
    gameState.processDeaths();
    gameState.processPending();

    // Step 4: Verify Rage AreaEffect was created
    List<AreaEffect> effects = gameState.getEntitiesOfType(AreaEffect.class);
    assertThat(effects).hasSize(1);
    assertThat(effects.get(0).getName()).isEqualTo("BarbarianRage");
    assertThat(effects.get(0).getTeam()).isEqualTo(Team.BLUE);

    // Step 5: Tick AreaEffectSystem -> friendly gets Rage buff
    areaEffectSystem.update(0.3f);

    assertThat(friendlyKnight.getAppliedEffects()).isNotEmpty();
    assertThat(friendlyKnight.getAppliedEffects().get(0).getType())
        .isEqualTo(StatusEffectType.RAGE);
  }
}
