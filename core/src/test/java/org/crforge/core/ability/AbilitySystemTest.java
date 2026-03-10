package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectSystem;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.projectile.Projectile;
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
  private AreaEffectSystem areaEffectSystem;

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
    areaEffectSystem = new AreaEffectSystem(gameState);
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
  void charge_shouldDealDamageInstantlySkippingWindup() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5); // Close enough for melee

    gameState.spawnEntity(prince);
    gameState.spawnEntity(target);
    gameState.processPending();

    prince.update(2.0f);
    target.update(2.0f);

    // Build up charge
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Acquire target -- do NOT manually skip windup
    prince.getCombat().setCurrentTarget(target);

    // A single combat tick should deal charge damage instantly (no windup wait)
    combatSystem.update(DT);

    assertThat(target.getHealth().getCurrent())
        .as("Charge damage should be dealt on first tick, skipping windup")
        .isEqualTo(1000 - 306);

    // Charge should be consumed
    assertThat(prince.getAbility().isCharged()).isFalse();
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

  // -- STUN/FREEZE reset charge tests --
  // These tests verify that stun-on-hit resets charge state on the target,
  // through both the melee (applyBuffOnDamage) and ranged (applyTargetBuff) paths.

  @Test
  void meleeStunOnHit_shouldResetPartialCharge() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop ewiz = createStunOnHitAttacker(Team.RED, 6, 5, 1.5f); // melee range

    gameState.spawnEntity(prince);
    gameState.spawnEntity(ewiz);
    gameState.processPending();

    prince.update(2.0f);
    ewiz.update(2.0f);

    // Build partial charge (1s out of 2.5s needed)
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().getChargeTimer()).isGreaterThan(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();

    // EWiz melee attacks Prince -- buffOnDamage applies STUN
    ewiz.getCombat().setCurrentTarget(prince);
    ewiz.getCombat().startAttackSequence();
    ewiz.getCombat().setCurrentWindup(0);
    combatSystem.update(DT);

    // Charge should be fully reset by the stun
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();
  }

  @Test
  void meleeStunOnHit_shouldResetFullCharge() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop ewiz = createStunOnHitAttacker(Team.RED, 6, 5, 1.5f); // melee range

    gameState.spawnEntity(prince);
    gameState.spawnEntity(ewiz);
    gameState.processPending();

    prince.update(2.0f);
    ewiz.update(2.0f);

    // Build full charge (2.5s+)
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);

    // EWiz melee attacks Prince -- buffOnDamage applies STUN
    ewiz.getCombat().setCurrentTarget(prince);
    ewiz.getCombat().startAttackSequence();
    ewiz.getCombat().setCurrentWindup(0);
    combatSystem.update(DT);

    // Charge and speed multiplier should be fully reset
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(1.0f);
  }

  @Test
  void rangedStunProjectile_shouldResetFullCharge() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);
    Troop ewiz = createStunOnHitAttacker(Team.RED, 10, 5, 5.0f); // ranged

    gameState.spawnEntity(prince);
    gameState.spawnEntity(ewiz);
    gameState.processPending();

    prince.update(2.0f);
    ewiz.update(2.0f);

    // Build full charge (2.5s+)
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);

    // Create a stun projectile about to hit the Prince (simulates EWiz ranged attack)
    Projectile stunProjectile = new Projectile(
        ewiz, prince, 50, 0, 15f,
        List.of(EffectStats.builder()
            .type(StatusEffectType.STUN)
            .duration(0.5f)
            .buffName("ZapFreeze")
            .applyAfterDamage(true)
            .build()));
    // Place projectile at the target so it hits on next update
    stunProjectile.getPosition().set(
        prince.getPosition().getX(), prince.getPosition().getY());
    gameState.spawnProjectile(stunProjectile);

    // Process projectile hit through CombatSystem
    combatSystem.update(DT);

    // Charge and speed multiplier should be fully reset
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(1.0f);
  }

  // -- SPELL (AreaEffectSystem) stun/freeze reset charge tests --
  // These tests verify that spell-based stuns (Zap, Freeze) reset charge state,
  // going through AreaEffectSystem.applyBuff() rather than CombatSystem.applyEffects().

  @Test
  void spellStun_shouldResetFullCharge() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);

    gameState.spawnEntity(prince);
    gameState.processPending();

    prince.update(2.0f);

    // Build full charge (2.5s+)
    for (int i = 0; i < 78; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().isCharged()).isTrue();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);

    // Cast Zap on the Prince via AreaEffectSystem (one-shot stun spell)
    AreaEffectStats zapStats = AreaEffectStats.builder()
        .name("Zap")
        .radius(2.5f)
        .lifeDuration(0.1f)
        .buff("ZapFreeze")
        .buffDuration(0.5f)
        .damage(75)
        .build();
    AreaEffect zap = AreaEffect.builder()
        .name("Zap")
        .team(Team.RED)
        .position(new Position(5, 5))
        .health(new Health(1))
        .movement(new Movement(0, 0, 0, 0, MovementType.GROUND))
        .stats(zapStats)
        .remainingLifetime(0.1f)
        .build();
    gameState.spawnEntity(zap);
    gameState.processPending();

    // Process the area effect -- should stun the Prince and reset charge
    areaEffectSystem.update(DT);

    // Charge and speed multiplier should be fully reset
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(1.0f);
  }

  @Test
  void spellStun_shouldResetPartialCharge() {
    Troop prince = createChargeTroop(Team.BLUE, 5, 5);

    gameState.spawnEntity(prince);
    gameState.processPending();

    prince.update(2.0f);

    // Build partial charge (1s out of 2.5s needed)
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }
    assertThat(prince.getAbility().getChargeTimer()).isGreaterThan(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();

    // Cast Zap on the Prince via AreaEffectSystem
    AreaEffectStats zapStats = AreaEffectStats.builder()
        .name("Zap")
        .radius(2.5f)
        .lifeDuration(0.1f)
        .buff("ZapFreeze")
        .buffDuration(0.5f)
        .damage(75)
        .build();
    AreaEffect zap = AreaEffect.builder()
        .name("Zap")
        .team(Team.RED)
        .position(new Position(5, 5))
        .health(new Health(1))
        .movement(new Movement(0, 0, 0, 0, MovementType.GROUND))
        .stats(zapStats)
        .remainingLifetime(0.1f)
        .build();
    gameState.spawnEntity(zap);
    gameState.processPending();

    areaEffectSystem.update(DT);

    // Partial charge should be fully reset by the stun
    assertThat(prince.getAbility().getChargeTimer()).isEqualTo(0f);
    assertThat(prince.getAbility().isCharged()).isFalse();
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

  @Test
  void variableDamage_shouldResetOnMeleeStun() {
    Troop inferno = createVariableDamageTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 8, 5);
    Troop ewiz = createStunOnHitAttacker(Team.RED, 6, 5, 1.5f); // melee range

    gameState.spawnEntity(inferno);
    gameState.spawnEntity(target);
    gameState.spawnEntity(ewiz);
    gameState.processPending();

    inferno.update(2.0f);
    target.update(2.0f);
    ewiz.update(2.0f);

    // Lock on target and escalate to stage 1 (2s)
    inferno.getCombat().setCurrentTarget(target);
    for (int i = 0; i < 61; i++) { // just over 2s
      abilitySystem.update(DT);
    }
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(1);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(47);

    // EWiz melee attacks Inferno Dragon -- buffOnDamage applies STUN
    ewiz.getCombat().setCurrentTarget(inferno);
    ewiz.getCombat().startAttackSequence();
    ewiz.getCombat().setCurrentWindup(0);
    combatSystem.update(DT);

    // Variable damage should be fully reset to stage 0
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(inferno.getAbility().getStageTimer()).isEqualTo(0f);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(14);
  }

  @Test
  void variableDamage_shouldResetOnSpellFreeze() {
    Troop inferno = createVariableDamageTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 8, 5);

    gameState.spawnEntity(inferno);
    gameState.spawnEntity(target);
    gameState.processPending();

    inferno.update(2.0f);
    target.update(2.0f);

    // Lock on target and escalate to stage 2 (4s+)
    inferno.getCombat().setCurrentTarget(target);
    for (int i = 0; i < 130; i++) { // ~4.3s, past both stage transitions
      abilitySystem.update(DT);
    }
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(2);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(165);

    // Cast Freeze spell via AreaEffectSystem
    AreaEffectStats freezeStats = AreaEffectStats.builder()
        .name("Freeze")
        .radius(3.0f)
        .lifeDuration(0.1f)
        .buff("Freeze")
        .buffDuration(4.0f)
        .damage(0)
        .build();
    AreaEffect freeze = AreaEffect.builder()
        .name("Freeze")
        .team(Team.RED)
        .position(new Position(5, 5))
        .health(new Health(1))
        .movement(new Movement(0, 0, 0, 0, MovementType.GROUND))
        .stats(freezeStats)
        .remainingLifetime(0.1f)
        .build();
    gameState.spawnEntity(freeze);
    gameState.processPending();

    areaEffectSystem.update(DT);

    // Variable damage should be fully reset to stage 0
    assertThat(inferno.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(inferno.getAbility().getStageTimer()).isEqualTo(0f);
    assertThat(inferno.getAbility().getLastTargetId()).isEqualTo(-1);
    assertThat(inferno.getCombat().getDamageOverride()).isEqualTo(14);
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

    // Burn through initial cooldown (0.8s = 24 ticks), then trigger dash
    for (int i = 0; i < 25; i++) {
      abilitySystem.update(DT);
    }

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

    // Burn through initial cooldown (0.8s = 24 ticks) + enough ticks to arrive
    for (int i = 0; i < 60; i++) {
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

    // Burn through initial cooldown (0.8s = 24 ticks)
    for (int i = 0; i < 25; i++) {
      abilitySystem.update(DT);
    }

    // Should still be IDLE because target is too close (below minRange)
    assertThat(bandit.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.IDLE);
  }

  @Test
  void dash_shouldNotOrbitAroundBuilding() {
    // Bandit dashes toward a building -- should move in a straight line and stop
    // at the collision boundary, not orbit around it due to sliding physics.
    Troop bandit = createDashTroop(Team.BLUE, 9, 5);
    Building cannon = Building.builder()
        .name("Cannon")
        .team(Team.RED)
        .position(new Position(9, 12))
        .health(new Health(500))
        .movement(new Movement(0, 0, 1.0f, 1.0f, MovementType.BUILDING))
        .lifetime(30f)
        .remainingLifetime(30f)
        .deployTime(0f)
        .build();

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(cannon);
    gameState.processPending();

    bandit.update(2.0f);
    cannon.update(2.0f);

    // Manually set target and burn through initial cooldown, then trigger dash
    bandit.getCombat().setCurrentTarget(cannon);
    for (int i = 0; i < 25; i++) {
      abilitySystem.update(DT);
    }

    assertThat(bandit.getAbility().getDashState())
        .isEqualTo(AbilityComponent.DashState.DASHING);

    // Record starting X -- Bandit and Cannon share the same X coordinate,
    // so the Bandit should move straight up (Y only) with X unchanged.
    float startX = bandit.getPosition().getX();

    // Run full system loop (ability + physics) for enough ticks to complete the dash
    for (int i = 0; i < 60; i++) {
      abilitySystem.update(DT);
      physicsSystem.update(gameState.getAliveEntities(), DT);
    }

    // Bandit should NOT have drifted sideways (orbiting).
    // Allow a tiny epsilon for float imprecision.
    assertThat(bandit.getPosition().getX())
        .as("Bandit X should stay constant (no orbiting) during dash into building")
        .isCloseTo(startX, org.assertj.core.data.Offset.offset(0.01f));

    // Dash should have completed (landed or returned to idle)
    assertThat(bandit.getAbility().getDashState())
        .as("Dash should have completed")
        .isNotEqualTo(AbilityComponent.DashState.DASHING);

    // Cannon should have taken at least dash damage (may also take melee hits after landing)
    assertThat(cannon.getHealth().getCurrent()).isLessThanOrEqualTo(500 - 152);
  }

  @Test
  void dash_shouldHoldPositionWhileCooldownActiveAndTargetInRange() {
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    // Target in dash range [3.5, 6.0]
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();
    bandit.update(2.0f);
    target.update(2.0f);
    bandit.getCombat().setCurrentTarget(target);

    // Initial cooldown is active (0.8s). Tick a few times -- should hold position.
    float startX = bandit.getPosition().getX();
    for (int i = 0; i < 10; i++) {
      abilitySystem.update(DT);
    }

    assertThat(bandit.getAbility().getDashState())
        .as("Should still be IDLE during cooldown")
        .isEqualTo(AbilityComponent.DashState.IDLE);
    assertThat(bandit.getMovement().isMovementDisabled())
        .as("Movement should be disabled while waiting for cooldown")
        .isTrue();
    assertThat(bandit.getCombat().isCombatDisabled())
        .as("Combat should be disabled while waiting for cooldown")
        .isTrue();
    assertThat(bandit.getPosition().getX())
        .as("Should not have moved during cooldown hold")
        .isEqualTo(startX);
  }

  @Test
  void dash_cooldownResetsWhenTargetExceedsMaxRange() {
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    // Target in acquisition range [3.5, 6.0]
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();
    bandit.update(2.0f);
    target.update(2.0f);
    bandit.getCombat().setCurrentTarget(target);

    float initialCooldown = bandit.getAbility().getDashCooldownTimer();
    assertThat(initialCooldown).as("Initial cooldown should be 0.8s").isGreaterThan(0);

    // Tick 10 times with target in range -- cooldown should decrease
    for (int i = 0; i < 10; i++) {
      abilitySystem.update(DT);
    }

    assertThat(bandit.getAbility().getDashCooldownTimer())
        .as("Cooldown should tick down when target is in dash range")
        .isLessThan(initialCooldown);

    // Move target beyond maxRange -- cooldown should reset to full
    target.getPosition().set(15, 5);
    abilitySystem.update(DT);

    assertThat(bandit.getAbility().getDashCooldownTimer())
        .as("Cooldown should reset when target exceeds maxRange")
        .isEqualTo(initialCooldown);
  }

  @Test
  void dash_shouldStillFireWhenTargetMovedBelowMinRange() {
    // Target enters acquisition range [3.5, 6.0], then walks closer (below minRange).
    // Dash should still fire once cooldown expires -- target just needs to be within maxRange.
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    // Target at edge distance ~4.0, within acquisition range [3.5, 6.0]
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();
    bandit.update(2.0f);
    target.update(2.0f);
    bandit.getCombat().setCurrentTarget(target);

    // Tick a few times to partially consume cooldown and acquire candidate
    for (int i = 0; i < 5; i++) {
      abilitySystem.update(DT);
    }
    assertThat(bandit.getAbility().isDashCandidateAcquired()).isTrue();

    // Move target below minRange (edge distance ~2.0) but still within maxRange
    target.getPosition().set(8, 5);

    // Burn through remaining cooldown
    for (int i = 0; i < 25; i++) {
      abilitySystem.update(DT);
    }

    // Dash should have fired even though target is below minRange
    assertThat(bandit.getAbility().getDashState())
        .as("Should dash to target even though it moved below minRange")
        .isNotEqualTo(AbilityComponent.DashState.IDLE);
  }

  @Test
  void dash_shouldReleaseHoldWhenCooldownExpires() {
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();
    bandit.update(2.0f);
    target.update(2.0f);
    bandit.getCombat().setCurrentTarget(target);

    // Burn through cooldown (0.8s = 24 ticks) -- should dash on the tick it expires
    for (int i = 0; i < 25; i++) {
      abilitySystem.update(DT);
    }

    // Should have started dashing (hold released, dash triggered)
    assertThat(bandit.getAbility().getDashState())
        .isEqualTo(AbilityComponent.DashState.DASHING);
  }

  @Test
  void dash_constantTime_shouldTakeFixedDurationRegardlessOfDistance() {
    // MegaKnight-style: constantTime=0.8s -> 24 ticks at 30fps
    // Place troop and target ~4 tiles apart (edge-to-edge)
    Troop mk = createConstantTimeDashTroop(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(mk);
    gameState.spawnEntity(target);
    gameState.processPending();
    mk.update(2.0f);
    target.update(2.0f);

    mk.getCombat().setCurrentTarget(target);

    // Burn through initial cooldown (0.9s = 27 ticks), then trigger dash
    for (int i = 0; i < 28; i++) {
      abilitySystem.update(DT);
    }
    assertThat(mk.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.DASHING);

    // At constant speed (15 tiles/sec), a ~4-tile dash would finish in ~8 ticks.
    // With constantTime=0.8s, it should still be DASHING at tick 20.
    for (int i = 1; i < 20; i++) {
      abilitySystem.update(DT);
    }
    assertThat(mk.getAbility().getDashState())
        .as("Should still be dashing at tick 20 (0.8s flight = ~24 ticks)")
        .isEqualTo(AbilityComponent.DashState.DASHING);

    // By tick 26 it should have arrived and transitioned to LANDING
    for (int i = 20; i < 26; i++) {
      abilitySystem.update(DT);
    }
    assertThat(mk.getAbility().getDashState())
        .as("Should have landed by tick 26")
        .isNotEqualTo(AbilityComponent.DashState.DASHING);
  }

  @Test
  void dash_constantTime_differentDistancesSameFlightDuration() {
    // Two MK dashes at different distances should both take ~24 ticks (0.8s)

    // Dash 1: ~4 tiles apart
    Troop mk1 = createConstantTimeDashTroop(Team.BLUE, 5, 5);
    Troop target1 = createDummyTarget(Team.RED, 10, 5);
    gameState.spawnEntity(mk1);
    gameState.spawnEntity(target1);
    gameState.processPending();
    mk1.update(2.0f);
    target1.update(2.0f);
    mk1.getCombat().setCurrentTarget(target1);
    // Burn through initial cooldown (0.9s = 27 ticks)
    for (int i = 0; i < 28; i++) {
      abilitySystem.update(DT);
    }
    assertThat(mk1.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.DASHING);

    // Count ticks until dash 1 finishes
    int ticks1 = 1;
    for (int i = 0; i < 60; i++) {
      abilitySystem.update(DT);
      ticks1++;
      if (mk1.getAbility().getDashState() != AbilityComponent.DashState.DASHING) {
        break;
      }
    }

    // Dash 2: ~6 tiles apart (larger distance), separate GameState
    GameState gs2 = new GameState();
    AbilitySystem as2 = new AbilitySystem(gs2);

    Troop mk2 = createConstantTimeDashTroop(Team.BLUE, 5, 5);
    Troop target2 = createDummyTarget(Team.RED, 12, 5);
    gs2.spawnEntity(mk2);
    gs2.spawnEntity(target2);
    gs2.processPending();
    mk2.update(2.0f);
    target2.update(2.0f);
    mk2.getCombat().setCurrentTarget(target2);
    // Burn through initial cooldown (0.9s = 27 ticks)
    for (int i = 0; i < 28; i++) {
      as2.update(DT);
    }
    assertThat(mk2.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.DASHING);

    int ticks2 = 1;
    for (int i = 0; i < 60; i++) {
      as2.update(DT);
      ticks2++;
      if (mk2.getAbility().getDashState() != AbilityComponent.DashState.DASHING) {
        break;
      }
    }

    // Both dashes should complete in ~24 ticks (within 1 tick tolerance)
    assertThat(ticks1).as("Short dash flight ticks").isBetween(23, 25);
    assertThat(ticks2).as("Long dash flight ticks").isBetween(23, 25);
    assertThat(Math.abs(ticks1 - ticks2))
        .as("Both distances should take roughly the same number of ticks")
        .isLessThanOrEqualTo(1);
  }

  @Test
  void dash_banditWithoutConstantTime_usesConstantSpeed() {
    // Bandit has no constantTime -- closer targets should be reached faster
    Troop bandit = createDashTroop(Team.BLUE, 5, 5);
    // Target ~4 tiles away (edge-to-edge after collision radii)
    Troop target = createDummyTarget(Team.RED, 10, 5);

    gameState.spawnEntity(bandit);
    gameState.spawnEntity(target);
    gameState.processPending();
    bandit.update(2.0f);
    target.update(2.0f);
    bandit.getCombat().setCurrentTarget(target);

    // Burn through initial cooldown (0.8s = 24 ticks)
    for (int i = 0; i < 25; i++) {
      abilitySystem.update(DT);
    }
    assertThat(bandit.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.DASHING);

    // At 15 tiles/sec, a ~4-tile dash should finish well before 0.8s (24 ticks)
    int ticks = 1;
    for (int i = 0; i < 60; i++) {
      abilitySystem.update(DT);
      ticks++;
      if (bandit.getAbility().getDashState() != AbilityComponent.DashState.DASHING) {
        break;
      }
    }

    // Should complete much faster than 24 ticks (constantTime=0.8s equivalent)
    assertThat(ticks)
        .as("Bandit dash at constant speed should complete faster than MK constantTime flight")
        .isLessThan(15);
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
    AbilityData hookData = new HookAbility(7.0f, 3.5f, 9999f, 850f, 450f);

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
  void reflect_shouldTriggerOnRangedAttackWithinRadius() {
    Troop eGiant = createReflectTroop(Team.BLUE, 5, 5);
    // Place ranged attacker within reflect radius (2.0 tiles + collision radii)
    Troop rangedAttacker = Troop.builder()
        .name("Ranged")
        .team(Team.RED)
        .position(new Position(7, 5))
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

    // Create a projectile that is about to hit eGiant (simulates ranged attack)
    Projectile projectile = new Projectile(
        rangedAttacker, eGiant, 100, 0, 15f, List.of());
    // Place projectile at eGiant's position so it hits on next update
    projectile.getPosition().set(
        eGiant.getPosition().getX(), eGiant.getPosition().getY());
    gameState.spawnProjectile(projectile);

    combatSystem.update(DT);

    // eGiant should take 100 damage from the projectile
    assertThat(eGiant.getHealth().getCurrent()).isEqualTo(3000 - 100);

    // Attacker should take 75 reflect damage (within reflect radius)
    assertThat(rangedAttacker.getHealth().getCurrent()).isEqualTo(500 - 75);

    // Attacker should be stunned by the reflect buff (ZapFreeze)
    assertThat(rangedAttacker.getAppliedEffects())
        .anyMatch(e -> e.getType() == StatusEffectType.STUN);
  }

  @Test
  void reflect_shouldNotTriggerOnRangedAttackOutsideRadius() {
    Troop eGiant = createReflectTroop(Team.BLUE, 5, 5);
    // Place ranged attacker far outside reflect radius (2.0 tiles + collision radii)
    Troop rangedAttacker = Troop.builder()
        .name("Ranged")
        .team(Team.RED)
        .position(new Position(12, 5))
        .health(new Health(500))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(100)
            .range(8.0f)
            .sightRange(8.0f)
            .attackCooldown(1.0f)
            .build())
        .deployTime(0f)
        .build();

    gameState.spawnEntity(eGiant);
    gameState.spawnEntity(rangedAttacker);
    gameState.processPending();

    eGiant.update(2.0f);

    // Create a projectile that is about to hit eGiant
    Projectile projectile = new Projectile(
        rangedAttacker, eGiant, 100, 0, 15f, List.of());
    projectile.getPosition().set(
        eGiant.getPosition().getX(), eGiant.getPosition().getY());
    gameState.spawnProjectile(projectile);

    combatSystem.update(DT);

    // eGiant should take 100 damage from the projectile
    assertThat(eGiant.getHealth().getCurrent()).isEqualTo(3000 - 100);

    // Attacker should NOT take reflect damage (outside reflect radius)
    assertThat(rangedAttacker.getHealth().getCurrent()).isEqualTo(500);
  }

  // -- Helper methods --

  private Troop createChargeTroop(Team team, float x, float y) {
    AbilityData chargeData = new ChargeAbility(306, 2.0f);

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
    AbilityData varDmgData = new VariableDamageAbility(List.of(
        new VariableDamageStage(14, 0f),   // Stage 0: immediate
        new VariableDamageStage(47, 2.0f), // Stage 1: after 2s
        new VariableDamageStage(165, 2.0f) // Stage 2: after 2 more s
    ));

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
    AbilityData dashData = new DashAbility(152, 3.5f, 6.0f, 0f, 0.8f, 0.1f, 0.2f, 0f, 0f);

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

  private Troop createConstantTimeDashTroop(Team team, float x, float y) {
    AbilityData dashData = new DashAbility(480, 3.5f, 7.0f, 2.5f, 0.9f, 0.1f, 0.3f, 0.8f, 0f);

    return Troop.builder()
        .name("MegaKnight")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(4000))
        .movement(new Movement(1.5f, 8f, 1.0f, 1.0f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(222)
            .range(1.5f)
            .sightRange(7.5f)
            .attackCooldown(1.5f)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(dashData))
        .build();
  }

  private Troop createHookTroop(Team team, float x, float y) {
    AbilityData hookData = new HookAbility(7.0f, 3.5f, 1.3f, 850f, 450f);

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
    AbilityData reflectData = new ReflectAbility(
        75, 2.0f, org.crforge.core.effect.StatusEffectType.STUN, 0.5f, 50, null);

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

  /**
   * Creates an attacker with buffOnDamage STUN (simulates Electro Wizard).
   * Use range < 2.0 for melee path, >= 2.0 for ranged/projectile path.
   */
  private Troop createStunOnHitAttacker(Team team, float x, float y, float range) {
    EffectStats stunBuff = EffectStats.builder()
        .type(StatusEffectType.STUN)
        .duration(0.5f)
        .buffName("ZapFreeze")
        .build();

    return Troop.builder()
        .name("EWiz")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder()
            .damage(50)
            .range(range)
            .sightRange(7.5f)
            .attackCooldown(1.8f)
            .buffOnDamage(stunBuff)
            .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
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
