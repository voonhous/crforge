package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.ability.DashAbility;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnockbackTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private PhysicsSystem physicsSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState, new AoeDamageService(gameState));
    Arena arena = new Arena("TestArena");
    physicsSystem = new PhysicsSystem(arena);
    physicsSystem.setGameState(gameState);
  }

  @Test
  void aoeProjectile_shouldKnockbackEnemiesAwayFromImpact() {
    // Place two enemy troops near the impact center
    Troop enemy1 = createTroop(Team.RED, 10f, 16f, false);
    Troop enemy2 = createTroop(Team.RED, 10f, 18f, false);

    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();
    enemy1.update(2.0f);
    enemy2.update(2.0f);

    float impactX = 10f;
    float impactY = 17f;

    // Create a position-targeted AOE projectile with pushback (like Fireball)
    Projectile fireball =
        new Projectile(
            Team.BLUE,
            impactX,
            impactY - 10f,
            impactX,
            impactY,
            100,
            2.5f,
            10f,
            Collections.emptyList());
    fireball.setPushback(1.0f); // 1 tile knockback

    // Advance projectile to hit
    gameState.spawnProjectile(fireball);
    while (fireball.isActive()) {
      combatSystem.update(1.0f / 30f);
    }

    // Both enemies should now be in knockback state
    assertThat(enemy1.getMovement().isKnockedBack()).isTrue();
    assertThat(enemy2.getMovement().isKnockedBack()).isTrue();

    // Record positions before physics tick
    float enemy1YBefore = enemy1.getPosition().getY();
    float enemy2YBefore = enemy2.getPosition().getY();

    // Tick physics to apply knockback displacement
    physicsSystem.update(gameState.getAliveEntities(), 1.0f / 30f);

    // enemy1 is below impact (y < impactY), should be pushed further down (negative Y)
    assertThat(enemy1.getPosition().getY()).isLessThan(enemy1YBefore);

    // enemy2 is above impact (y > impactY), should be pushed further up (positive Y)
    assertThat(enemy2.getPosition().getY()).isGreaterThan(enemy2YBefore);
  }

  @Test
  void ignorePushback_shouldPreventKnockback() {
    Troop immune = createTroop(Team.RED, 10f, 16f, true);

    gameState.spawnEntity(immune);
    gameState.processPending();
    immune.update(2.0f);

    float impactX = 10f;
    float impactY = 17f;

    Projectile fireball =
        new Projectile(
            Team.BLUE,
            impactX,
            impactY - 10f,
            impactX,
            impactY,
            100,
            2.5f,
            10f,
            Collections.emptyList());
    fireball.setPushback(1.0f);

    gameState.spawnProjectile(fireball);
    while (fireball.isActive()) {
      combatSystem.update(1.0f / 30f);
    }

    // Entity with ignorePushback should NOT be knocked back
    assertThat(immune.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void buildings_shouldBeImmuneToKnockback() {
    Building building =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(10f, 16f))
            .health(new Health(500))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .lifetime(30f)
            .remainingLifetime(30f)
            .deployTime(1.0f)
            .deployTimer(0f) // already deployed
            .build();

    gameState.spawnEntity(building);
    gameState.processPending();

    float impactX = 10f;
    float impactY = 17f;

    Projectile fireball =
        new Projectile(
            Team.BLUE,
            impactX,
            impactY - 10f,
            impactX,
            impactY,
            100,
            2.5f,
            10f,
            Collections.emptyList());
    fireball.setPushback(1.0f);

    gameState.spawnProjectile(fireball);
    while (fireball.isActive()) {
      combatSystem.update(1.0f / 30f);
    }

    // Building should not have knockback state (Movement has no knockback)
    assertThat(building.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void combatDisabledDuringKnockback() {
    Troop attacker = createTroop(Team.RED, 10f, 16f, false);
    Troop target = createTroop(Team.BLUE, 11f, 16f, false);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();
    attacker.update(2.0f);
    target.update(2.0f);

    // Manually start knockback on the attacker
    attacker.getMovement().startKnockback(0f, -1f, 1.0f, 0.5f, 1.0f);
    assertThat(attacker.getMovement().isKnockedBack()).isTrue();

    // Set up attack: attacker targets enemy, ready to fire
    attacker.getCombat().setCurrentTarget(target);
    attacker.getCombat().startAttackSequence();
    attacker.getCombat().setCurrentWindup(0); // Skip windup

    int hpBefore = target.getHealth().getCurrent();

    // Process combat -- attacker should be blocked by knockback
    combatSystem.update(1.0f / 30f);

    // Target should not have taken damage
    assertThat(target.getHealth().getCurrent()).isEqualTo(hpBefore);
  }

  @Test
  void knockbackExpires_afterDuration() {
    Troop troop = createTroop(Team.RED, 10f, 16f, false);

    gameState.spawnEntity(troop);
    gameState.processPending();
    troop.update(2.0f);

    troop.getMovement().startKnockback(0f, 1f, 1.0f, 0.5f, 1.0f);
    assertThat(troop.getMovement().isKnockedBack()).isTrue();

    // Tick physics for slightly more than the knockback duration (0.5s)
    // 16 frames at 1/30s = 0.533s, safely past the 0.5s knockback duration
    for (int i = 0; i < 16; i++) {
      physicsSystem.update(gameState.getAliveEntities(), 1.0f / 30f);
    }

    // After > 0.5s, knockback should have expired
    assertThat(troop.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void nonAoeProjectile_shouldOnlyKnockbackDirectTarget() {
    // Direct target and a nearby bystander
    Troop directTarget = createTroop(Team.RED, 10f, 16f, false);
    Troop bystander = createTroop(Team.RED, 10.5f, 16f, false);

    Troop source = createTroop(Team.BLUE, 10f, 10f, false);

    gameState.spawnEntity(source);
    gameState.spawnEntity(directTarget);
    gameState.spawnEntity(bystander);
    gameState.processPending();
    source.update(2.0f);
    directTarget.update(2.0f);
    bystander.update(2.0f);

    // Create entity-targeted projectile with no AOE radius but with pushback
    Projectile proj = new Projectile(source, directTarget, 50, 0f, 15f, Collections.emptyList());
    proj.setPushback(1.0f);

    gameState.spawnProjectile(proj);

    // Advance until hit
    for (int i = 0; i < 60; i++) {
      combatSystem.update(1.0f / 30f);
    }

    // Only direct target should be knocked back
    assertThat(directTarget.getMovement().isKnockedBack()).isTrue();
    assertThat(bystander.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void dashLanding_shouldKnockbackEnemies() {
    // Create a MegaKnight-like dasher with AOE dash and pushback
    AbilityData dashAbility = new DashAbility(200, 4f, 5f, 2.0f, 0f, 0f, 0f, 0f, 1.0f);

    Movement dasherMovement = new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND);
    Troop dasher =
        Troop.builder()
            .name("MegaKnightTest")
            .team(Team.BLUE)
            .position(new Position(10f, 16f))
            .health(new Health(3000))
            .movement(dasherMovement)
            .combat(
                Combat.builder()
                    .damage(200)
                    .range(1.5f)
                    .sightRange(7.5f)
                    .attackCooldown(1.7f)
                    .build())
            .ability(new AbilityComponent(dashAbility))
            .deployTime(1.0f)
            .deployTimer(1.0f)
            .build();

    // Create two enemies near the dasher's position (within dash AOE radius)
    Troop enemy1 = createTroop(Team.RED, 10f, 15f, false);
    Troop enemy2 = createTroop(Team.RED, 11f, 16f, false);

    gameState.spawnEntity(dasher);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();
    dasher.update(2.0f);
    enemy1.update(2.0f);
    enemy2.update(2.0f);

    AbilitySystem abilitySystem = new AbilitySystem(gameState);

    // Set up dash state: simulate arriving at target (DASHING -> LANDING transition)
    AbilityComponent abilityComp = dasher.getAbility();
    abilityComp.setDashState(AbilityComponent.DashState.DASHING);
    abilityComp.setDashTargetX(dasher.getPosition().getX());
    abilityComp.setDashTargetY(dasher.getPosition().getY());
    abilityComp.setDashSpeed(15f);
    dasher
        .getCombat()
        .setCombatDisabled(org.crforge.core.component.ModifierSource.ABILITY_DASH, true);

    // Tick the ability system -- dasher is at target, so it transitions to LANDING
    // and applyDashDamage fires
    abilitySystem.update(1.0f / 30f);

    // Both enemies should be in knockback state
    assertThat(enemy1.getMovement().isKnockedBack())
        .as("enemy1 should be knocked back by dash landing")
        .isTrue();
    assertThat(enemy2.getMovement().isKnockedBack())
        .as("enemy2 should be knocked back by dash landing")
        .isTrue();

    // Enemies should have taken dash damage
    assertThat(enemy1.getHealth().getCurrent()).isLessThan(500);
    assertThat(enemy2.getHealth().getCurrent()).isLessThan(500);
  }

  @Test
  void dashLanding_shouldNotKnockbackPushbackImmuneEntities() {
    AbilityData dashAbility = new DashAbility(200, 4f, 5f, 2.0f, 0f, 0f, 0f, 0f, 1.0f);

    Movement dasherMovement = new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND);
    Troop dasher =
        Troop.builder()
            .name("MegaKnightTest")
            .team(Team.BLUE)
            .position(new Position(10f, 16f))
            .health(new Health(3000))
            .movement(dasherMovement)
            .combat(
                Combat.builder()
                    .damage(200)
                    .range(1.5f)
                    .sightRange(7.5f)
                    .attackCooldown(1.7f)
                    .build())
            .ability(new AbilityComponent(dashAbility))
            .deployTime(1.0f)
            .deployTimer(1.0f)
            .build();

    // Immune enemy (ignorePushback = true)
    Troop immune = createTroop(Team.RED, 10f, 15f, true);

    gameState.spawnEntity(dasher);
    gameState.spawnEntity(immune);
    gameState.processPending();
    dasher.update(2.0f);
    immune.update(2.0f);

    AbilitySystem abilitySystem = new AbilitySystem(gameState);

    AbilityComponent abilityComp = dasher.getAbility();
    abilityComp.setDashState(AbilityComponent.DashState.DASHING);
    abilityComp.setDashTargetX(dasher.getPosition().getX());
    abilityComp.setDashTargetY(dasher.getPosition().getY());
    abilityComp.setDashSpeed(15f);

    abilitySystem.update(1.0f / 30f);

    // Immune entity should take damage but NOT be knocked back
    assertThat(immune.getHealth().getCurrent()).isLessThan(500);
    assertThat(immune.getMovement().isKnockedBack()).isFalse();
  }

  /** Creates a simple troop with movement for knockback testing. */
  private Troop createTroop(Team team, float x, float y, boolean ignorePushback) {
    Movement movement = new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND);
    movement.setIgnorePushback(ignorePushback);

    return Troop.builder()
        .name("TestTroop")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .movement(movement)
        .combat(
            Combat.builder().damage(50).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }
}
