package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetingSystemTest {

  private TargetingSystem targetingSystem;

  @BeforeEach
  void setUp() {
    targetingSystem = new TargetingSystem();
    AbstractEntity.resetIdCounter();
  }

  private Troop createDeployedTroop(String name, Team team, float x, float y) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(team)
            .position(new Position(x, y))
            .deployTime(0)
            .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().sightRange(5.5f).range(1.0f).build())
            .build();
    troop.onSpawn();
    return troop;
  }

  @Test
  void troop_shouldTargetNearestEnemy() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop farEnemy = createDeployedTroop("Far Enemy", Team.RED, 15, 10);
    Troop nearEnemy = createDeployedTroop("Near Enemy", Team.RED, 12, 10);

    List<Entity> entities = List.of(attacker, farEnemy, nearEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(nearEnemy);
  }

  @Test
  void troop_shouldNotTargetAllies() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop ally = createDeployedTroop("Ally", Team.BLUE, 11, 10);
    Troop enemy = createDeployedTroop("Enemy", Team.RED, 12, 10);

    List<Entity> entities = List.of(attacker, ally, enemy);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy);
  }

  @Test
  void troop_shouldNotTargetOutOfRange() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop farEnemy = createDeployedTroop("Far Enemy", Team.RED, 20, 10);

    List<Entity> entities = List.of(attacker, farEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void groundTroop_shouldNotTargetAirUnits() {
    Troop groundAttacker =
        Troop.builder()
            .name("Ground Attacker")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().sightRange(5.5f).targetType(TargetType.GROUND).build())
            .build();
    groundAttacker.onSpawn();

    Troop airEnemy =
        Troop.builder()
            .name("Air Enemy")
            .team(Team.RED)
            .position(new Position(12, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.AIR))
            .build();
    airEnemy.onSpawn();

    List<Entity> entities = List.of(groundAttacker, airEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(groundAttacker.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void airTroop_shouldTargetAirUnits() {
    Troop airAttacker =
        Troop.builder()
            .name("Air Attacker")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.AIR))
            .combat(Combat.builder().sightRange(5.5f).targetType(TargetType.AIR).build())
            .build();
    airAttacker.onSpawn();

    Troop airEnemy =
        Troop.builder()
            .name("Air Enemy")
            .team(Team.RED)
            .position(new Position(12, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.AIR))
            .build();
    airEnemy.onSpawn();

    List<Entity> entities = List.of(airAttacker, airEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(airAttacker.getCombat().getCurrentTarget()).isEqualTo(airEnemy);
  }

  @Test
  void troop_shouldKeepValidTarget() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop enemy = createDeployedTroop("Enemy", Team.RED, 12, 10);

    List<Entity> entities = List.of(attacker, enemy);

    // First update - acquire target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy);

    // Second update - should keep same target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy);
  }

  @Test
  void troop_shouldRetargetWhenTargetDies() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop enemy1 = createDeployedTroop("Enemy1", Team.RED, 12, 10);
    Troop enemy2 = createDeployedTroop("Enemy2", Team.RED, 14, 10);

    List<Entity> entities = List.of(attacker, enemy1, enemy2);

    // First update - acquire target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy1);

    // Kill enemy1
    enemy1.getHealth().takeDamage(10000);
    enemy1.markDead();

    // Update - should retarget to enemy2
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy2);
  }

  @Test
  void knightShouldIgnoreBabyDragon() {
    // Verify registry config
    TroopStats knightStats = CardRegistry.get("knight").getUnitStats();
    TroopStats dragonStats = CardRegistry.get("babydragon").getUnitStats();

    assertThat(knightStats.getTargetType()).isEqualTo(TargetType.GROUND);
    assertThat(dragonStats.getMovementType()).isEqualTo(MovementType.AIR);

    // Verify system interaction
    Troop knight =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(5.5f)
                    .targetType(knightStats.getTargetType()) // Use actual stat
                    .build())
            .build();
    knight.onSpawn();

    Troop dragon =
        Troop.builder()
            .name("Baby Dragon")
            .team(Team.RED)
            .position(new Position(12, 10))
            .deployTime(0)
            .movement(
                new Movement(0, 0, 0.5f, 0.5f, dragonStats.getMovementType())) // Use actual stat
            .build();
    dragon.onSpawn();

    List<Entity> entities = List.of(knight, dragon);
    targetingSystem.updateTargets(entities);

    assertThat(knight.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void targetOnlyBuildings_shouldIgnoreTroops() {
    // Giant-like unit: targets only buildings
    Troop giant =
        Troop.builder()
            .name("Giant")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(5.5f)
                    .targetType(TargetType.GROUND)
                    .targetOnlyBuildings(true)
                    .build())
            .build();
    giant.onSpawn();

    Troop enemyKnight = createDeployedTroop("Knight", Team.RED, 12, 10);

    List<Entity> entities = List.of(giant, enemyKnight);
    targetingSystem.updateTargets(entities);

    // Giant should ignore Knight (not a building)
    assertThat(giant.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void targetOnlyBuildings_shouldTargetTower() {
    Troop giant =
        Troop.builder()
            .name("Giant")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(9.0f)
                    .targetType(TargetType.GROUND)
                    .targetOnlyBuildings(true)
                    .build())
            .build();
    giant.onSpawn();

    Tower tower = Tower.createPrincessTower(Team.RED, 14, 10, 1);
    tower.onSpawn();

    List<Entity> entities = List.of(giant, tower);
    targetingSystem.updateTargets(entities);

    // Giant should target the tower
    assertThat(giant.getCombat().getCurrentTarget()).isEqualTo(tower);
  }

  @Test
  void troop_shouldAcquireTargetWithinEdgeToEdgeSightRange() {
    // Attacker at (10,10) with collisionRadius=0.5, sightRange=5.5
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    // Target at (16,10) with collisionRadius=0.5 -> center distance = 6.0
    // Center-to-center: 6.0 > sightRange 5.5 -- would MISS with old logic
    // Edge-to-edge: 6.0 - 0.5 - 0.5 = 5.0 <= sightRange 5.5 -- should HIT
    Troop enemy = createDeployedTroop("Enemy", Team.RED, 16, 10);

    List<Entity> entities = List.of(attacker, enemy);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("Target should be acquired when within edge-to-edge sight range")
        .isEqualTo(enemy);
  }

  @Test
  void targetOnlyBuildings_shouldPreferCloserBuilding() {
    Troop giant =
        Troop.builder()
            .name("Giant")
            .team(Team.BLUE)
            .position(new Position(5, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(15.0f)
                    .targetType(TargetType.GROUND)
                    .targetOnlyBuildings(true)
                    .build())
            .build();
    giant.onSpawn();

    // A troop that is closer should be ignored
    Troop enemyKnight = createDeployedTroop("Knight", Team.RED, 6, 10);

    Tower farTower = Tower.createPrincessTower(Team.RED, 14, 10, 1);
    farTower.onSpawn();

    List<Entity> entities = List.of(giant, enemyKnight, farTower);
    targetingSystem.updateTargets(entities);

    // Giant skips the Knight, targets the Tower even though it's further
    assertThat(giant.getCombat().getCurrentTarget()).isEqualTo(farTower);
  }

  // -- Building pull: targetOnlyBuildings retargets to closer building --

  @Test
  void targetOnlyBuildings_retargetsToCloserBuilding() {
    // Giant-like troop with targetOnlyBuildings=true, large sight range
    Troop giant =
        Troop.builder()
            .name("Giant")
            .team(Team.BLUE)
            .position(new Position(5, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(15.0f)
                    .targetType(TargetType.GROUND)
                    .targetOnlyBuildings(true)
                    .build())
            .build();
    giant.onSpawn();

    // Far tower is the only building initially
    Tower farTower = Tower.createPrincessTower(Team.RED, 14, 10, 1);
    farTower.onSpawn();

    List<Entity> entities = new java.util.ArrayList<>(List.of(giant, farTower));
    targetingSystem.updateTargets(entities);
    assertThat(giant.getCombat().getCurrentTarget())
        .as("Should initially target the only building (far tower)")
        .isEqualTo(farTower);

    // A closer building appears (e.g. Tesla just revealed, Cannon placed)
    Building cannon =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(8, 10))
            .health(new Health(500))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    cannon.onSpawn();
    entities.add(cannon);

    // After targeting update, troop should retarget to the closer building
    targetingSystem.updateTargets(entities);
    assertThat(giant.getCombat().getCurrentTarget())
        .as("Should retarget to the closer building (cannon)")
        .isEqualTo(cannon);
  }

  @Test
  void targetOnlyBuildings_keepsAttackStateWhenTargetUnchanged() {
    // Giant-like troop targeting a tower, with isAttacking=true
    Troop giant =
        Troop.builder()
            .name("Giant")
            .team(Team.BLUE)
            .position(new Position(5, 10))
            .deployTime(0)
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(15.0f)
                    .targetType(TargetType.GROUND)
                    .targetOnlyBuildings(true)
                    .build())
            .build();
    giant.onSpawn();

    Tower tower = Tower.createPrincessTower(Team.RED, 14, 10, 1);
    tower.onSpawn();

    List<Entity> entities = List.of(giant, tower);
    targetingSystem.updateTargets(entities);
    assertThat(giant.getCombat().getCurrentTarget()).isEqualTo(tower);

    // Simulate that the giant is mid-attack
    giant.getCombat().setAttacking(true);

    // Run targeting again -- no closer building exists
    targetingSystem.updateTargets(entities);

    // isAttacking should still be true (setCurrentTarget identity guard prevents reset)
    assertThat(giant.getCombat().isAttacking())
        .as("Attack state should be preserved when target is unchanged")
        .isTrue();
    assertThat(giant.getCombat().getCurrentTarget()).isEqualTo(tower);
  }

  // -- Minimum range (blind spot) tests for Mortar-like buildings --

  /**
   * Creates a Mortar-like building with a minimum range blind spot. Uses minimumRange=3.5,
   * sightRange=11.5, range=11.5, targets GROUND only. Deploy is instant (deployTime=0,
   * deployTimer=0) so the building can target immediately in tests.
   */
  private Building createMortarLikeBuilding(Team team, float x, float y) {
    Building mortar =
        Building.builder()
            .name("Mortar")
            .team(team)
            .position(new Position(x, y))
            .health(new Health(1000))
            .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(11.5f)
                    .range(11.5f)
                    .minimumRange(3.5f)
                    .targetType(TargetType.GROUND)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    mortar.onSpawn();
    return mortar;
  }

  @Test
  void minimumRange_shouldNotAcquireTargetInBlindSpot() {
    // Mortar at (9,16), enemy at (9,18) -> 2 tiles apart, inside 3.5-tile blind spot
    Building mortar = createMortarLikeBuilding(Team.BLUE, 9, 16);
    Troop enemy = createDeployedTroop("Knight", Team.RED, 9, 18);

    List<Entity> entities = List.of(mortar, enemy);
    targetingSystem.updateTargets(entities);

    assertThat(mortar.getCombat().getCurrentTarget())
        .as("Mortar should not acquire target inside minimum range blind spot")
        .isNull();
  }

  @Test
  void minimumRange_shouldAcquireTargetOutsideBlindSpot() {
    // Mortar at (9,16), close enemy at (9,18) inside blind spot, far enemy at (9,24) outside
    Building mortar = createMortarLikeBuilding(Team.BLUE, 9, 16);
    Troop closeEnemy = createDeployedTroop("Close Knight", Team.RED, 9, 18);
    Troop farEnemy = createDeployedTroop("Far Knight", Team.RED, 9, 24);

    List<Entity> entities = List.of(mortar, closeEnemy, farEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(mortar.getCombat().getCurrentTarget())
        .as("Mortar should skip blind-spot enemy and target the far one")
        .isEqualTo(farEnemy);
  }

  @Test
  void minimumRange_shouldDropTargetThatEntersBlindSpot() {
    // Mortar at (9,16), enemy starts at (9,24) -- outside blind spot
    Building mortar = createMortarLikeBuilding(Team.BLUE, 9, 16);
    Troop enemy = createDeployedTroop("Knight", Team.RED, 9, 24);

    List<Entity> entities = List.of(mortar, enemy);
    targetingSystem.updateTargets(entities);

    assertThat(mortar.getCombat().getCurrentTarget())
        .as("Mortar should initially acquire far target")
        .isEqualTo(enemy);

    // Enemy moves inside the blind spot
    enemy.getPosition().set(9, 18);
    targetingSystem.updateTargets(entities);

    assertThat(mortar.getCombat().getCurrentTarget())
        .as("Mortar should drop target that entered blind spot")
        .isNull();
  }
}
