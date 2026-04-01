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

  // -- Two-phase target locking tests --

  @Test
  void unlocked_shouldRetargetToCloserEnemyWhileMoving() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop farEnemy = createDeployedTroop("Far Enemy", Team.RED, 14, 10);

    java.util.List<Entity> entities = new java.util.ArrayList<>(List.of(attacker, farEnemy));

    // Acquire initial target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(farEnemy);
    // Target is unlocked (not in attack range yet)
    assertThat(attacker.getCombat().isTargetLocked()).isFalse();

    // A closer enemy appears
    Troop nearEnemy = createDeployedTroop("Near Enemy", Team.RED, 12, 10);
    entities.add(nearEnemy);

    // Should retarget to the closer enemy because target is unlocked
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget())
        .as("Unlocked troop should retarget to closer enemy")
        .isEqualTo(nearEnemy);
  }

  @Test
  void locked_shouldKeepTargetDespiteCloserEnemy() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop originalEnemy = createDeployedTroop("Original Enemy", Team.RED, 14, 10);

    java.util.List<Entity> entities = new java.util.ArrayList<>(List.of(attacker, originalEnemy));

    // Acquire initial target
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(originalEnemy);

    // Simulate entering attack range -- lock the target (normally done by CombatSystem)
    attacker.getCombat().setTargetLocked(true);

    // A closer enemy appears
    Troop closerEnemy = createDeployedTroop("Closer Enemy", Team.RED, 12, 10);
    entities.add(closerEnemy);

    // Should keep original target because it is locked
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget())
        .as("Locked troop should keep current target despite closer enemy")
        .isEqualTo(originalEnemy);
  }

  @Test
  void locked_shouldUnlockAndRescanWhenTargetDies() {
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop enemy1 = createDeployedTroop("Enemy1", Team.RED, 12, 10);
    Troop enemy2 = createDeployedTroop("Enemy2", Team.RED, 14, 10);

    List<Entity> entities = List.of(attacker, enemy1, enemy2);

    // Acquire target (enemy1 is closer) and lock it
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy1);
    attacker.getCombat().setTargetLocked(true);

    // Kill the locked target
    enemy1.getHealth().takeDamage(10000);
    enemy1.markDead();

    // Should unlock, rescan, and acquire enemy2
    targetingSystem.updateTargets(entities);
    assertThat(attacker.getCombat().isTargetLocked())
        .as("Target lock should be released after locked target dies")
        .isFalse();
    assertThat(attacker.getCombat().getCurrentTarget())
        .as("Should retarget to remaining enemy after locked target dies")
        .isEqualTo(enemy2);
  }

  // -- Minimum range (blind spot) tests for Mortar-like buildings --

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

  // -- TargetSelectAlgorithm tests --

  /**
   * Creates a deployed troop with a specific TargetSelectAlgorithm on its Combat component. Uses
   * large sight range (15.0) so algorithm selection is the deciding factor, not range limits.
   */
  private Troop createDeployedTroopWithAlgorithm(
      String name, Team team, float x, float y, TargetSelectAlgorithm algorithm) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(team)
            .position(new Position(x, y))
            .deployTime(0)
            .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(15.0f)
                    .range(1.0f)
                    .targetSelectAlgorithm(algorithm)
                    .build())
            .build();
    troop.onSpawn();
    return troop;
  }

  /**
   * Creates a deployed enemy troop with specific HP and damage values. Useful for testing HP-based
   * and damage-based targeting algorithms.
   */
  private Troop createEnemyWithHpAndDamage(String name, float x, float y, int hp, int damage) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.RED)
            .position(new Position(x, y))
            .deployTime(0)
            .health(new Health(hp))
            .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().damage(damage).sightRange(5.5f).build())
            .build();
    troop.onSpawn();
    return troop;
  }

  @Test
  void defaultAlgorithm_isNearest() {
    // No algorithm set explicitly -> default NEAREST -> picks closest enemy
    Troop attacker = createDeployedTroop("Attacker", Team.BLUE, 10, 10);
    Troop farEnemy = createDeployedTroop("Far", Team.RED, 15, 10);
    Troop nearEnemy = createDeployedTroop("Near", Team.RED, 12, 10);

    assertThat(attacker.getCombat().getTargetSelectAlgorithm())
        .isEqualTo(TargetSelectAlgorithm.NEAREST);

    targetingSystem.updateTargets(List.of(attacker, farEnemy, nearEnemy));
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(nearEnemy);
  }

  @Test
  void farthest_shouldTargetFurthestEnemy() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 10, 10, TargetSelectAlgorithm.FARTHEST);
    Troop nearEnemy = createDeployedTroop("Near", Team.RED, 12, 10);
    Troop farEnemy = createDeployedTroop("Far", Team.RED, 15, 10);
    Troop midEnemy = createDeployedTroop("Mid", Team.RED, 14, 10);

    targetingSystem.updateTargets(List.of(attacker, nearEnemy, farEnemy, midEnemy));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("FARTHEST should pick the enemy furthest away")
        .isEqualTo(farEnemy);
  }

  @Test
  void farthest_respectsSightRange() {
    // Attacker with FARTHEST but only 5.5 sight range (from default helper)
    Troop attacker =
        Troop.builder()
            .name("Attacker")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .deployTime(0)
            .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .sightRange(5.5f)
                    .range(1.0f)
                    .targetSelectAlgorithm(TargetSelectAlgorithm.FARTHEST)
                    .build())
            .build();
    attacker.onSpawn();

    Troop inRangeEnemy = createDeployedTroop("InRange", Team.RED, 14, 10);
    Troop outOfRangeEnemy = createDeployedTroop("OutOfRange", Team.RED, 20, 10);

    targetingSystem.updateTargets(List.of(attacker, inRangeEnemy, outOfRangeEnemy));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("FARTHEST should only consider enemies within sight range")
        .isEqualTo(inRangeEnemy);
  }

  @Test
  void lowestHp_shouldTargetLowestHpEnemy() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 10, 10, TargetSelectAlgorithm.LOWEST_HP);
    Troop highHp = createEnemyWithHpAndDamage("HighHP", 12, 10, 1000, 50);
    Troop lowHp = createEnemyWithHpAndDamage("LowHP", 14, 10, 200, 50);
    Troop midHp = createEnemyWithHpAndDamage("MidHP", 13, 10, 500, 50);

    targetingSystem.updateTargets(List.of(attacker, highHp, lowHp, midHp));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("LOWEST_HP should pick the enemy with the least current HP")
        .isEqualTo(lowHp);
  }

  @Test
  void highestHp_shouldTargetHighestHpEnemy() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 10, 10, TargetSelectAlgorithm.HIGHEST_HP);
    Troop highHp = createEnemyWithHpAndDamage("HighHP", 14, 10, 1000, 50);
    Troop lowHp = createEnemyWithHpAndDamage("LowHP", 12, 10, 200, 50);

    targetingSystem.updateTargets(List.of(attacker, highHp, lowHp));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("HIGHEST_HP should pick the enemy with the most current HP")
        .isEqualTo(highHp);
  }

  @Test
  void lowestHpRatio_shouldTargetMostDamagedEnemy() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 10, 10, TargetSelectAlgorithm.LOWEST_HP_RATIO);
    // Both start at full HP but with different max values
    Troop fullHp = createEnemyWithHpAndDamage("Full", 12, 10, 1000, 50);
    Troop damagedEnemy = createEnemyWithHpAndDamage("Damaged", 14, 10, 1000, 50);
    // Damage the second enemy to 30% HP
    damagedEnemy.getHealth().takeDamage(700);

    targetingSystem.updateTargets(List.of(attacker, fullHp, damagedEnemy));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("LOWEST_HP_RATIO should pick the enemy with the lowest HP percentage")
        .isEqualTo(damagedEnemy);
  }

  @Test
  void highestAd_shouldTargetHighestDamageEnemy() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 10, 10, TargetSelectAlgorithm.HIGHEST_AD);
    Troop lowDmg = createEnemyWithHpAndDamage("LowDmg", 12, 10, 500, 50);
    Troop highDmg = createEnemyWithHpAndDamage("HighDmg", 14, 10, 500, 300);
    Troop midDmg = createEnemyWithHpAndDamage("MidDmg", 13, 10, 500, 150);

    targetingSystem.updateTargets(List.of(attacker, lowDmg, highDmg, midDmg));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("HIGHEST_AD should pick the enemy with the most attack damage")
        .isEqualTo(highDmg);
  }

  @Test
  void random_shouldTargetSomeValidEnemy() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 10, 10, TargetSelectAlgorithm.RANDOM);
    Troop enemy1 = createDeployedTroop("E1", Team.RED, 12, 10);
    Troop enemy2 = createDeployedTroop("E2", Team.RED, 14, 10);
    Troop enemy3 = createDeployedTroop("E3", Team.RED, 13, 10);

    List<Entity> entities = List.of(attacker, enemy1, enemy2, enemy3);
    targetingSystem.updateTargets(entities);

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("RANDOM should pick one of the valid enemy candidates")
        .isIn(enemy1, enemy2, enemy3);
  }

  @Test
  void crowdest_shouldTargetClusteredArea() {
    Troop attacker =
        createDeployedTroopWithAlgorithm(
            "Attacker", Team.BLUE, 5, 10, TargetSelectAlgorithm.CROWDEST);

    // Cluster of 3 enemies close together (within 2-tile radius of each other)
    Troop cluster1 = createDeployedTroop("C1", Team.RED, 12, 10);
    Troop cluster2 = createDeployedTroop("C2", Team.RED, 12.5f, 10.5f);
    Troop cluster3 = createDeployedTroop("C3", Team.RED, 12, 11);

    // Isolated enemy far from the cluster
    Troop isolated = createDeployedTroop("Isolated", Team.RED, 18, 10);

    targetingSystem.updateTargets(List.of(attacker, cluster1, cluster2, cluster3, isolated));

    assertThat(attacker.getCombat().getCurrentTarget())
        .as("CROWDEST should pick a target in the dense cluster, not the isolated one")
        .isIn(cluster1, cluster2, cluster3);
  }

  @Test
  void buildingPull_preservedWithNonNearestAlgorithm() {
    // A building-targeting troop with FARTHEST algorithm should still use building pull
    // (always rescans), which means placing a closer building diverts it
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
                    .targetSelectAlgorithm(TargetSelectAlgorithm.FARTHEST)
                    .build())
            .build();
    giant.onSpawn();

    Tower nearTower = Tower.createPrincessTower(Team.RED, 10, 10, 1);
    nearTower.onSpawn();
    Tower farTower = Tower.createPrincessTower(Team.RED, 16, 10, 1);
    farTower.onSpawn();

    java.util.List<Entity> entities =
        new java.util.ArrayList<>(List.of(giant, nearTower, farTower));
    targetingSystem.updateTargets(entities);

    // With FARTHEST algorithm, should pick the far tower
    assertThat(giant.getCombat().getCurrentTarget())
        .as("Building pull with FARTHEST should pick farthest building")
        .isEqualTo(farTower);

    // Place a closer building -- building pull always rescans
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

    targetingSystem.updateTargets(entities);

    // Should still pick farthest building (far tower), proving building pull rescans
    // but the FARTHEST algorithm picks the farthest one
    assertThat(giant.getCombat().getCurrentTarget())
        .as("Building pull should rescan with FARTHEST, picking farthest building")
        .isEqualTo(farTower);
  }
}
