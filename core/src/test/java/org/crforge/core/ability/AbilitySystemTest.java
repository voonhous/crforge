package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.effect.StatusEffectSystem;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbilitySystemTest {

  private GameState gameState;
  private AbilitySystem abilitySystem;
  private CombatSystem combatSystem;
  private TargetingSystem targetingSystem;
  private PhysicsSystem physicsSystem;
  private StatusEffectSystem statusEffectSystem;

  private static final float DT = 1.0f / 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    abilitySystem = new AbilitySystem(gameState);
    combatSystem = new CombatSystem(gameState);
    targetingSystem = new TargetingSystem();
    physicsSystem = new PhysicsSystem(new Arena("Test Arena"));
    statusEffectSystem = new StatusEffectSystem();
  }

  // -- CHARGE tests --

  @Test
  void charge_shouldBuildUpWhileMoving() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);

    gameState.spawnEntity(prince);
    gameState.processPending();

    // Finish deploy
    prince.update(2.0f);

    // No target needed -- charge builds while unit is moving (speed > 0)

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

    gameState.spawnEntity(prince);
    gameState.processPending();

    prince.update(2.0f);

    // Run for 2.5s (charge time threshold) -- no target needed
    for (int i = 0; i < 76; i++) { // 76 ticks = 2.533s
      abilitySystem.update(DT);
    }

    AbilityComponent ability = prince.getAbility();
    assertThat(ability.isCharged()).isTrue();

    // Speed multiplier should be applied
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);
  }

  @Test
  void charge_shouldNotResetOnTargetLoss() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 15, 5);

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    prince.update(2.0f);
    target.update(2.0f);

    // Build up charge while moving (no target needed)
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Set and then lose target -- charge should persist (unit is still moving)
    prince.getCombat().setCurrentTarget(target);
    prince.getCombat().clearTarget();
    abilitySystem.update(DT);

    AbilityComponent ability = prince.getAbility();
    assertThat(ability.isCharged()).isTrue();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);
  }

  @Test
  void charge_shouldResetWhenMovementStopsBeforeFullyCharged() {
    // Stationary troop (speed=0) should not build charge
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);

    gameState.spawnEntity(prince);
    gameState.processPending();

    prince.update(2.0f);

    // Build partial charge (1s)
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().getChargeTimer()).isGreaterThan(0f);

    // Simulate stopped movement (e.g. stun zeroes effective speed)
    prince.getMovement().setSpeedMultiplier(
        ModifierSource.STATUS_EFFECT, 0f);
    abilitySystem.update(DT);

    // Charge should be lost -- must restart from zero
    AbilityComponent ability = prince.getAbility();
    assertThat(ability.getChargeTimer()).isEqualTo(0f);
    assertThat(ability.isCharged()).isFalse();
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

    // Build up charge while moving (no target needed)
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Now acquire target for the attack
    prince.getCombat().setCurrentTarget(target);

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

    // Do NOT charge -- attack immediately
    prince.getCombat().setCurrentTarget(target);
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

  // -- DASH tests --

  @Test
  void dash_shouldTriggerWhenTargetInRangeWindow() {
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    // Target at distance ~5.0 (within [3.5, 6.0] dash window)
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();

    bandit.update(2.0f);
    target.update(2.0f);

    bandit.getCombat().setCurrentTarget(target);

    // Tick to trigger dash
    abilitySystem.update(DT);

    AbilityComponent ability = bandit.getAbility();
    assertThat(ability.getDashState()).isEqualTo(AbilityComponent.DashState.DASHING);
    assertThat(bandit.isInvulnerable()).isTrue();
  }

  @Test
  void dash_shouldDealDamageOnArrival() {
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();

    bandit.update(2.0f);
    target.update(2.0f);

    bandit.getCombat().setCurrentTarget(target);

    // Run enough ticks for bandit to arrive (dash speed is 15 tiles/s, 5 tiles distance)
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }

    // Should have landed and dealt 152 damage
    assertThat(target.getHealth().getCurrent()).isEqualTo(1000 - 152);
    // Immunity should be off after landing
    assertThat(bandit.isInvulnerable()).isFalse();
  }

  @Test
  void dash_shouldNotTriggerWhenTargetTooClose() {
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    // Target at ~1.5 distance, below minRange 3.5
    Troop target = createDummyTarget(Team.RED, 6.5f, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();

    bandit.update(2.0f);
    target.update(2.0f);

    bandit.getCombat().setCurrentTarget(target);
    abilitySystem.update(DT);

    assertThat(bandit.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.IDLE);
  }

  // -- HOOK tests --

  @Test
  void hook_shouldTriggerAndPullTarget() {
    Troop fisher = createHookTroop(Team.BLUE, 5, 5);
    // Target at distance 5.0 (within [3.5, 7.0] hook window)
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(fisher);
    gameState.spawnEntity(target);
    gameState.processPending();

    fisher.update(2.0f);
    target.update(2.0f);

    fisher.getCombat().setCurrentTarget(target);

    // First tick -> WINDING_UP
    abilitySystem.update(DT);
    assertThat(fisher.getAbility().getHookState())
        .isEqualTo(AbilityComponent.HookState.WINDING_UP);

    // Wait for load time (1.3s)
    for (int i = 0; i < 40; i++) { // ~1.33s
      abilitySystem.update(DT);
    }

    // Should be PULLING now
    assertThat(fisher.getAbility().getHookState())
        .isEqualTo(AbilityComponent.HookState.PULLING);

    // Target should start moving toward the fisherman
    float initialTargetX = target.getPosition().getX();

    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }

    // Target should have moved closer to the fisherman
    assertThat(target.getPosition().getX()).isLessThan(initialTargetX);
  }

  @Test
  void hook_fishermanShouldWalkThenStopAndWindUpBeforeHookingBuilding() {
    // Fisherman starts 10 tiles from building. We simulate walking by manually
    // advancing position 1 tile/s toward the building each tick (speed 1.0 tile/s).
    // After ~3s the building enters hook range and the hook triggers.
    // Then we verify the Fisherman stays put for the 1.3s wind-up.
    Troop fisher = createHookTroop(Team.BLUE, 9, 5);
    Building cannon = Building.builder()
        .name("Cannon")
        .team(Team.RED)
        .position(new Position(9, 15))
        .health(new Health(500))
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
        .lifetime(30f)
        .remainingLifetime(30f)
        .deployTime(0f)
        .build();

    gameState.spawnEntity(fisher);
    gameState.spawnEntity(cannon);
    gameState.processPending();

    fisher.update(2.0f);
    cannon.update(2.0f);

    float speed = 1.0f; // tile/s, matching Fisherman's speed
    float startY = fisher.getPosition().getY();

    // Phase 1: Simulate walking toward building until hook triggers (max 5s)
    int hookTriggeredAtTick = -1;
    for (int i = 0; i < 150; i++) {
      // Simulate walking: move Fisherman toward building if hook hasn't triggered
      if (fisher.getAbility().getHookState() == AbilityComponent.HookState.IDLE) {
        fisher.getPosition().add(0, speed * DT);
      }

      targetingSystem.updateTargets(gameState.getAliveEntities());
      abilitySystem.update(DT);

      if (fisher.getAbility().getHookState() != AbilityComponent.HookState.IDLE
          && hookTriggeredAtTick == -1) {
        hookTriggeredAtTick = i;
        break;
      }
    }

    // Fisherman should have walked forward before hook triggered
    assertThat(fisher.getPosition().getY())
        .as("Fisherman should have walked toward the building")
        .isGreaterThan(startY);

    // Hook should have triggered
    assertThat(hookTriggeredAtTick)
        .as("Hook should have triggered within 5 seconds")
        .isGreaterThanOrEqualTo(0);

    // Phase 2: Hook just triggered -- verify Fisherman stays put during 1.3s wind-up
    float yAtHookTrigger = fisher.getPosition().getY();

    assertThat(fisher.getAbility().getHookState())
        .isEqualTo(AbilityComponent.HookState.WINDING_UP);

    // Run for 1 second (30 ticks) -- still within 1.3s wind-up
    for (int i = 0; i < 30; i++) {
      targetingSystem.updateTargets(gameState.getAliveEntities());
      abilitySystem.update(DT);
    }

    // Should still be winding up (1.0s < 1.3s)
    assertThat(fisher.getAbility().getHookState())
        .as("Should still be winding up after 1s")
        .isEqualTo(AbilityComponent.HookState.WINDING_UP);

    // Position must not have changed during wind-up
    assertThat(fisher.getPosition().getY())
        .as("Fisherman should not move during wind-up")
        .isEqualTo(yAtHookTrigger);

    // Building should never have moved
    assertThat(cannon.getPosition().getX()).isEqualTo(9f);
    assertThat(cannon.getPosition().getY()).isEqualTo(15f);
  }

  @Test
  void hook_shouldStopCompletelyWhenWindUpIsExtremelyLong() {
    // Use an absurdly long hookLoadTime so the Fisherman never exits WINDING_UP.
    // Run the full system loop (targeting + ability + physics) to verify that
    // PhysicsSystem respects movementDisabled and the Fisherman truly stops.
    AbilityData hookData = AbilityData.builder()
        .type(AbilityType.HOOK)
        .hookRange(7.0f)
        .hookMinimumRange(3.5f)
        .hookLoadTime(9999f)
        .hookDragBackSpeed(850f)
        .hookDragSelfSpeed(450f)
        .build();

    Troop fisher = Troop.builder()
        .name("Fisherman")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(900))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(80)
            .range(1.2f)
            .sightRange(7.5f)
            .attackCooldown(1.3f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(hookData))
        .build();

    // Target at distance 5.0 (within [3.5, 7.0] hook window)
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(fisher);
    gameState.spawnEntity(target);
    gameState.processPending();

    fisher.update(2.0f);
    target.update(2.0f);

    // Assign target and trigger hook
    fisher.getCombat().setCurrentTarget(target);
    abilitySystem.update(DT);

    assertThat(fisher.getAbility().getHookState())
        .isEqualTo(AbilityComponent.HookState.WINDING_UP);

    float startX = fisher.getPosition().getX();
    float startY = fisher.getPosition().getY();

    // Run for 10 seconds (300 ticks) matching real GameEngine tick order:
    // StatusEffectSystem (resets flags) -> targeting -> ability -> physics
    for (int i = 0; i < 300; i++) {
      statusEffectSystem.update(gameState, DT);
      targetingSystem.updateTargets(gameState.getAliveEntities());
      abilitySystem.update(DT);
      physicsSystem.update(gameState.getAliveEntities(), DT);
    }

    assertThat(fisher.getAbility().getHookState())
        .as("Should still be winding up after 10s (hookLoadTime=9999)")
        .isEqualTo(AbilityComponent.HookState.WINDING_UP);

    assertThat(fisher.getPosition().getX())
        .as("Fisherman X should not change during wind-up")
        .isEqualTo(startX);
    assertThat(fisher.getPosition().getY())
        .as("Fisherman Y should not change during wind-up")
        .isEqualTo(startY);

    // Movement and combat should be disabled the entire time
    assertThat(fisher.getMovement().canMove())
        .as("Movement should be disabled during wind-up")
        .isFalse();
    assertThat(fisher.getCombat().canAttack())
        .as("Combat should be disabled during wind-up")
        .isFalse();

    // Target should not have moved either (not yet in PULLING)
    assertThat(target.getPosition().getX()).isEqualTo(10f);
    assertThat(target.getPosition().getY()).isEqualTo(5f);
  }

  @Test
  void hook_shouldDragSelfToBuildingInsteadOfPulling() {
    // Place Fisherman 5 tiles from a building (within hook range [3.5, 7.0])
    Troop fisher = createHookTroop(Team.BLUE, 5, 5);
    Building cannon = Building.builder()
        .name("Cannon")
        .team(Team.RED)
        .position(new Position(10, 5))
        .health(new Health(500))
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
        .lifetime(30f)
        .remainingLifetime(30f)
        .deployTime(0f)
        .build();

    gameState.spawnEntity(fisher);
    gameState.spawnEntity(cannon);
    gameState.processPending();

    fisher.update(2.0f);
    cannon.update(2.0f);

    // Manually set target and trigger hook
    fisher.getCombat().setCurrentTarget(cannon);
    abilitySystem.update(DT); // -> WINDING_UP

    // Advance past wind-up (1.3s = 39 ticks)
    for (int i = 0; i < 40; i++) {
      abilitySystem.update(DT);
    }

    // Should skip PULLING and go straight to DRAGGING_SELF for buildings
    assertThat(fisher.getAbility().getHookState())
        .as("Should skip PULLING for buildings and go to DRAGGING_SELF")
        .isEqualTo(AbilityComponent.HookState.DRAGGING_SELF);

    // Building should not have moved at all
    assertThat(cannon.getPosition().getX()).isEqualTo(10f);
    assertThat(cannon.getPosition().getY()).isEqualTo(5f);

    // Run a few more ticks -- Fisherman should be moving toward the building
    float fisherXBefore = fisher.getPosition().getX();
    for (int i = 0; i < 10; i++) {
      abilitySystem.update(DT);
    }
    assertThat(fisher.getPosition().getX())
        .as("Fisherman should move toward the building")
        .isGreaterThan(fisherXBefore);
  }

  // -- REFLECT tests --

  @Test
  void reflect_shouldDealCounterDamageOnMeleeHit() {
    Troop eGiant = createReflectTroop(Team.BLUE, 5, 5);
    Troop attacker = Troop.builder()
        .name("Attacker")
        .team(Team.RED)
        .position(new Position(5.5f, 5))
        .health(new Health(500))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(100)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .build())
        .deployTime(0f)
        .build();

    gameState.spawnEntity(eGiant);
    gameState.spawnEntity(attacker);
    gameState.processPending();

    eGiant.update(2.0f);

    // Attacker hits eGiant with melee
    attacker.getCombat().setCurrentTarget(eGiant);
    attacker.getCombat().startAttackSequence();
    attacker.getCombat().setCurrentWindup(0);

    combatSystem.update(DT);

    // eGiant should take 100 damage from the attack
    assertThat(eGiant.getHealth().getCurrent()).isEqualTo(3000 - 100);

    // Attacker should take 75 reflect damage
    assertThat(attacker.getHealth().getCurrent()).isEqualTo(500 - 75);
  }

  @Test
  void reflect_shouldNotTriggerOnRangedAttack() {
    Troop eGiant = createReflectTroop(Team.BLUE, 5, 5);
    Troop rangedAttacker = Troop.builder()
        .name("Ranged")
        .team(Team.RED)
        .position(new Position(10, 5))
        .health(new Health(500))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(100)
            .range(6.0f)
            .sightRange(6.0f)
            .attackCooldown(1.0f)
            .build())
        .deployTime(0f)
        .build();

    gameState.spawnEntity(eGiant);
    gameState.spawnEntity(rangedAttacker);
    gameState.processPending();

    eGiant.update(2.0f);

    // Ranged attacker fires a projectile (no direct melee hit)
    rangedAttacker.getCombat().setCurrentTarget(eGiant);
    rangedAttacker.getCombat().startAttackSequence();
    rangedAttacker.getCombat().setCurrentWindup(0);

    combatSystem.update(DT);

    // A projectile is spawned, but reflect doesn't trigger on ranged
    // Attacker should NOT take reflect damage
    assertThat(rangedAttacker.getHealth().getCurrent()).isEqualTo(500);
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

  private Troop createDashTroop(Team team, float x, float y) {
    AbilityData dashData = AbilityData.builder()
        .type(AbilityType.DASH)
        .dashDamage(152)
        .dashMinRange(3.5f)
        .dashMaxRange(6.0f)
        .dashCooldown(0.8f)
        .dashImmuneTime(0.1f)
        .dashLandingTime(0.2f)
        .build();

    return Troop.builder()
        .name("Bandit")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(750))
        .movement(new Movement(2.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(80)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(dashData))
        .build();
  }

  private Troop createHookTroop(Team team, float x, float y) {
    AbilityData hookData = AbilityData.builder()
        .type(AbilityType.HOOK)
        .hookRange(7.0f)
        .hookMinimumRange(3.5f)
        .hookLoadTime(1.3f)
        .hookDragBackSpeed(850f)
        .hookDragSelfSpeed(450f)
        .build();

    return Troop.builder()
        .name("Fisherman")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(900))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(80)
            .range(1.2f)
            .sightRange(7.5f)
            .attackCooldown(1.3f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(hookData))
        .build();
  }

  private Troop createReflectTroop(Team team, float x, float y) {
    AbilityData reflectData = AbilityData.builder()
        .type(AbilityType.REFLECT)
        .reflectDamage(75)
        .reflectRadius(2.0f)
        .reflectBuff(org.crforge.core.effect.StatusEffectType.STUN)
        .reflectBuffDuration(0.5f)
        .reflectCrownTowerDamagePercent(50)
        .build();

    return Troop.builder()
        .name("ElectroGiant")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(3000))
        .movement(new Movement(0.8f, 8f, 1.0f, 1.0f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(0) // ElectroGiant doesn't deal damage directly, reflect does
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(reflectData))
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
