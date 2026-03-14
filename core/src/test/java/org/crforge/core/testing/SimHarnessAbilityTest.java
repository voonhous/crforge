package org.crforge.core.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.ability.DashAbility;
import org.crforge.core.ability.HookAbility;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proof-of-concept: hook and modifier-source tests rewritten with SimHarness. Validates both the
 * SimHarness API and the ModifierSource system.
 */
class SimHarnessAbilityTest {

  private Map<String, BuffDefinition> savedBuffs;

  @BeforeEach
  void setUp() {
    // Register a test Rage buff (140 -> 1.4x speed/hit speed)
    savedBuffs = BuffRegistry.snapshot();
    BuffRegistry.clear();
    BuffRegistry.register(
        "TestRage",
        BuffDefinition.builder()
            .name("TestRage")
            .speedMultiplier(140)
            .hitSpeedMultiplier(140)
            .build());
  }

  @AfterEach
  void tearDown() {
    BuffRegistry.restore(savedBuffs);
  }

  private static AbilityData hookData() {
    return new HookAbility(7.0f, 3.5f, 1.3f, 850f, 450f);
  }

  private static AbilityData longWindupHookData() {
    return new HookAbility(7.0f, 3.5f, 9999f, 850f, 450f);
  }

  private static AbilityData chargeData() {
    return new ChargeAbility(306, 2.0f);
  }

  private static AbilityData dashData() {
    return new DashAbility(152, 3.5f, 6.0f, 0f, 0.8f, 0.1f, 0.2f, 0f, 0f);
  }

  // -- Hook tests via SimHarness --

  @Test
  void hook_shouldTriggerAndPullTarget() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(
                TroopTemplate.melee("Fisherman", Team.BLUE)
                    .at(5, 5)
                    .hp(900)
                    .damage(80)
                    .range(1.2f)
                    .sightRange(7.5f)
                    .cooldown(1.3f)
                    .ability(hookData()))
            .spawn(TroopTemplate.target("Target", Team.RED).at(10, 5))
            .deployed()
            .build();

    Troop fisher = sim.troop("Fisherman");
    Troop target = sim.troop("Target");

    fisher.getCombat().setCurrentTarget(target);

    // First tick -> WINDING_UP
    sim.tick();
    assertThat(fisher.getAbility().getHookState()).isEqualTo(AbilityComponent.HookState.WINDING_UP);

    // Wait for load time (1.3s = ~40 ticks)
    sim.tick(40);
    assertThat(fisher.getAbility().getHookState()).isEqualTo(AbilityComponent.HookState.PULLING);

    // Pull target toward fisherman
    float initialTargetX = target.getPosition().getX();
    sim.tick(30);
    assertThat(target.getPosition().getX()).isLessThan(initialTargetX);
  }

  @Test
  void hook_movementDisabledSurvivesStatusEffectReset() {
    // This is the core test that validates ModifierSource: StatusEffectSystem
    // runs every tick and used to trample ability flags. Now it should leave
    // ABILITY_HOOK source untouched.
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(
                TroopTemplate.melee("Fisherman", Team.BLUE)
                    .at(5, 5)
                    .hp(900)
                    .damage(80)
                    .range(1.2f)
                    .sightRange(7.5f)
                    .cooldown(1.3f)
                    .ability(longWindupHookData()))
            .spawn(TroopTemplate.target("Target", Team.RED).at(10, 5))
            .deployed()
            .build();

    Troop fisher = sim.troop("Fisherman");
    Troop target = sim.troop("Target");

    fisher.getCombat().setCurrentTarget(target);

    // Trigger hook
    sim.tick();
    assertThat(fisher.getAbility().getHookState()).isEqualTo(AbilityComponent.HookState.WINDING_UP);

    float startX = fisher.getPosition().getX();
    float startY = fisher.getPosition().getY();

    // Run for 10 seconds with ALL systems (including StatusEffectSystem and Physics)
    sim.tick(300);

    // Should still be winding up (hookLoadTime=9999)
    assertThat(fisher.getAbility().getHookState())
        .as("Should still be winding up after 10s")
        .isEqualTo(AbilityComponent.HookState.WINDING_UP);

    // Movement must remain disabled despite StatusEffectSystem resetting every tick
    assertThat(fisher.getMovement().canMove())
        .as("Movement should remain disabled (ABILITY_HOOK source survives SES reset)")
        .isFalse();
    assertThat(fisher.getCombat().canAttack())
        .as("Combat should remain disabled (ABILITY_HOOK source survives SES reset)")
        .isFalse();

    // Fisherman should not have moved at all
    assertThat(fisher.getPosition().getX()).isEqualTo(startX);
    assertThat(fisher.getPosition().getY()).isEqualTo(startY);
  }

  @Test
  void hook_shouldDragSelfToBuilding() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(
                TroopTemplate.melee("Fisherman", Team.BLUE)
                    .at(5, 5)
                    .hp(900)
                    .damage(80)
                    .range(1.2f)
                    .sightRange(7.5f)
                    .cooldown(1.3f)
                    .ability(hookData()))
            .spawn(BuildingTemplate.defense("Cannon", Team.RED).at(10, 5))
            .deployed()
            .build();

    Troop fisher = sim.troop("Fisherman");

    fisher.getCombat().setCurrentTarget(sim.building("Cannon"));

    // Trigger hook + wind up past 1.3s
    sim.tick(42);

    // Should skip PULLING and go straight to DRAGGING_SELF for buildings
    assertThat(fisher.getAbility().getHookState())
        .isEqualTo(AbilityComponent.HookState.DRAGGING_SELF);

    // Building should not have moved
    assertThat(sim.building("Cannon").getPosition().getX()).isEqualTo(10f);

    // Fisherman should be moving toward the building
    float fisherXBefore = fisher.getPosition().getX();
    sim.tick(10);
    assertThat(fisher.getPosition().getX()).isGreaterThan(fisherXBefore);
  }

  // -- Dash combat disabled survives StatusEffectSystem reset --

  @Test
  void dash_combatDisabledSurvivesStatusEffectReset() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(
                TroopTemplate.melee("Bandit", Team.BLUE)
                    .at(5, 5)
                    .hp(750)
                    .damage(80)
                    .speed(2.0f)
                    .sightRange(5.5f)
                    .cooldown(1.0f)
                    .ability(dashData()))
            .spawn(TroopTemplate.target("Target", Team.RED).at(12, 5))
            .deployed()
            .build();

    Troop bandit = sim.troop("Bandit");
    bandit.getCombat().setCurrentTarget(sim.troop("Target"));

    // Burn through initial cooldown (0.8s = 24 ticks) then trigger dash.
    // Target placed far enough so Bandit stays in [3.5, 6.0] window after walking during cooldown.
    sim.tick(25);
    assertThat(bandit.getAbility().getDashState()).isEqualTo(AbilityComponent.DashState.DASHING);

    // Combat should be disabled during dash, even with StatusEffectSystem running
    assertThat(bandit.getCombat().isCombatDisabled())
        .as("Combat should be disabled during dash (ABILITY_DASH source)")
        .isTrue();

    // Run a few more ticks while dashing -- combat should stay disabled
    sim.tick(5);
    assertThat(bandit.getCombat().isCombatDisabled())
        .as("Combat should still be disabled mid-dash")
        .isTrue();
  }

  // -- Charge speed multiplier composes with status effects --

  @Test
  void charge_speedComposesWithRage() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.STATUS_EFFECTS, SimSystems.ABILITY)
            .spawn(
                TroopTemplate.melee("Prince", Team.BLUE)
                    .at(5, 5)
                    .hp(1000)
                    .damage(100)
                    .range(1.5f)
                    .sightRange(5.5f)
                    .cooldown(1.4f)
                    .speed(1.5f)
                    .ability(chargeData()))
            .spawn(TroopTemplate.target("Target", Team.RED).at(15, 5))
            .deployed()
            .build();

    Troop prince = sim.troop("Prince");
    prince.getCombat().setCurrentTarget(sim.troop("Target"));

    // Build up charge (2.6s)
    sim.tick(78);
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Charge sets ABILITY_CHARGE speed multiplier to 2.0
    assertThat(prince.getMovement().getSpeedMultiplier()).isEqualTo(2.0f);

    // Apply rage (40% speed boost) via status effect
    prince.addEffect(new AppliedEffect(StatusEffectType.RAGE, 5.0f, "TestRage"));

    // Tick once to let StatusEffectSystem apply rage
    sim.tick();

    // Speed should compose: 2.0 (charge) * 1.4 (rage) = 2.8
    assertThat(prince.getMovement().getSpeedMultiplier())
        .as("Charge (2.0x) * Rage (1.4x) = 2.8x")
        .isCloseTo(2.8f, org.assertj.core.data.Offset.offset(0.01f));
  }

  @Test
  void charge_speedResetDoesNotAffectRage() {
    // Target placed far enough (distance 10) to be outside attack range but within sight
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.STATUS_EFFECTS, SimSystems.ABILITY)
            .spawn(
                TroopTemplate.melee("Prince", Team.BLUE)
                    .at(5, 5)
                    .hp(1000)
                    .damage(100)
                    .range(1.5f)
                    .sightRange(5.5f)
                    .cooldown(1.4f)
                    .speed(1.5f)
                    .ability(chargeData()))
            .spawn(TroopTemplate.target("Target", Team.RED).at(15, 5))
            .deployed()
            .build();

    Troop prince = sim.troop("Prince");
    prince.getCombat().setCurrentTarget(sim.troop("Target"));

    // Build charge (2.6s = 78 ticks)
    sim.tick(78);
    assertThat(prince.getAbility().isCharged()).isTrue();

    // Apply rage
    prince.addEffect(new AppliedEffect(StatusEffectType.RAGE, 5.0f, "TestRage"));
    sim.tick();

    // Consume charge via static helper (simulates CombatSystem consuming after attack)
    AbilitySystem.consumeCharge(prince);

    // Tick to let StatusEffectSystem recalculate
    sim.tick();

    // Charge cleared, but rage should remain -> 1.4x
    assertThat(prince.getMovement().getSpeedMultiplier())
        .as("After charge consumed, only rage should remain (1.4x)")
        .isCloseTo(1.4f, org.assertj.core.data.Offset.offset(0.01f));
  }
}
