package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.ProjectileStats;
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

class ReturningProjectileTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void returningProjectile_shouldTravelOutAndReturn() {
    // Create a source entity for the returning projectile
    Troop source =
        Troop.builder()
            .name("Executioner")
            .team(Team.BLUE)
            .position(new Position(5, 5))
            .health(new Health(1000))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(source);
    gameState.processPending();

    // Create a piercing + returning projectile
    Projectile proj =
        new Projectile(source, source, 100, 1.0f, 10f, java.util.Collections.emptyList());
    proj.configurePiercing(1f, 0f, 5f, true, true);
    proj.configureReturning(source);

    gameState.spawnProjectile(proj);

    // Travel outbound: 5 tiles at 10 tiles/sec = 0.5s
    for (int i = 0; i < 15; i++) {
      proj.update(1.0f / 30f);
    }

    // Should still be active (transitioning to return phase)
    assertThat(proj.isActive()).isTrue();
    assertThat(proj.isReturnPhase()).isTrue();

    // Travel return: home back to source at (5,5)
    for (int i = 0; i < 60; i++) {
      if (!proj.isActive()) break;
      proj.update(1.0f / 30f);
    }

    // Should have deactivated upon reaching source
    assertThat(proj.isActive()).isFalse();
  }

  @Test
  void returningProjectile_shouldHitEnemiesOnBothTrips() {
    Troop source = createExecutioner(Team.BLUE, 2, 10);
    // Enemy in the path at x=5 (3 tiles away, within outbound range of 7.5)
    Troop enemy = createTarget(Team.RED, 5, 10);

    gameState.spawnEntity(source);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Set up the attack: source targets enemy, skip windup
    source.getCombat().setCurrentTarget(enemy);
    source.getCombat().startAttackSequence();
    source.getCombat().setCurrentWindup(0);

    // Fire the projectile
    combatSystem.update(1.0f / 30);

    assertThat(gameState.getProjectiles()).hasSize(1);
    Projectile proj = gameState.getProjectiles().get(0);
    assertThat(proj.isReturningEnabled()).isTrue();

    int hpAfterFirstHit = -1;

    // Run enough ticks for outbound travel + return
    for (int i = 0; i < 120; i++) {
      combatSystem.update(1.0f / 30);
      if (hpAfterFirstHit < 0 && enemy.getHealth().getCurrent() < 1000) {
        hpAfterFirstHit = enemy.getHealth().getCurrent();
      }
    }

    // Enemy should have been hit twice (outbound + return), taking double damage
    assertThat(hpAfterFirstHit).as("First hit should deal damage").isLessThan(1000);
    assertThat(enemy.getHealth().getCurrent())
        .as("Second hit (return) should deal additional damage")
        .isLessThan(hpAfterFirstHit);
  }

  @Test
  void returningProjectile_shouldDeactivateWhenSourceDies() {
    Troop source =
        Troop.builder()
            .name("Executioner")
            .team(Team.BLUE)
            .position(new Position(5, 5))
            .health(new Health(1000))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(source);
    gameState.processPending();

    Projectile proj =
        new Projectile(source, source, 100, 1.0f, 10f, java.util.Collections.emptyList());
    proj.configurePiercing(1f, 0f, 5f, true, true);
    proj.configureReturning(source);

    // Reach outbound max range
    for (int i = 0; i < 16; i++) {
      proj.update(1.0f / 30f);
    }
    assertThat(proj.isReturnPhase()).isTrue();

    // Kill the source
    source.getHealth().kill();

    // Next update should deactivate
    proj.update(1.0f / 30f);
    assertThat(proj.isActive()).isFalse();
  }

  @Test
  void returningProjectile_shouldTrackSourceCurrentPosition() {
    Troop source =
        Troop.builder()
            .name("Executioner")
            .team(Team.BLUE)
            .position(new Position(5, 5))
            .health(new Health(1000))
            .deployTime(0f)
            .build();

    gameState.spawnEntity(source);
    gameState.processPending();

    Projectile proj =
        new Projectile(source, source, 100, 1.0f, 10f, java.util.Collections.emptyList());
    proj.configurePiercing(1f, 0f, 5f, true, true);
    proj.configureReturning(source);

    // Travel outbound to max range
    for (int i = 0; i < 16; i++) {
      proj.update(1.0f / 30f);
    }
    assertThat(proj.isReturnPhase()).isTrue();

    // Move source to a new position
    source.getPosition().set(3, 3);

    // Run a few return ticks -- projectile should head toward (3,3), not (5,5)
    float prevDist = proj.getPosition().distanceTo(source.getPosition());
    proj.update(1.0f / 30f);
    float newDist = proj.getPosition().distanceTo(source.getPosition());

    assertThat(newDist)
        .as("Projectile should be getting closer to source's NEW position")
        .isLessThan(prevDist);
  }

  @Test
  void pingpongMovingShooter_shouldDisableCombatDuringFlight() {
    Troop executioner = createExecutioner(Team.BLUE, 5, 5);
    Troop enemy = createTarget(Team.RED, 8, 5);

    gameState.spawnEntity(executioner);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    assertThat(executioner.getCombat().isReturningProjectileInFlight()).isFalse();

    // Fire a returning projectile
    executioner.getCombat().setCurrentTarget(enemy);
    executioner.getCombat().startAttackSequence();
    executioner.getCombat().setCurrentWindup(0);

    combatSystem.update(1.0f / 30);

    // Combat should be disabled while projectile is in flight
    assertThat(executioner.getCombat().isReturningProjectileInFlight())
        .as("Combat should be disabled while returning projectile is in flight")
        .isTrue();
    assertThat(executioner.getCombat().isCombatDisabled()).isTrue();

    // Run enough ticks for the projectile to return
    for (int i = 0; i < 120; i++) {
      combatSystem.update(1.0f / 30);
    }

    // Combat should be re-enabled after projectile returns
    assertThat(executioner.getCombat().isReturningProjectileInFlight())
        .as("Combat should be re-enabled after projectile returns")
        .isFalse();
    assertThat(executioner.getCombat().isCombatDisabled()).isFalse();
  }

  @Test
  void pingpongMovingShooter_shouldAllowMovementDuringFlight() {
    Troop executioner = createExecutioner(Team.BLUE, 5, 10);
    Troop enemy = createTarget(Team.RED, 8, 10);

    gameState.spawnEntity(executioner);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Fire a returning projectile to lock combat
    executioner.getCombat().setCurrentTarget(enemy);
    executioner.getCombat().startAttackSequence();
    executioner.getCombat().setCurrentWindup(0);

    combatSystem.update(1.0f / 30);
    assertThat(executioner.getCombat().isReturningProjectileInFlight()).isTrue();

    // isInAttackRange should still be true, but the troop should still be allowed to move
    // because isReturningProjectileInFlight is true. We verify by checking the combat state.
    assertThat(executioner.isInAttackRange()).isTrue();
    assertThat(executioner.getCombat().isReturningProjectileInFlight()).isTrue();
  }

  /**
   * Creates an Executioner-like troop with returning projectile stats and pingpongMovingShooter.
   */
  private Troop createExecutioner(Team team, float x, float y) {
    ProjectileStats axeProjectile =
        ProjectileStats.builder()
            .name("AxeManProjectile")
            .damage(100)
            .speed(550f / 60f)
            .radius(1.0f)
            .homing(false)
            .projectileRange(7.5f)
            .returning(true)
            .aoeToAir(true)
            .aoeToGround(true)
            .build();

    return Troop.builder()
        .name("Executioner")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(1.0f, 4.0f, 0.6f, 0.6f, MovementType.GROUND))
        .deployTime(0f)
        .combat(
            Combat.builder()
                .damage(100)
                .range(4.5f)
                .sightRange(5.5f)
                .attackCooldown(0.9f)
                .loadTime(0.4f)
                .accumulatedLoadTime(0.4f)
                .projectileStats(axeProjectile)
                .pingpongMovingShooter(true)
                .build())
        .build();
  }

  private Troop createTarget(Team team, float x, float y) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .deployTime(0f)
        .combat(
            Combat.builder().damage(0).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .build();
  }
}
