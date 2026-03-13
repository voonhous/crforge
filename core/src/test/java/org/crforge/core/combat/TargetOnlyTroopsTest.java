package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetOnlyTroopsTest {

  private TargetingSystem targetingSystem;

  @BeforeEach
  void setUp() {
    targetingSystem = new TargetingSystem();
  }

  private Troop createAttacker(Team team, float x, float y, boolean targetOnlyTroops) {
    return createAttacker(team, x, y, targetOnlyTroops, null);
  }

  private Troop createAttacker(
      Team team, float x, float y, boolean targetOnlyTroops, String ignoreTargetsWithBuff) {
    Troop troop =
        Troop.builder()
            .name("Attacker")
            .team(team)
            .position(new Position(x, y))
            .health(new Health(500))
            .movement(new Movement(1.0f, 4.0f, 0.3f, 0.3f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(50)
                    .range(5.0f)
                    .sightRange(7.0f)
                    .targetType(TargetType.ALL)
                    .targetOnlyTroops(targetOnlyTroops)
                    .ignoreTargetsWithBuff(ignoreTargetsWithBuff)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    troop.onSpawn();
    return troop;
  }

  private Troop createEnemyTroop(Team team, float x, float y) {
    Troop troop =
        Troop.builder()
            .name("EnemyTroop")
            .team(team)
            .position(new Position(x, y))
            .health(new Health(300))
            .movement(new Movement(1.0f, 4.0f, 0.3f, 0.3f, MovementType.GROUND))
            .combat(Combat.builder().damage(30).range(1.0f).targetType(TargetType.GROUND).build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    troop.onSpawn();
    return troop;
  }

  private Building createEnemyBuilding(Team team, float x, float y) {
    Building building =
        Building.builder()
            .name("EnemyBuilding")
            .team(team)
            .position(new Position(x, y))
            .health(new Health(1000))
            .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.BUILDING))
            .combat(Combat.builder().damage(50).range(5.0f).targetType(TargetType.GROUND).build())
            .lifetime(30f)
            .remainingLifetime(30f)
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    building.onSpawn();
    return building;
  }

  @Test
  void targetOnlyTroops_skipsBuildingsAndTargetsTroops() {
    Troop attacker = createAttacker(Team.BLUE, 5f, 5f, true);
    Troop enemyTroop = createEnemyTroop(Team.RED, 6f, 5f);
    Building enemyBuilding = createEnemyBuilding(Team.RED, 4f, 5f);

    List<Entity> entities = List.of(attacker, enemyTroop, enemyBuilding);
    targetingSystem.updateTargets(entities);

    // Should target the troop, not the building
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemyTroop);
  }

  @Test
  void targetOnlyTroops_false_canTargetBuildings() {
    Troop attacker = createAttacker(Team.BLUE, 5f, 5f, false);
    Building enemyBuilding = createEnemyBuilding(Team.RED, 6f, 5f);

    List<Entity> entities = List.of(attacker, enemyBuilding);
    targetingSystem.updateTargets(entities);

    // Should target the building
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemyBuilding);
  }

  @Test
  void ignoreTargetsWithBuff_skipsTargetsWithMatchingBuff() {
    Troop attacker = createAttacker(Team.BLUE, 5f, 5f, false, "BolaSnare");
    Troop snaredEnemy = createEnemyTroop(Team.RED, 6f, 5f);
    Troop freeEnemy = createEnemyTroop(Team.RED, 7f, 5f);

    // Apply BolaSnare debuff to the first enemy
    snaredEnemy.addEffect(new AppliedEffect(StatusEffectType.SLOW, 5f, "BolaSnare"));

    List<Entity> entities = List.of(attacker, snaredEnemy, freeEnemy);
    targetingSystem.updateTargets(entities);

    // Should skip the snared enemy and target the free one
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(freeEnemy);
  }

  @Test
  void ignoreTargetsWithBuff_targetsEnemiesWithoutMatchingBuff() {
    Troop attacker = createAttacker(Team.BLUE, 5f, 5f, false, "BolaSnare");
    Troop enemy = createEnemyTroop(Team.RED, 6f, 5f);
    // Enemy has a different buff
    enemy.addEffect(new AppliedEffect(StatusEffectType.SLOW, 5f, "IceWizardSlowDown"));

    List<Entity> entities = List.of(attacker, enemy);
    targetingSystem.updateTargets(entities);

    // Should target the enemy (different buff name)
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(enemy);
  }

  @Test
  void ignoreTargetsWithBuff_null_targetsAll() {
    Troop attacker = createAttacker(Team.BLUE, 5f, 5f, false, null);
    Troop snaredEnemy = createEnemyTroop(Team.RED, 6f, 5f);
    snaredEnemy.addEffect(new AppliedEffect(StatusEffectType.SLOW, 5f, "BolaSnare"));

    List<Entity> entities = List.of(attacker, snaredEnemy);
    targetingSystem.updateTargets(entities);

    // With null ignoreTargetsWithBuff, should target normally
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(snaredEnemy);
  }
}
