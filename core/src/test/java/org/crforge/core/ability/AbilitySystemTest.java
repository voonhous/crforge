package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbilitySystemTest {

  private GameState gameState;
  private AbilitySystem abilitySystem;
  private CombatSystem combatSystem;
  private TargetingSystem targetingSystem;

  private static final float DT = 1.0f / 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    abilitySystem = new AbilitySystem(gameState);
    combatSystem = new CombatSystem(gameState);
    targetingSystem = new TargetingSystem();
  }

  // -- CHARGE tests --

  @Test
  void charge_shouldBuildUpWhileMovingTowardTarget() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 15, 5);

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Finish deploy
    prince.update(2.0f);
    target.update(2.0f);

    // Set target (has target, not attacking -> charge should build)
    prince.getCombat().setCurrentTarget(target);

    // Run ability system for 1 second
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }

    // Should have accumulated ~1s of charge, not yet charged (needs 2.5s)
    AbilityComponent ability = prince.getAbility();
    assertThat(ability.getChargeTimer()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.05f));
    assertThat(ability.isCharged()).isFalse();
  }

  @Test
  void charge_shouldActivateAfterChargeTime() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 15, 5);

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    prince.update(2.0f);
    target.update(2.0f);

    prince.getCombat().setCurrentTarget(target);

    // Run for 2.5s (charge time threshold)
    for (int i = 0; i < 76; i++) { // 76 ticks = 2.533s
      abilitySystem.update(DT);
    }

    AbilityComponent ability = prince.getAbility();
    assertThat(ability.isCharged()).isTrue();

    // Speed multiplier should be applied
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);
  }

  @Test
  void charge_shouldResetOnTargetLoss() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 15, 5);

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    prince.update(2.0f);
    target.update(2.0f);

    prince.getCombat().setCurrentTarget(target);

    // Build up 2.6s of charge
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Lose target
    prince.getCombat().clearTarget();
    abilitySystem.update(DT);

    // Charge should be reset
    AbilityComponent ability = prince.getAbility();
    assertThat(ability.isCharged()).isFalse();
    assertThat(ability.getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(1.0f);
  }

  @Test
  void charge_shouldDealChargeDamageOnAttack() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5); // Close enough for melee

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    prince.update(2.0f);
    target.update(2.0f);

    prince.getCombat().setCurrentTarget(target);

    // Build up charge (2.6s)
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Verify getChargeDamage returns charge damage
    int chargeDmg = AbilitySystem.getChargeDamage(prince.getAbility(), 100);
    assertThat(chargeDmg).isEqualTo(306);

    // Trigger attack: set up windup=0 to execute immediately
    prince.getCombat().startAttackSequence();
    prince.getCombat().setCurrentWindup(0);
    combatSystem.update(DT);

    // Target should take charge damage (306) instead of base damage (100)
    assertThat(target.getHealth().getCurrent()).isEqualTo(1000 - 306);

    // Charge should be consumed
    assertThat(prince.getAbility().isCharged()).isFalse();
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(1.0f);
  }

  @Test
  void charge_shouldDealNormalDamageWhenNotCharged() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    prince.update(2.0f);
    target.update(2.0f);

    prince.getCombat().setCurrentTarget(target);

    // Do NOT charge -- attack immediately
    assertThat(prince.getAbility().isCharged()).isFalse();

    int normalDmg = AbilitySystem.getChargeDamage(prince.getAbility(), 100);
    assertThat(normalDmg).isEqualTo(100); // Returns base damage when not charged

    prince.getCombat().startAttackSequence();
    prince.getCombat().setCurrentWindup(0);
    combatSystem.update(DT);

    // Target should take base damage (100)
    assertThat(target.getHealth().getCurrent()).isEqualTo(1000 - 100);
  }

  @Test
  void charge_shouldNotBuildDuringDeploy() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 15, 5);

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Don't finish deploy -- prince is still deploying
    prince.getCombat().setCurrentTarget(target);

    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }

    // Charge should not build during deploy
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
  }

  // -- VARIABLE DAMAGE tests --

  @Test
  void variableDamage_shouldStartAtStage0() {
    Troop inferno = createVariableDamageTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 8, 5);

    gameState.spawnEntity(inferno);
    gameState.spawnEntity(target);
    gameState.processPending();

    inferno.update(2.0f);
    target.update(2.0f);

    inferno.getCombat().setCurrentTarget(target);
    abilitySystem.update(DT);

    // Should be at stage 0 with damage override of 14
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(14);
  }

  @Test
  void variableDamage_shouldEscalateThroughStages() {
    Troop inferno = createVariableDamageTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 8, 5);

    gameState.spawnEntity(inferno);
    gameState.spawnEntity(target);
    gameState.processPending();

    inferno.update(2.0f);
    target.update(2.0f);

    inferno.getCombat().setCurrentTarget(target);

    // Stage 0: immediate (14 damage)
    abilitySystem.update(DT);
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(14);

    // Run for 2 seconds -> should advance to stage 1 (47 damage)
    for (int i = 0; i < 60; i++) {
      abilitySystem.update(DT);
    }
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(1);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(47);

    // Run for 2 more seconds -> should advance to stage 2 (165 damage)
    for (int i = 0; i < 60; i++) {
      abilitySystem.update(DT);
    }
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(2);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(165);
  }

  @Test
  void variableDamage_shouldResetOnRetarget() {
    Troop inferno = createVariableDamageTroop(Team.BLUE, 5, 5);
    Troop target1 = createDummyTarget(Team.RED, 8, 5);
    Troop target2 = createDummyTarget(Team.RED, 9, 5);

    gameState.spawnEntity(inferno);
    gameState.spawnEntity(target1);
    gameState.spawnEntity(target2);
    gameState.processPending();

    inferno.update(2.0f);
    target1.update(2.0f);
    target2.update(2.0f);

    // Start on target1, escalate to stage 1
    inferno.getCombat().setCurrentTarget(target1);
    for (int i = 0; i < 61; i++) { // ~2s
      abilitySystem.update(DT);
    }
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(1);

    // Switch to target2 -> should reset to stage 0
    inferno.getCombat().setCurrentTarget(target2);
    abilitySystem.update(DT);

    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(14);
  }

  @Test
  void variableDamage_shouldStayAtMaxStage() {
    Troop inferno = createVariableDamageTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 8, 5);

    gameState.spawnEntity(inferno);
    gameState.spawnEntity(target);
    gameState.processPending();

    inferno.update(2.0f);
    target.update(2.0f);

    inferno.getCombat().setCurrentTarget(target);

    // Run for 6 seconds (well past all stage transitions)
    for (int i = 0; i < 180; i++) {
      abilitySystem.update(DT);
    }

    // Should be at max stage (2) with 165 damage
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(2);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(165);
  }

  // -- Helper methods --

  private Troop createChargeTroop(Team team, float x, float y) {
    AbilityData chargeData = AbilityData.builder()
        .type(AbilityType.CHARGE)
        .chargeDamage(306)
        .speedMultiplier(2.0f)
        .build();

    return Troop.builder()
        .name("Prince")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(1.5f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(100)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.4f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(chargeData))
        .build();
  }

  private Troop createVariableDamageTroop(Team team, float x, float y) {
    AbilityData varDmgData = AbilityData.builder()
        .type(AbilityType.VARIABLE_DAMAGE)
        .stages(List.of(
            new VariableDamageStage(14, 0f),   // Stage 0: immediate
            new VariableDamageStage(47, 2.0f), // Stage 1: after 2s
            new VariableDamageStage(165, 2.0f) // Stage 2: after 2 more s
        ))
        .build();

    return Troop.builder()
        .name("InfernoDragon")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(800))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.AIR))
        .combat(Combat.builder()
            .damage(14) // base damage, overridden by ability
            .range(4.0f)
            .sightRange(5.5f)
            .attackCooldown(0.4f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(varDmgData))
        .build();
  }

  private Troop createDummyTarget(Team team, float x, float y) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }
}
