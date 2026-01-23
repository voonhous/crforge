package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.*;
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
            .position(x, y)
            .deployTime(0)
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

    assertThat(attacker.getCurrentTarget()).isEqualTo(nearEnemy);
  }

  @Test
  void troop_shouldNotTargetAllies() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop ally = createDeployedTroop("Ally", Team.BLUE, 11, 10);
    Troop enemy = createDeployedTroop("Enemy", Team.RED, 12, 10);

    List<Entity> entities = List.of(attacker, ally, enemy);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCurrentTarget()).isEqualTo(enemy);
  }

  @Test
  void troop_shouldNotTargetOutOfRange() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop farEnemy =
        createDeployedTroop("Far Enemy", Team.RED, 20, 10); // 10 units away, sight range is 5.5

    List<Entity> entities = List.of(attacker, farEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCurrentTarget()).isNull();
  }

  @Test
  void groundTroop_shouldNotTargetAirUnits() {
    Troop groundAttacker =
        Troop.builder()
            .name("Ground Attacker")
            .team(Team.BLUE)
            .position(10, 10)
            .deployTime(0)
            .movementType(MovementType.GROUND)
            .combat(Combat.builder().sightRange(5.5f).targetType(TargetType.GROUND).build())
            .build();
    groundAttacker.onSpawn();

    Troop airEnemy =
        Troop.builder()
            .name("Air Enemy")
            .team(Team.RED)
            .position(12, 10)
            .deployTime(0)
            .movementType(MovementType.AIR)
            .build();
    airEnemy.onSpawn();

    List<Entity> entities = List.of(groundAttacker, airEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(groundAttacker.getCurrentTarget()).isNull();
  }

  @Test
  void airTroop_shouldTargetAirUnits() {
    Troop airAttacker =
        Troop.builder()
            .name("Air Attacker")
            .team(Team.BLUE)
            .position(10, 10)
            .deployTime(0)
            .movementType(MovementType.AIR)
            .combat(Combat.builder().sightRange(5.5f).targetType(TargetType.AIR).build())
            .build();
    airAttacker.onSpawn();

    Troop airEnemy =
        Troop.builder()
            .name("Air Enemy")
            .team(Team.RED)
            .position(12, 10)
            .deployTime(0)
            .movementType(MovementType.AIR)
            .build();
    airEnemy.onSpawn();

    List<Entity> entities = List.of(airAttacker, airEnemy);
    targetingSystem.updateTargets(entities);

    assertThat(airAttacker.getCurrentTarget()).isEqualTo(airEnemy);
  }

  @Test
  void troop_shouldKeepValidTarget() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop enemy = createDeployedTroop("Enemy", Team.RED, 12, 10);

    List<Entity> entities = List.of(attacker, enemy);

    // First update - acquire target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCurrentTarget()).isEqualTo(enemy);

    // Second update - should keep same target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCurrentTarget()).isEqualTo(enemy);
  }

  @Test
  void troop_shouldRetargetWhenTargetDies() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop enemy1 = createDeployedTroop("Enemy1", Team.RED, 12, 10);
    Troop enemy2 = createDeployedTroop("Enemy2", Team.RED, 14, 10);

    List<Entity> entities = List.of(attacker, enemy1, enemy2);

    // First update - acquire target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCurrentTarget()).isEqualTo(enemy1);

    // Kill enemy1
    enemy1.getHealth().takeDamage(10000);
    enemy1.markDead();

    // Update - should retarget to enemy2
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCurrentTarget()).isEqualTo(enemy2);
  }
}
