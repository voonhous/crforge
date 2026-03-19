package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
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
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StealthAbilityTest {

  private GameState gameState;
  private AbilitySystem abilitySystem;
  private CombatSystem combatSystem;
  private AoeDamageService aoeDamageService;
  private TargetingSystem targetingSystem;
  private PhysicsSystem physicsSystem;

  private static final float DT = 1.0f / 30;

  // Royal Ghost stealth stats: 1.8s fade time, 0.4s attack grace period
  private static final float FADE_TIME = 1.8f;
  private static final float GRACE_PERIOD = 0.4f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    abilitySystem = new AbilitySystem(gameState);
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    targetingSystem = new TargetingSystem();
    physicsSystem = new PhysicsSystem(new Arena("Test Arena"));
  }

  // -- Test 1: Ghost starts invisible after deploy --

  @Test
  void stealth_shouldBeInvisibleAfterDeploy() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);

    gameState.spawnEntity(ghost);
    gameState.processPending();

    // Finish deploy
    ghost.setDeployTimer(0);

    // Ghost should already be invisible -- no fade wait needed
    assertThat(ghost.isInvisible())
        .as("Ghost should be invisible immediately after deploy finishes")
        .isTrue();
  }

  // -- Test 2: Ghost is invisible even during deploy (constructor sets invisible=true) --

  @Test
  void stealth_shouldBeInvisibleEvenDuringDeploy() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);

    gameState.spawnEntity(ghost);
    gameState.processPending();

    // Ghost is still deploying but isInvisible() checks ability component directly
    assertThat(ghost.isDeploying()).isTrue();
    assertThat(ghost.isInvisible())
        .as("Ghost should be invisible even while deploying (constructor sets invisible=true)")
        .isTrue();
  }

  // -- Test 3: Invisible ghost skipped by TargetingSystem.findBestTarget() --

  @Test
  void stealth_invisibleGhostShouldNotBeTargetedByUnits() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop enemy = createMeleeAttacker(Team.RED, 7, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    ghost.setDeployTimer(0);
    enemy.setDeployTimer(0);

    // Ghost starts invisible from constructor
    assertThat(ghost.isInvisible()).isTrue();

    // Targeting system should not find the invisible ghost
    targetingSystem.updateTargets(gameState.getAliveEntities());

    assertThat(enemy.getCombat().getCurrentTarget())
        .as("Enemy should not target invisible ghost")
        .isNull();
  }

  // -- Test 4: Existing target dropped when ghost goes invisible --

  @Test
  void stealth_existingTargetShouldBeDroppedWhenGhostGoesInvisible() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);
    Troop enemy = createMeleeAttacker(Team.RED, 7, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(target);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    ghost.setDeployTimer(0);
    target.setDeployTimer(0);
    enemy.setDeployTimer(0);

    // Reveal the ghost by attacking past the grace period
    makeVisible(ghost, target);
    assertThat(ghost.isInvisible()).isFalse();

    // Enemy acquires the visible ghost as target
    targetingSystem.updateTargets(gameState.getAliveEntities());
    assertThat(enemy.getCombat().getCurrentTarget())
        .as("Enemy should initially target visible ghost")
        .isEqualTo(ghost);

    // Ghost stops attacking and fades back to invisible
    ghost.getCombat().finishAttack();
    for (int i = 0; i < 55; i++) {
      abilitySystem.update(DT);
    }
    assertThat(ghost.isInvisible()).isTrue();

    // Re-run targeting -- should drop the invisible ghost
    targetingSystem.updateTargets(gameState.getAliveEntities());

    assertThat(enemy.getCombat().getCurrentTarget())
        .as("Enemy should drop target when ghost goes invisible")
        .isNull();
  }

  // -- Test 5: Invisible ghost still hit by AOE spell (applySpellDamage) --

  @Test
  void stealth_invisibleGhostShouldBeHitByAoeSpell() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);

    gameState.spawnEntity(ghost);
    gameState.processPending();

    ghost.setDeployTimer(0);

    // Ghost starts invisible
    assertThat(ghost.isInvisible()).isTrue();

    int hpBefore = ghost.getHealth().getCurrent();

    // Cast a Fireball (position-based AOE) at the ghost's location
    aoeDamageService.applySpellDamage(Team.RED, 5, 5, 200, 2.5f, List.of());

    assertThat(ghost.getHealth().getCurrent())
        .as("Invisible ghost should still be hit by AOE spell damage")
        .isEqualTo(hpBefore - 200);
  }

  // -- Test 6: Attack grace period: stays invisible for 0.4s after starting attack --

  @Test
  void stealth_gracePeriod_shouldStayInvisibleDuringGracePeriod() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(target);
    gameState.processPending();

    ghost.setDeployTimer(0);
    target.setDeployTimer(0);

    // Ghost starts invisible
    assertThat(ghost.isInvisible()).isTrue();

    // Ghost starts attacking (simulate combat starting an attack sequence)
    ghost.getCombat().setCurrentTarget(target);
    ghost.getCombat().startAttackSequence();
    assertThat(ghost.getCombat().isAttacking()).isTrue();

    // Run for 0.2s (6 ticks) -- within grace period, should still be invisible
    for (int i = 0; i < 6; i++) {
      abilitySystem.update(DT);
    }

    assertThat(ghost.isInvisible())
        .as("Ghost should stay invisible during attack grace period (0.2s < 0.4s)")
        .isTrue();
  }

  // -- Test 7: Reveals after grace period expires --

  @Test
  void stealth_shouldRevealAfterGracePeriodExpires() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(target);
    gameState.processPending();

    ghost.setDeployTimer(0);
    target.setDeployTimer(0);

    // Ghost starts invisible
    assertThat(ghost.isInvisible()).isTrue();

    // Ghost starts attacking
    ghost.getCombat().setCurrentTarget(target);
    ghost.getCombat().startAttackSequence();

    // Run for 0.4s+ (13 ticks) -- grace period expired, should reveal
    for (int i = 0; i < 13; i++) {
      abilitySystem.update(DT);
    }

    assertThat(ghost.isInvisible())
        .as("Ghost should reveal after grace period expires (0.43s > 0.4s)")
        .isFalse();
  }

  // -- Test 8: Fade timer resets on reveal --

  @Test
  void stealth_fadeTimerShouldResetOnReveal() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(target);
    gameState.processPending();

    ghost.setDeployTimer(0);
    target.setDeployTimer(0);

    // Ghost starts invisible

    // Start attacking and reveal via grace period expiry
    ghost.getCombat().setCurrentTarget(target);
    ghost.getCombat().startAttackSequence();
    for (int i = 0; i < 13; i++) {
      abilitySystem.update(DT);
    }
    assertThat(ghost.isInvisible()).isFalse();

    // Fade timer should have been reset to 0
    assertThat(ghost.getAbility().getStealthFadeTimer())
        .as("Fade timer should reset to 0 on reveal")
        .isEqualTo(0f);
  }

  // -- Test 9: First attack from invisible gets full grace period --

  @Test
  void stealth_firstAttackFromInvisibleGetsFullGracePeriod() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(target);
    gameState.processPending();

    ghost.setDeployTimer(0);
    target.setDeployTimer(0);

    // Ghost starts invisible with revealTimer=0
    assertThat(ghost.isInvisible()).isTrue();
    assertThat(ghost.getAbility().getStealthRevealTimer()).isEqualTo(0f);

    // Ghost starts attacking
    ghost.getCombat().setCurrentTarget(target);
    ghost.getCombat().startAttackSequence();

    // After 1 tick, reveal timer should have advanced but not expired grace period
    abilitySystem.update(DT);

    assertThat(ghost.isInvisible())
        .as("Ghost should stay invisible during first attack's grace period")
        .isTrue();

    // Run for enough ticks to pass 0.4s total (12 ticks = 0.4s)
    // We already did 1 tick, so 11 more
    for (int i = 0; i < 11; i++) {
      abilitySystem.update(DT);
    }

    // 12 ticks * (1/30) = 0.4s exactly -- still invisible (need to exceed, not equal)
    // 13th tick pushes past 0.4s
    abilitySystem.update(DT);

    assertThat(ghost.isInvisible())
        .as("Ghost should reveal after first attack's grace period expires")
        .isFalse();
  }

  // -- Test 10: Invisible ghost skips collision --

  @Test
  void stealth_invisibleGhostShouldSkipCollision() {
    // Use 0 speed so physics movement doesn't affect positions -- we only test collision resolution
    AbilityData stealthData = new StealthAbility(FADE_TIME, GRACE_PERIOD);
    Troop ghost =
        Troop.builder()
            .name("RoyalGhost")
            .team(Team.BLUE)
            .position(new Position(5, 5))
            .health(new Health(800))
            .movement(new Movement(0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(216)
                    .range(1.2f)
                    .sightRange(5.5f)
                    .attackCooldown(1.8f)
                    .build())
            .deployTime(0f)
            .ability(new AbilityComponent(stealthData))
            .build();

    Troop blocker =
        Troop.builder()
            .name("Blocker")
            .team(Team.RED)
            .position(new Position(5.5f, 5))
            .health(new Health(1000))
            .movement(new Movement(0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(blocker);
    gameState.processPending();

    // Ghost starts invisible from constructor -- no makeInvisible needed
    assertThat(ghost.isInvisible()).isTrue();

    // Record positions before collision resolution
    float ghostX = ghost.getPosition().getX();
    float blockerX = blocker.getPosition().getX();

    // Run physics -- they overlap but invisible ghost should not be pushed
    physicsSystem.update(gameState.getAliveEntities(), DT);

    assertThat(ghost.getPosition().getX())
        .as("Invisible ghost should not be pushed by collision")
        .isEqualTo(ghostX);
    assertThat(blocker.getPosition().getX())
        .as("Blocker should not be pushed by invisible ghost collision")
        .isEqualTo(blockerX);
  }

  // -- Test 11: Full lifecycle: invisible -> attack -> grace -> reveal -> stop attack -> fade ->
  // invisible --

  @Test
  void stealth_fullLifecycle() {
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop target = createDummyTarget(Team.RED, 6, 5);

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Phase 1: Deploying -- ghost is invisible even while deploying
    assertThat(ghost.isDeploying()).isTrue();
    assertThat(ghost.isInvisible()).isTrue();

    // Finish deploy
    ghost.setDeployTimer(0);
    target.setDeployTimer(0);
    assertThat(ghost.isDeploying()).isFalse();

    // Phase 2: Invisible after deploy -- no fade needed
    assertThat(ghost.isInvisible()).as("Phase 2: Ghost should be invisible after deploy").isTrue();

    // Phase 3: Start attacking -- grace period keeps ghost invisible
    ghost.getCombat().setCurrentTarget(target);
    ghost.getCombat().startAttackSequence();

    // Run for 0.2s (6 ticks) -- within grace period, still invisible
    for (int i = 0; i < 6; i++) {
      abilitySystem.update(DT);
    }
    assertThat(ghost.isInvisible())
        .as("Phase 3: Ghost should stay invisible during grace period")
        .isTrue();

    // Phase 4: Grace period expires -- reveal
    for (int i = 0; i < 7; i++) {
      abilitySystem.update(DT);
    }
    assertThat(ghost.isInvisible()).as("Phase 4: Ghost should reveal after grace period").isFalse();

    // Phase 5: Stop attacking -- fade timer restarts, ghost fades back to invisible
    ghost.getCombat().finishAttack();
    assertThat(ghost.getCombat().isAttacking()).isFalse();

    // Run for 1.8s+ (55 ticks) -- should become invisible again
    for (int i = 0; i < 55; i++) {
      abilitySystem.update(DT);
    }
    assertThat(ghost.isInvisible())
        .as("Phase 5: Ghost should become invisible again after 1.8s of not attacking")
        .isTrue();
  }

  // -- Additional targeting tests --

  @Test
  void stealth_additionalTargetsShouldSkipInvisibleGhost() {
    // Electro Wizard (multipleTargets=2) should not select invisible ghost as extra target
    Troop ghost = createGhost(Team.BLUE, 5, 5);
    Troop visibleTroop = createDummyTarget(Team.BLUE, 6, 5);

    Troop ewiz =
        Troop.builder()
            .name("EWiz")
            .team(Team.RED)
            .position(new Position(7, 5))
            .health(new Health(500))
            .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(50)
                    .range(5.0f)
                    .sightRange(5.5f)
                    .attackCooldown(1.8f)
                    .multipleTargets(2)
                    .build())
            .deployTime(0f)
            .build();

    gameState.spawnEntity(ghost);
    gameState.spawnEntity(visibleTroop);
    gameState.spawnEntity(ewiz);
    gameState.processPending();

    ghost.setDeployTimer(0);
    visibleTroop.setDeployTimer(0);

    // Ghost starts invisible from constructor
    assertThat(ghost.isInvisible()).isTrue();

    int ghostHpBefore = ghost.getHealth().getCurrent();

    // EWiz attacks visible troop as primary
    ewiz.getCombat().setCurrentTarget(visibleTroop);
    ewiz.getCombat().startAttackSequence();
    ewiz.getCombat().setCurrentWindup(0);

    combatSystem.update(DT);

    // Ghost should not have been hit as an additional target
    assertThat(ghost.getHealth().getCurrent())
        .as("Invisible ghost should not be hit as an additional target")
        .isEqualTo(ghostHpBefore);
  }

  // -- Helper methods --

  /** Creates a Royal Ghost with stealth ability: 1.8s fade time, 0.4s attack grace period. */
  private Troop createGhost(Team team, float x, float y) {
    AbilityData stealthData = new StealthAbility(FADE_TIME, GRACE_PERIOD);

    return Troop.builder()
        .name("RoyalGhost")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(800))
        .movement(new Movement(1.6f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder().damage(216).range(1.2f).sightRange(5.5f).attackCooldown(1.8f).build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .ability(new AbilityComponent(stealthData))
        .build();
  }

  /** Creates a basic melee attacker for targeting tests. */
  private Troop createMeleeAttacker(Team team, float x, float y) {
    return Troop.builder()
        .name("Knight")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder().damage(100).range(1.5f).sightRange(5.5f).attackCooldown(1.2f).build())
        .deployTime(0f)
        .build();
  }

  private Troop createDummyTarget(Team team, float x, float y) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .deployTime(0f)
        .build();
  }

  /**
   * Reveals the ghost by attacking a target past the grace period. The ghost must already be
   * spawned and finished deploying.
   */
  private void makeVisible(Troop ghost, Troop target) {
    ghost.getCombat().setCurrentTarget(target);
    ghost.getCombat().startAttackSequence();
    // Tick past the grace period (13 ticks > 0.4s)
    for (int i = 0; i < 13; i++) {
      abilitySystem.update(DT);
    }
    assertThat(ghost.isInvisible()).isFalse();
  }
}
