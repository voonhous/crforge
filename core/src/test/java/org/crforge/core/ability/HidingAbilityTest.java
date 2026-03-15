package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;
import org.crforge.core.testing.BuildingTemplate;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.SimSystems;
import org.crforge.core.testing.TroopTemplate;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Tesla hiding mechanic. Tesla hides underground when no enemies are nearby
 * (untargetable, combat disabled) and reveals when an enemy enters sight range.
 */
class HidingAbilityTest {

  private static final float HIDE_TIME = 0.5f;
  private static final float UP_TIME = 0.5f;

  // Due to float precision, countdown from 0.5 at DT=1/30 takes 16 ticks to reach <=0
  // (15 * DT leaves a tiny positive remainder ~3e-8).
  private static final int COUNTDOWN_TICKS = 16;

  // Accumulation from 0 to >=0.5 takes 15 ticks (15 * DT = ~0.500000025 >= 0.5)
  private static final int ACCUMULATE_TICKS = 15;

  // Total ticks to go from HIDDEN -> UP: 1 tick for transition + COUNTDOWN_TICKS for reveal timer
  private static final int HIDDEN_TO_UP_TICKS = 1 + COUNTDOWN_TICKS;

  // Total ticks to go from UP -> HIDDEN with no target: upTime accumulation + hideTime countdown
  private static final int UP_TO_HIDDEN_TICKS = ACCUMULATE_TICKS + COUNTDOWN_TICKS;

  private static BuildingTemplate teslaTemplate(Team team) {
    return BuildingTemplate.defense("Tesla", team)
        .hp(800)
        .lifetime(30f)
        .combat(
            Combat.builder()
                .damage(100)
                .range(5.5f)
                .sightRange(5.5f)
                .attackCooldown(1.1f)
                .loadTime(0.7f)
                .build())
        .ability(new AbilityComponent(new HidingAbility(HIDE_TIME, UP_TIME)));
  }

  /** Tesla template with no lifetime (no health decay), for AOE damage isolation tests. */
  private static BuildingTemplate teslaNoLifetime(Team team) {
    return teslaTemplate(team).lifetime(0f);
  }

  // -- Test 1: Tesla deploys visible, then hides with no enemies --

  @Test
  void tesla_shouldStartVisibleThenHideWithNoEnemies() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Tesla should start in UP state (visible) after deploy
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);
    assertThat(tesla.isHidden()).isFalse();
    assertThat(tesla.isTargetable()).isTrue();

    // After upTime (no enemies), should transition to HIDING
    sim.tick(ACCUMULATE_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDING);

    // After hideTime countdown, should be fully HIDDEN
    sim.tick(COUNTDOWN_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDDEN);
    assertThat(tesla.isHidden()).isTrue();
    assertThat(tesla.isTargetable()).isFalse();
  }

  // -- Test 2: Tesla reveals when enemy enters sight range --

  @Test
  void tesla_shouldRevealWhenEnemyEntersSightRange() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();

    // Spawn enemy within sight range (distance = 4, sightRange = 5.5)
    sim.gameState()
        .spawnEntity(
            TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000).deployTime(0f).build());
    sim.gameState().processPending();
    sim.troop("Enemy").update(2.0f);

    // After first tick: TargetingSystem assigns target, AbilitySystem sees target -> REVEALING
    sim.tick();

    assertThat(tesla.getAbility().getHidingState())
        .isEqualTo(AbilityComponent.HidingState.REVEALING);
    // Tesla is targetable during REVEALING (enemies can shoot back)
    assertThat(tesla.isTargetable()).isTrue();

    // After hideTime countdown completes, Tesla should transition to UP
    sim.tick(COUNTDOWN_TICKS);

    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);
  }

  // -- Test 3: Tesla can attack after reveal completes --

  @Test
  void tesla_shouldAttackAfterRevealCompletes() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000))
            .deployed()
            .build();

    int initialEnemyHp = sim.troop("Enemy").getHealth().getCurrent();

    // Tick through HIDDEN -> REVEALING -> UP and then enough time for an attack
    sim.tickSeconds(3.0f);

    // Enemy should have taken damage (Tesla has been able to attack)
    assertThat(sim.troop("Enemy").getHealth().getCurrent()).isLessThan(initialEnemyHp);
  }

  // -- Test 4: Tesla cannot attack during REVEALING --

  @Test
  void tesla_shouldNotAttackDuringRevealing() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();

    // Spawn enemy within sight range
    sim.gameState()
        .spawnEntity(
            TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000).deployTime(0f).build());
    sim.gameState().processPending();
    sim.troop("Enemy").update(2.0f);

    int initialEnemyHp = sim.troop("Enemy").getHealth().getCurrent();

    // First tick: HIDDEN -> REVEALING
    sim.tick();
    assertThat(tesla.getAbility().getHidingState())
        .isEqualTo(AbilityComponent.HidingState.REVEALING);

    // Combat should be disabled during REVEALING
    assertThat(tesla.getCombat().isCombatDisabled()).isTrue();

    // After a few more ticks (still in REVEALING phase, before hideTime expires)
    sim.tick(5);

    // Enemy HP should not have changed -- Tesla cannot attack during REVEALING
    assertThat(sim.troop("Enemy").getHealth().getCurrent()).isEqualTo(initialEnemyHp);
  }

  // -- Test 5: Tesla hides after upTime with no targets --

  @Test
  void tesla_shouldHideAfterUpTimeWithNoTargets() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Get to UP state
    sim.tick(HIDDEN_TO_UP_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);

    // Kill the enemy so Tesla has no target
    sim.troop("Enemy").getHealth().takeDamage(5000);
    sim.tick(); // Process death (also first "no target" tick)

    // After upTime accumulation -> HIDING
    sim.tick(ACCUMULATE_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDING);

    // After hideTime countdown -> HIDDEN
    sim.tick(COUNTDOWN_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDDEN);
    assertThat(tesla.isHidden()).isTrue();
    assertThat(tesla.isTargetable()).isFalse();
  }

  // -- Test 6: Tesla cancels hide when enemy appears during HIDING --

  @Test
  void tesla_shouldCancelHideWhenEnemyAppearsDuringHiding() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Get to UP state
    sim.tick(HIDDEN_TO_UP_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);

    // Kill enemy and tick through upTime to enter HIDING
    sim.troop("Enemy").getHealth().takeDamage(5000);
    sim.tick(1 + ACCUMULATE_TICKS); // Process death + upTime accumulation
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDING);

    // Spawn a new enemy while Tesla is in HIDING state
    sim.gameState()
        .spawnEntity(
            TroopTemplate.target("NewEnemy", Team.RED).at(9, 14).hp(5000).deployTime(0f).build());
    sim.gameState().processPending();
    sim.troop("NewEnemy").update(2.0f); // Deploy instantly

    // Next tick: TargetingSystem assigns new target, AbilitySystem cancels hide -> UP
    sim.tick();
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);
  }

  // -- Test 7: Earthquake damages hidden Tesla without revealing --

  @Test
  void earthquake_shouldDamageHiddenTeslaWithoutRevealing() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY, SimSystems.AREA_EFFECT)
            .spawn(teslaNoLifetime(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    int initialHp = tesla.getHealth().getCurrent();
    assertThat(tesla.isHidden()).isTrue();

    // Spawn an Earthquake area effect centered on the Tesla
    AreaEffect earthquake =
        AreaEffect.builder()
            .name("Earthquake")
            .team(Team.RED)
            .position(new Position(9, 10))
            .health(new Health(1))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .stats(
                AreaEffectStats.builder()
                    .name("Earthquake")
                    .radius(3.5f)
                    .damage(50)
                    .buff("Earthquake")
                    .buffDuration(1.0f)
                    .hitSpeed(0.0f) // One-shot
                    .hitsGround(true)
                    .onlyEnemies(true)
                    .build())
            .remainingLifetime(0.5f)
            .build();
    sim.gameState().spawnEntity(earthquake);
    sim.gameState().processPending();

    sim.tick();

    // Tesla should have taken damage
    assertThat(tesla.getHealth().getCurrent()).isLessThan(initialHp);
    // But Tesla should remain hidden
    assertThat(tesla.isHidden()).isTrue();
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDDEN);
  }

  // -- Test 8: Freeze forces hidden Tesla to reveal --

  @Test
  void freeze_shouldForceHiddenTeslaToRevealAndFreeze() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(
                SimSystems.TARGETING,
                SimSystems.ABILITY,
                SimSystems.AREA_EFFECT,
                SimSystems.STATUS_EFFECTS)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();

    // Spawn a Freeze area effect centered on the Tesla
    AreaEffect freeze =
        AreaEffect.builder()
            .name("Freeze")
            .team(Team.RED)
            .position(new Position(9, 10))
            .health(new Health(1))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .stats(
                AreaEffectStats.builder()
                    .name("Freeze")
                    .radius(3.0f)
                    .damage(0)
                    .buff("Freeze")
                    .buffDuration(4.0f)
                    .hitSpeed(0.0f) // One-shot
                    .hitsGround(true)
                    .onlyEnemies(true)
                    .build())
            .remainingLifetime(0.5f)
            .build();
    sim.gameState().spawnEntity(freeze);
    sim.gameState().processPending();

    sim.tick();

    // Tesla should be revealed (forced to UP)
    assertThat(tesla.isHidden()).isFalse();
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);
    // Tesla should be frozen
    assertThat(tesla.getAppliedEffects()).isNotEmpty();
  }

  // -- Test 9: Normal AOE does not hit hidden Tesla --

  @Test
  void normalAoe_shouldNotHitHiddenTesla() {
    // Use no-lifetime Tesla to isolate AOE damage from lifetime decay
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY, SimSystems.AREA_EFFECT)
            .spawn(teslaNoLifetime(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    int initialHp = tesla.getHealth().getCurrent();
    assertThat(tesla.isHidden()).isTrue();

    // Spawn a Zap area effect (not Earthquake or Freeze)
    AreaEffect zap =
        AreaEffect.builder()
            .name("Zap")
            .team(Team.RED)
            .position(new Position(9, 10))
            .health(new Health(1))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .stats(
                AreaEffectStats.builder()
                    .name("Zap")
                    .radius(2.5f)
                    .damage(75)
                    .buff("ZapFreeze")
                    .buffDuration(0.5f)
                    .hitSpeed(0.0f)
                    .hitsGround(true)
                    .onlyEnemies(true)
                    .build())
            .remainingLifetime(0.5f)
            .build();
    sim.gameState().spawnEntity(zap);
    sim.gameState().processPending();

    sim.tick();

    // Tesla should NOT have taken damage (hidden, Zap doesn't bypass)
    assertThat(tesla.getHealth().getCurrent()).isEqualTo(initialHp);
    // Tesla should remain hidden
    assertThat(tesla.isHidden()).isTrue();
  }

  // -- Test 10: Tesla hides again after freeze wears off and no enemies --

  @Test
  void tesla_shouldHideAgainAfterFreezeWearsOffWithNoEnemies() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(
                SimSystems.TARGETING,
                SimSystems.ABILITY,
                SimSystems.AREA_EFFECT,
                SimSystems.STATUS_EFFECTS)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();

    // Apply Freeze to force reveal
    AreaEffect freeze =
        AreaEffect.builder()
            .name("Freeze")
            .team(Team.RED)
            .position(new Position(9, 10))
            .health(new Health(1))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
            .stats(
                AreaEffectStats.builder()
                    .name("Freeze")
                    .radius(3.0f)
                    .damage(0)
                    .buff("Freeze")
                    .buffDuration(1.0f)
                    .hitSpeed(0.0f)
                    .hitsGround(true)
                    .onlyEnemies(true)
                    .build())
            .remainingLifetime(0.5f)
            .build();
    sim.gameState().spawnEntity(freeze);
    sim.gameState().processPending();

    sim.tick();
    assertThat(tesla.isHidden()).isFalse();

    // Wait for freeze to wear off (1s), then upTime (0.5s), then hideTime (0.5s)
    // Total ~2s + margin = 3s
    sim.tickSeconds(3.0f);

    assertThat(tesla.isHidden()).isTrue();
    assertThat(tesla.isTargetable()).isFalse();
  }

  // -- Test 11: Lifetime decay continues while hidden --

  @Test
  void tesla_lifetimeShouldDecayWhileHidden() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();
    int initialHp = tesla.getHealth().getCurrent();

    // Tick for a few seconds while hidden -- lifetime should still decay
    sim.tickSeconds(5.0f);

    // Health should have decayed due to lifetime
    assertThat(tesla.getHealth().getCurrent()).isLessThan(initialHp);
    // Tesla should still be hidden (no enemies)
    assertThat(tesla.isHidden()).isTrue();
  }

  // -- Test 12: Building-targeting troop retargets when Tesla reveals --

  @Test
  void targetOnlyBuildings_retargetsWhenTeslaReveals() {
    // Building-targeting troop (like Ram) + far tower + closer hidden Tesla
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 14))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();

    // Now spawn the Ram and far tower after Tesla is hidden
    sim.gameState()
        .spawnEntity(
            TroopTemplate.melee("Ram", Team.RED)
                .at(9, 18)
                .hp(1000)
                .damage(100)
                .sightRange(15.0f)
                .targetOnlyBuildings(true)
                .speed(0f)
                .deployTime(0f)
                .build());
    sim.gameState().processPending();
    sim.troop("Ram").update(2.0f);

    Tower farTower = Tower.createPrincessTower(Team.BLUE, 9, 3, 1);
    farTower.onSpawn();
    sim.gameState().spawnEntity(farTower);
    sim.gameState().processPending();

    // Tesla is hidden, so the Ram should target the far tower
    sim.tick();
    assertThat(sim.troop("Ram").getCombat().getCurrentTarget())
        .as("Ram should target the far tower while Tesla is hidden")
        .isEqualTo(farTower);

    // Tick through Tesla's reveal cycle (HIDDEN -> REVEALING -> UP)
    // Tesla needs an enemy in sight range to reveal. The Ram is at (9,18),
    // Tesla is at (9,14) with sightRange 5.5 -- distance = 4, within range.
    sim.tick(HIDDEN_TO_UP_TICKS);

    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);
    assertThat(tesla.isTargetable()).isTrue();

    // After Tesla is revealed and targetable, the Ram should retarget to Tesla (closer)
    sim.tick();
    assertThat(sim.troop("Ram").getCombat().getCurrentTarget())
        .as("Ram should retarget to the now-visible Tesla (closer than tower)")
        .isEqualTo(tesla);
  }

  // -- Test 13: Load time accumulates after reveal --

  @Test
  void tesla_shouldAccumulateLoadTimeAfterReveal() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Let Tesla hide first (no enemies present)
    sim.tick(UP_TO_HIDDEN_TICKS);
    assertThat(tesla.isHidden()).isTrue();

    // Get Tesla to UP state by spawning an enemy
    sim.gameState()
        .spawnEntity(
            TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000).deployTime(0f).build());
    sim.gameState().processPending();
    sim.troop("Enemy").update(2.0f);

    // HIDDEN -> REVEALING (1 tick) -> UP (COUNTDOWN_TICKS ticks)
    sim.tick(HIDDEN_TO_UP_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);

    // Now combat is enabled, load time should be accumulating
    sim.tick(30); // 1 second

    assertThat(tesla.getCombat().getAccumulatedLoadTime()).isGreaterThan(0f);
  }

  // -- Test 13: Combat is not disabled during HIDING transition --

  @Test
  void tesla_combatShouldNotBeDisabledDuringHidingTransition() {
    // During HIDING state, combat is NOT disabled -- Tesla can still see and attack
    // enemies that appear, which cancels the hide. This is unlike HIDDEN/REVEALING
    // where combat IS disabled.
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(teslaTemplate(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Enemy", Team.RED).at(9, 14).hp(5000))
            .deployed()
            .build();

    Building tesla = sim.building("Tesla");

    // Get to UP state
    sim.tick(HIDDEN_TO_UP_TICKS);
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.UP);

    // Kill enemy, wait for upTime to start HIDING
    sim.troop("Enemy").getHealth().takeDamage(5000);
    sim.tick(1 + ACCUMULATE_TICKS); // Process death + upTime accumulation
    assertThat(tesla.getAbility().getHidingState()).isEqualTo(AbilityComponent.HidingState.HIDING);

    // During HIDING, combat should NOT be disabled (Tesla can still attack if an enemy appears)
    assertThat(tesla.getCombat().getCombatDisableSources())
        .doesNotContain(ModifierSource.ABILITY_HIDING);
  }
}
