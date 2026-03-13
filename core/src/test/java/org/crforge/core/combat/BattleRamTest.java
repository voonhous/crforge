package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.SpawnerSystem;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Battle Ram's kamikaze mechanic: unit dies after delivering its attack, then
 * death-spawns 2 Barbarians via the existing SpawnerSystem.
 */
class BattleRamTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private SpawnerSystem spawnerSystem;

  // Barbarian stats used for death spawn
  private static final TroopStats BARBARIAN_STATS =
      TroopStats.builder()
          .name("Barbarian")
          .health(300)
          .damage(75)
          .speed(1.0f)
          .range(0.7f)
          .attackCooldown(1.4f)
          .movementType(MovementType.GROUND)
          .targetType(TargetType.GROUND)
          .build();

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    spawnerSystem = new SpawnerSystem(gameState, new AoeDamageService(gameState));
    gameState.setDeathHandler(spawnerSystem::onDeath);
  }

  @Test
  void chargedAttack_killsSelfAndSpawnsBarbarians() {
    Troop battleRam = createBattleRam(Team.BLUE, 5, 5);
    Building tower = createBuilding(Team.RED, 6, 5, 2000);

    gameState.spawnEntity(battleRam);
    gameState.spawnEntity(tower);
    gameState.processPending();

    // Finish deploy
    battleRam.update(2.0f);
    tower.update(2.0f);

    // Fully charge the Battle Ram
    battleRam.getAbility().setCharged(true);

    // Set target and execute combat -- charge impact skips windup
    battleRam.getCombat().setCurrentTarget(tower);
    combatSystem.update(0.1f);

    // Battle Ram should be dead after delivering its attack
    assertThat(battleRam.getHealth().isDead())
        .as("Battle Ram should die after delivering its attack (kamikaze)")
        .isTrue();

    // Tower should have taken charge damage (224)
    assertThat(tower.getHealth().getCurrent())
        .as("Tower should take charge damage")
        .isEqualTo(2000 - 224);

    // Process deaths to trigger death spawn
    gameState.processDeaths();
    gameState.processPending();

    // 2 Barbarians should have spawned
    long barbCount =
        gameState.getAliveEntities().stream()
            .filter(e -> e instanceof Troop t && "Barbarian".equals(t.getName()))
            .count();
    assertThat(barbCount).as("2 Barbarians should spawn on Battle Ram death").isEqualTo(2);
  }

  @Test
  void unchargedAttack_stillKillsSelfAndSpawnsBarbarians() {
    Troop battleRam = createBattleRam(Team.BLUE, 5, 5);
    Building tower = createBuilding(Team.RED, 6, 5, 2000);

    gameState.spawnEntity(battleRam);
    gameState.spawnEntity(tower);
    gameState.processPending();

    // Finish deploy
    battleRam.update(2.0f);
    tower.update(2.0f);

    // Do NOT charge -- ability stays uncharged
    assertThat(battleRam.getAbility().isCharged()).isFalse();

    // Set target
    battleRam.getCombat().setCurrentTarget(tower);

    // Run combat updates until attack executes (windup = attackCooldown - loadTime = 0.4 - 0.35 =
    // 0.05s)
    runCombatUpdates(0.5f);

    // Battle Ram should be dead after delivering its uncharged attack
    assertThat(battleRam.getHealth().isDead())
        .as("Battle Ram should die after uncharged attack (kamikaze)")
        .isTrue();

    // Tower should have taken normal damage (112), not charge damage
    assertThat(tower.getHealth().getCurrent())
        .as("Tower should take normal (uncharged) damage")
        .isEqualTo(2000 - 112);

    // Process deaths to trigger death spawn
    gameState.processDeaths();
    gameState.processPending();

    // 2 Barbarians should have spawned
    long barbCount =
        gameState.getAliveEntities().stream()
            .filter(e -> e instanceof Troop t && "Barbarian".equals(t.getName()))
            .count();
    assertThat(barbCount).as("2 Barbarians should spawn on Battle Ram death").isEqualTo(2);
  }

  @Test
  void killedBeforeReachingBuilding_stillSpawnsBarbarians() {
    Troop battleRam = createBattleRam(Team.BLUE, 5, 5);

    gameState.spawnEntity(battleRam);
    gameState.processPending();

    // Finish deploy
    battleRam.update(2.0f);

    // Kill Battle Ram via external damage (simulating enemy troop killing it)
    battleRam.getHealth().takeDamage(5000);
    assertThat(battleRam.getHealth().isDead()).isTrue();

    // Process deaths to trigger death spawn
    gameState.processDeaths();
    gameState.processPending();

    // 2 Barbarians should still spawn from death spawn
    long barbCount =
        gameState.getAliveEntities().stream()
            .filter(e -> e instanceof Troop t && "Barbarian".equals(t.getName()))
            .count();
    assertThat(barbCount)
        .as("2 Barbarians should spawn even when Battle Ram is killed before reaching a building")
        .isEqualTo(2);
  }

  private Troop createBattleRam(Team team, float x, float y) {
    // Death spawn config: 2 Barbarians
    List<DeathSpawnEntry> deathSpawns = List.of(new DeathSpawnEntry(BARBARIAN_STATS, 2, 0.6f, 0f));

    SpawnerComponent spawner =
        SpawnerComponent.builder().deathSpawns(deathSpawns).rarity(Rarity.RARE).level(1).build();

    Combat combat =
        Combat.builder()
            .damage(112)
            .range(0.5f)
            .sightRange(5.5f)
            .attackCooldown(0.4f)
            .loadTime(0.35f)
            .accumulatedLoadTime(0.35f) // Preloaded
            .targetOnlyBuildings(true)
            .kamikaze(true)
            .targetType(TargetType.GROUND)
            .build();

    AbilityComponent ability = new AbilityComponent(new ChargeAbility(224, 2.0f));

    return Troop.builder()
        .name("BattleRam")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(378))
        .deployTime(1.0f)
        .movement(new Movement(1.0f, 6.0f, 0.75f, 0.75f, MovementType.GROUND))
        .combat(combat)
        .spawner(spawner)
        .ability(ability)
        .build();
  }

  private Building createBuilding(Team team, float x, float y, int hp) {
    return Building.builder()
        .name("Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  private void runCombatUpdates(float duration) {
    float dt = 0.1f;
    int ticks = (int) (duration / dt);
    for (int i = 0; i < ticks; i++) {
      for (Entity e : gameState.getAliveEntities()) {
        e.update(dt);
      }
      combatSystem.update(dt);
    }
  }
}
