package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.BuildingTemplate;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.TroopTemplate;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Bomb Tower -- a defensive building that attacks ground units with splash
 * projectiles and drops a death bomb (BombTowerBomb) on destruction. The bomb has a 3-second fuse
 * (deploy time) and then explodes, dealing AOE death damage to all nearby enemies (ground + air).
 */
class BombTowerIntegrationTest {

  // BombTower level 1 stats from units.json
  private static final int BOMB_TOWER_HP = 530;
  private static final int BOMB_TOWER_DAMAGE = 87;
  private static final float BOMB_TOWER_RANGE = 6.0f;
  private static final float BOMB_TOWER_LIFETIME = 30.0f;

  // BombTowerBomb stats
  private static final int BOMB_DEATH_DAMAGE = 87;
  private static final float BOMB_DEATH_DAMAGE_RADIUS = 3.0f;
  private static final float BOMB_DEPLOY_TIME = 3.0f;

  @Test
  void bombTowerCard_shouldLoadFromRegistry() {
    Card card = CardRegistry.get("bombtower");
    assertThat(card).isNotNull();
    assertThat(card.getType()).isEqualTo(CardType.BUILDING);
    assertThat(card.getCost()).isEqualTo(4);
    assertThat(card.getRarity()).isEqualTo(Rarity.RARE);

    // Unit stats should be loaded with BombTower data
    TroopStats unitStats = card.getUnitStats();
    assertThat(unitStats).isNotNull();
    assertThat(unitStats.getName()).isEqualTo("BombTower");
    assertThat(unitStats.getHealth()).isEqualTo(BOMB_TOWER_HP);
    assertThat(unitStats.getDamage()).isEqualTo(BOMB_TOWER_DAMAGE);
    assertThat(unitStats.getRange()).isEqualTo(BOMB_TOWER_RANGE);
    assertThat(unitStats.getLifeTime()).isEqualTo(BOMB_TOWER_LIFETIME);
    assertThat(unitStats.getTargetType()).isEqualTo(TargetType.GROUND);

    // Should have a projectile reference (BombTowerProjectile)
    ProjectileStats projectile = unitStats.getProjectile();
    assertThat(projectile).isNotNull();
    assertThat(projectile.getName()).isEqualTo("BombTowerProjectile");
    assertThat(projectile.isHoming()).isFalse();
    assertThat(projectile.isAoeToGround()).isTrue();
    assertThat(projectile.isAoeToAir()).isFalse();
    assertThat(projectile.getRadius()).isEqualTo(1.5f);

    // Should have a death spawn entry for BombTowerBomb
    assertThat(unitStats.getDeathSpawns()).hasSize(1);
    DeathSpawnEntry deathSpawn = unitStats.getDeathSpawns().get(0);
    assertThat(deathSpawn.stats().getName()).isEqualTo("BombTowerBomb");
    assertThat(deathSpawn.count()).isEqualTo(1);
  }

  @Test
  void bombTower_shouldAttackGroundEnemy() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(bombTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("GroundTarget", Team.RED).hp(5000).at(9, 13))
            .deployed()
            .build();

    Building tower = sim.building("BombTower");
    Troop target = sim.troop("GroundTarget");
    int initialHp = target.getHealth().getCurrent();

    // Let targeting acquire the enemy and attack (enough ticks for projectile travel)
    sim.tick(120);

    // Tower should have acquired a target and dealt damage via projectile
    assertThat(target.getHealth().getCurrent()).isLessThan(initialHp);
  }

  @Test
  void bombTower_shouldNotTargetAirUnits() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(bombTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.air("AirTarget", Team.RED).hp(5000).damage(0).speed(0f).at(9, 13))
            .deployed()
            .build();

    Building tower = sim.building("BombTower");
    Troop airTarget = sim.troop("AirTarget");

    // Run for a while -- tower should never acquire the air target
    sim.tick(120);

    // Air target should be untouched
    assertThat(airTarget.getHealth().getCurrent()).isEqualTo(5000);
    assertThat(tower.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void bombTower_shouldSpawnBombOnDeath() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(bombTower(Team.BLUE).at(9, 10).hp(1))
            .deployed()
            .build();

    Building tower = sim.building("BombTower");

    // Kill the tower by dealing enough damage
    tower.getHealth().takeDamage(10);

    // Tick 1: processDeaths triggers onDeath -> bomb goes to pendingSpawns
    // Tick 2: processPending moves bomb into entities list
    sim.tick(2);

    // BombTowerBomb should have been spawned
    List<Entity> allEntities = sim.gameState().getEntities();
    Entity bomb =
        allEntities.stream()
            .filter(e -> e.getName().equals("BombTowerBomb"))
            .findFirst()
            .orElse(null);

    assertThat(bomb).isNotNull();
    assertThat(bomb).isInstanceOf(Troop.class);

    // Bomb should have 1 HP (set to 1 so it survives deploy phase)
    assertThat(bomb.getHealth().getMax()).isEqualTo(1);

    // Bomb should have selfDestruct enabled
    assertThat(bomb.getSpawner()).isNotNull();
    assertThat(bomb.getSpawner().isSelfDestruct()).isTrue();
    assertThat(bomb.getSpawner().getDeathDamage()).isEqualTo(BOMB_DEATH_DAMAGE);
    assertThat(bomb.getSpawner().getDeathDamageRadius()).isEqualTo(BOMB_DEATH_DAMAGE_RADIUS);
  }

  @Test
  void bombTowerBomb_shouldExplodeAfterDeployAndDealAoeDamage() {
    // Full lifecycle: tower dies -> bomb spawns -> 3s fuse -> explosion hits nearby enemies
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(bombTower(Team.BLUE).at(9, 10).hp(1))
            // Ground enemy within bomb explosion radius (3.0 tiles)
            .spawn(TroopTemplate.target("NearGround", Team.RED).hp(500).at(10, 10))
            // Air enemy within bomb explosion radius (death damage hits ALL target types)
            .spawn(TroopTemplate.air("NearAir", Team.RED).hp(500).damage(0).speed(0f).at(9, 11))
            // Enemy far away -- outside explosion radius
            .spawn(TroopTemplate.target("FarEnemy", Team.RED).hp(500).at(20, 20))
            .deployed()
            .build();

    Building tower = sim.building("BombTower");
    Troop nearGround = sim.troop("NearGround");
    Troop nearAir = sim.troop("NearAir");
    Troop farEnemy = sim.troop("FarEnemy");

    // Kill the tower
    tower.getHealth().takeDamage(10);
    // Tick 1: processDeaths triggers onDeath -> bomb goes to pendingSpawns
    // Tick 2: processPending moves bomb into entities list
    sim.tick(2);

    // Bomb should exist and be deploying
    Entity bomb =
        sim.gameState().getEntities().stream()
            .filter(e -> e.getName().equals("BombTowerBomb"))
            .findFirst()
            .orElse(null);
    assertThat(bomb).isNotNull();
    assertThat(((Troop) bomb).isDeploying()).isTrue();

    // Record HP before explosion
    int nearGroundHpBefore = nearGround.getHealth().getCurrent();
    int nearAirHpBefore = nearAir.getHealth().getCurrent();
    int farEnemyHpBefore = farEnemy.getHealth().getCurrent();

    // Advance past the 3-second bomb fuse (deploy time)
    // 3.0s * 30 ticks/s = 90 ticks, plus a few extra to ensure processing
    sim.tick(95);

    // Bomb should have self-destructed and exploded
    assertThat(bomb.getHealth().isAlive()).isFalse();

    // Nearby ground enemy should have taken death damage
    assertThat(nearGround.getHealth().getCurrent()).isLessThan(nearGroundHpBefore);
    assertThat(nearGround.getHealth().getCurrent())
        .isEqualTo(nearGroundHpBefore - BOMB_DEATH_DAMAGE);

    // Nearby air enemy should ALSO have taken death damage (bomb targets ALL)
    assertThat(nearAir.getHealth().getCurrent()).isLessThan(nearAirHpBefore);
    assertThat(nearAir.getHealth().getCurrent()).isEqualTo(nearAirHpBefore - BOMB_DEATH_DAMAGE);

    // Far enemy should be untouched
    assertThat(farEnemy.getHealth().getCurrent()).isEqualTo(farEnemyHpBefore);
  }

  // -- Helper factories --

  /**
   * Creates a BuildingTemplate for a Bomb Tower with projectile combat and death spawn bomb. Uses
   * level 1 stats from units.json.
   */
  private BuildingTemplate bombTower(Team team) {
    ProjectileStats projectile =
        ProjectileStats.builder()
            .name("BombTowerProjectile")
            .damage(BOMB_TOWER_DAMAGE)
            .speed(500)
            .homing(false)
            .aoeToGround(true)
            .aoeToAir(false)
            .radius(1.5f)
            .build();

    Combat combat =
        Combat.builder()
            .damage(BOMB_TOWER_DAMAGE)
            .range(BOMB_TOWER_RANGE)
            .sightRange(BOMB_TOWER_RANGE)
            .attackCooldown(1.8f)
            .loadTime(1.3f)
            .accumulatedLoadTime(1.3f)
            .targetType(TargetType.GROUND)
            .projectileStats(projectile)
            .build();

    TroopStats bombStats = bombTowerBombStats();
    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .deathSpawns(List.of(new DeathSpawnEntry(bombStats, 1, 0f)))
            .build();

    return BuildingTemplate.defense("BombTower", team)
        .hp(BOMB_TOWER_HP)
        .lifetime(BOMB_TOWER_LIFETIME)
        .deployTime(1.0f)
        .combat(combat)
        .spawner(spawner);
  }

  /** Creates TroopStats for the BombTowerBomb entity (health=0, deploy=3s, death damage AOE). */
  private TroopStats bombTowerBombStats() {
    return TroopStats.builder()
        .name("BombTowerBomb")
        .health(0)
        .deployTime(BOMB_DEPLOY_TIME)
        .deathDamage(BOMB_DEATH_DAMAGE)
        .deathDamageRadius(BOMB_DEATH_DAMAGE_RADIUS)
        .targetType(TargetType.ALL)
        .movementType(MovementType.GROUND)
        .build();
  }
}
