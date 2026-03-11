package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.data.card.CardRegistry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
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
    Troop farEnemy =
        createDeployedTroop("Far Enemy", Team.RED, 20, 10);

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
    Troop knight = Troop.builder()
        .name("Knight")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .deployTime(0)
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .sightRange(5.5f)
            .targetType(knightStats.getTargetType()) // Use actual stat
            .build())
        .build();
    knight.onSpawn();

    Troop dragon = Troop.builder()
        .name("Baby Dragon")
        .team(Team.RED)
        .position(new Position(12, 10))
        .deployTime(0)
        .movement(new Movement(0, 0, 0.5f, 0.5f, dragonStats.getMovementType())) // Use actual stat
        .build();
    dragon.onSpawn();

    List<Entity> entities = List.of(knight, dragon);
    targetingSystem.updateTargets(entities);

    assertThat(knight.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void targetOnlyBuildings_shouldIgnoreTroops() {
    // Giant-like unit: targets only buildings
    Troop giant = Troop.builder()
        .name("Giant")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .deployTime(0)
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
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
    Troop giant = Troop.builder()
        .name("Giant")
        .team(Team.BLUE)
        .position(new Position(10, 10))
        .deployTime(0)
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
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
  void targetOnlyBuildings_shouldPreferCloserBuilding() {
    Troop giant = Troop.builder()
        .name("Giant")
        .team(Team.BLUE)
        .position(new Position(5, 10))
        .deployTime(0)
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
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
}
