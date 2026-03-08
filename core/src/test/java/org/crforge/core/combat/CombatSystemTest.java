package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CombatSystemTest {

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
  void meleeAttack_shouldDealDamageAfterWindup() {
    Troop attacker = createMeleeTroop(Team.BLUE, 5, 5, 50);
    Troop target = createMeleeTroop(Team.RED, 6, 5, 100);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Manually finish deploy
    attacker.update(2.0f);
    target.update(2.0f);

    // Set target
    attacker.getCombat().setCurrentTarget(target);

    // 1. Start Attack logic (sets isAttacking=true, windup=1.0s)
    combatSystem.update(0.1f);
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    // 2. Wait for windup (Attack Cooldown is 1.0s)
    // Need at least 1.0s of updates. 11 ticks of 0.1s = 1.1s is safe.
    runCombatUpdates(1.1f);

    assertThat(target.getHealth().getCurrent()).isEqualTo(50);
  }

  @Test
  void projectile_shouldDealDamageOnHit() {
    Troop attacker = createRangedTroop(Team.BLUE, 5, 5, 50);
    Troop target = createMeleeTroop(Team.RED, 6, 5, 100); // Close enough for quick hit

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    attacker.update(2.0f);
    target.update(2.0f);
    attacker.getCombat().setCurrentTarget(target);

    // 1. Start Attack logic
    combatSystem.update(0.1f);
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    // 2. Wait for windup (1.0s) so projectile spawns
    runCombatUpdates(1.1f);

    // The projectile might hit and disappear within runCombatUpdates if target is close.
    // Instead of checking for projectile existence, we check for the result (damage).

    // 3. Ensure enough time for projectile travel (if it hasn't hit yet)
    for (int i = 0; i < 30; i++) {
      combatSystem.update(1.0f / 30f);
    }

    // Projectile should have hit and dealt damage
    assertThat(target.getHealth().getCurrent()).isLessThan(100);
  }

  @Test
  void canAttack_shouldCheckRange() {
    Troop attacker = createMeleeTroop(Team.BLUE, 5, 5, 50);
    Troop closeTarget = createMeleeTroop(Team.RED, 6, 5, 100);
    Troop farTarget = createMeleeTroop(Team.RED, 20, 20, 100);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(closeTarget);
    gameState.spawnEntity(farTarget);
    gameState.processPending();

    attacker.update(2.0f);
    closeTarget.update(2.0f);
    farTarget.update(2.0f);

    assertThat(combatSystem.canAttack(attacker, closeTarget)).isTrue();
    assertThat(combatSystem.canAttack(attacker, farTarget)).isFalse();
  }

  @Test
  void applySpellDamage_shouldDamageEnemiesInRadius() {
    Troop enemy1 = createMeleeTroop(Team.RED, 10f, 10f, 0);
    enemy1.getHealth().takeDamage(0);
    Troop enemy2 = createMeleeTroop(Team.RED, 11f, 10f, 0);

    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();

    enemy1.update(2.0f);
    enemy2.update(2.0f);

    combatSystem.applySpellDamage(Team.BLUE, 10f, 10f, 50, 3.0f, Collections.emptyList());

    assertThat(enemy1.getHealth().getCurrent()).isEqualTo(50);
    assertThat(enemy2.getHealth().getCurrent()).isEqualTo(50);
  }

  private void runCombatUpdates(float duration) {
    float dt = 0.1f;
    int ticks = (int) (duration / dt);
    for (int i = 0; i < ticks; i++) {
      // Update entities (decrements windup timers)
      for (org.crforge.core.entity.base.Entity e : gameState.getAliveEntities()) {
        e.update(dt);
      }
      // Update combat system (checks windup completion)
      combatSystem.update(dt);
    }
  }

  private Troop createMeleeTroop(Team team, float x, float y, int damage) {
    return Troop.builder()
        .name("Melee")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(100))
        .deployTime(1.0f)
        .combat(
            Combat.builder()
                .damage(damage)
                .range(1.5f)
                .sightRange(5.5f)
                .attackCooldown(1.0f)
                .loadTime(0f)
                .build())
        .build();
  }

  @Test
  void minimumRange_shouldPreventAttackOnCloseTarget() {
    // Mortar-like unit with minimumRange = 3.5
    Troop mortar = Troop.builder()
        .name("Mortar")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(500))
        .deployTime(1.0f)
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
        .combat(Combat.builder()
            .damage(100)
            .range(11.5f)
            .sightRange(11.5f)
            .attackCooldown(5.0f)
            .minimumRange(3.5f)
            .build())
        .build();

    // Close enemy (distance = 2.0, within minimum range)
    Troop closeEnemy = createMeleeTroop(Team.RED, 7, 5, 50);
    // Far enemy (distance = 8.0, outside minimum range but within max range)
    Troop farEnemy = createMeleeTroop(Team.RED, 13, 5, 50);

    gameState.spawnEntity(mortar);
    gameState.spawnEntity(closeEnemy);
    gameState.spawnEntity(farEnemy);
    gameState.processPending();

    mortar.update(2.0f);
    closeEnemy.update(2.0f);
    farEnemy.update(2.0f);

    // Close enemy is within minimumRange -- should NOT be attackable
    assertThat(combatSystem.canAttack(mortar, closeEnemy)).isFalse();
    // Far enemy is in valid range window
    assertThat(combatSystem.canAttack(mortar, farEnemy)).isTrue();
  }

  @Test
  void crownTowerDamagePercent_shouldReduceDamageToTower() {
    // Miner-like unit with -75 crownTowerDamagePercent
    Troop miner = Troop.builder()
        .name("Miner")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(1000))
        .deployTime(1.0f)
        .combat(Combat.builder()
            .damage(100)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .crownTowerDamagePercent(-75)
            .build())
        .build();

    // Create a tower as target
    Tower tower = Tower.createPrincessTower(Team.RED, 6, 5, 1);
    tower.onSpawn();
    int towerMaxHp = tower.getHealth().getMax();

    gameState.spawnEntity(miner);
    gameState.processPending();

    miner.update(2.0f);
    tower.update(2.0f);

    // Set target and run attack cycle
    miner.getCombat().setCurrentTarget(tower);
    combatSystem.update(0.1f); // Start attack
    runCombatUpdates(1.2f); // Wait for windup

    // Miner deals 100 base damage, 25% to towers = 25 damage
    // Tower health should be reduced by 25 (not 100)
    int expectedDamage = 25; // 100 * (100 + (-75)) / 100 = 25
    assertThat(tower.getHealth().getCurrent()).isEqualTo(towerMaxHp - expectedDamage);
  }

  @Test
  void crownTowerDamagePercent_shouldNotAffectNonTowerTargets() {
    // Miner attacks a regular troop -- full damage applies
    Troop miner = Troop.builder()
        .name("Miner")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(1000))
        .deployTime(1.0f)
        .combat(Combat.builder()
            .damage(100)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .crownTowerDamagePercent(-75)
            .build())
        .build();

    Troop enemy = createMeleeTroop(Team.RED, 6, 5, 50);

    gameState.spawnEntity(miner);
    gameState.spawnEntity(enemy);
    gameState.processPending();

    miner.update(2.0f);
    enemy.update(2.0f);

    miner.getCombat().setCurrentTarget(enemy);
    combatSystem.update(0.1f);
    runCombatUpdates(1.2f);

    // Full 100 damage to non-tower target (100 HP - 100 = 0, enemy is dead)
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(0);
  }

  private Troop createRangedTroop(Team team, float x, float y, int damage) {
    return Troop.builder()
        .name("Ranged")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(100))
        .deployTime(1.0f)
        .combat(
            Combat.builder()
                .damage(damage)
                .range(6.0f)
                .sightRange(6.0f)
                .attackCooldown(1.0f)
                .build())
        .build();
  }

  @Test
  void shieldAbsorbsDamageBeforeHealth() {
    // Unit with shield: shield absorbs damage first
    Troop shielded = Troop.builder()
        .name("ShieldedUnit")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(100, 50)) // 100 HP + 50 shield
        .deployTime(0f)
        .build();

    gameState.spawnEntity(shielded);
    gameState.processPending();

    // Take 30 damage -> shield absorbs it (50 -> 20)
    shielded.getHealth().takeDamage(30);
    assertThat(shielded.getHealth().getShield()).isEqualTo(20);
    assertThat(shielded.getHealth().getCurrent()).isEqualTo(100);

    // Take 40 damage -> shield breaks (20), but no overflow into HP
    shielded.getHealth().takeDamage(40);
    assertThat(shielded.getHealth().getShield()).isEqualTo(0);
    assertThat(shielded.getHealth().getCurrent()).isEqualTo(100);

    // Next hit goes directly to health
    shielded.getHealth().takeDamage(40);
    assertThat(shielded.getHealth().getCurrent()).isEqualTo(60);
  }

  @Test
  void buffOnDamage_shouldApplyToTargetOnMeleeHit() {
    // Melee attacker with buff-on-damage (e.g. stun on hit)
    EffectStats stunOnHit = EffectStats.builder()
        .type(StatusEffectType.STUN)
        .duration(0.5f)
        .build();

    Troop attacker = Troop.builder()
        .name("Stunner")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(500))
        .deployTime(0f)
        .combat(Combat.builder()
            .damage(50)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .buffOnDamage(stunOnHit)
            .build())
        .build();

    Troop target = Troop.builder()
        .name("Target")
        .team(Team.RED)
        .position(new Position(5.5f, 5))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Set up target
    attacker.getCombat().setCurrentTarget(target);
    attacker.getCombat().startAttackSequence();
    attacker.getCombat().setCurrentWindup(0); // Skip windup

    combatSystem.update(1.0f / 30);

    // Target should have taken damage and received stun
    assertThat(target.getHealth().getCurrent()).isEqualTo(450);
    assertThat(target.getAppliedEffects()).hasSize(1);
    assertThat(target.getAppliedEffects().get(0).getType()).isEqualTo(StatusEffectType.STUN);
  }

  @Test
  void multipleTargets_shouldAttackExtraEnemies() {
    // Ranged attacker with multipleTargets=2 (like EWiz)
    Troop attacker = Troop.builder()
        .name("EWiz")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(500))
        .deployTime(0f)
        .combat(Combat.builder()
            .damage(46)
            .range(5.0f)
            .sightRange(5.5f)
            .attackCooldown(1.8f)
            .multipleTargets(2)
            .build())
        .build();

    Troop target1 = Troop.builder()
        .name("Target1")
        .team(Team.RED)
        .position(new Position(8, 5))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    Troop target2 = Troop.builder()
        .name("Target2")
        .team(Team.RED)
        .position(new Position(9, 5))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    Troop target3 = Troop.builder()
        .name("Target3")
        .team(Team.RED)
        .position(new Position(50, 50))
        .health(new Health(500))
        .deployTime(0f)
        .build();

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target1);
    gameState.spawnEntity(target2);
    gameState.spawnEntity(target3);
    gameState.processPending();

    // Set up target
    attacker.getCombat().setCurrentTarget(target1);
    attacker.getCombat().startAttackSequence();
    attacker.getCombat().setCurrentWindup(0); // Skip windup

    combatSystem.update(1.0f / 30);

    // Should have spawned 2 projectiles (primary + 1 extra), NOT 3 (target3 is out of range)
    // EWiz is ranged, so projectiles are spawned
    assertThat(gameState.getProjectiles()).hasSize(2);
  }

  @Test
  void chainLightning_shouldDamageChainTargets() {
    // Ranged attacker with chain lightning (like ElectroDragon)
    Troop attacker = Troop.builder()
        .name("ElectroDragon")
        .team(Team.BLUE)
        .position(new Position(5, 5))
        .health(new Health(500))
        .deployTime(0f)
        .combat(Combat.builder()
            .damage(75)
            .range(3.5f)
            .sightRange(5.5f)
            .attackCooldown(2.1f)
            .projectileStats(org.crforge.core.card.ProjectileStats.builder()
                .name("ElectroDragonProjectile")
                .damage(75)
                .speed(10f)
                .chainedHitRadius(4.0f)
                .chainedHitCount(3)
                .build())
            .build())
        .build();

    Troop target1 = createChainTarget(Team.RED, 7, 5);
    Troop target2 = createChainTarget(Team.RED, 8, 5);
    Troop target3 = createChainTarget(Team.RED, 9, 5);
    // 4th target within chain radius -- should NOT be hit since chainedHitCount=3 means 3 total
    Troop target4 = createChainTarget(Team.RED, 10, 5);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target1);
    gameState.spawnEntity(target2);
    gameState.spawnEntity(target3);
    gameState.spawnEntity(target4);
    gameState.processPending();

    // Set target and fire
    attacker.getCombat().setCurrentTarget(target1);
    attacker.getCombat().startAttackSequence();
    attacker.getCombat().setCurrentWindup(0);

    // Run enough ticks for primary and chain projectiles to hit
    for (int i = 0; i < 60; i++) {
      combatSystem.update(1.0f / 30);
    }

    // Primary target should have taken 75 damage
    assertThat(target1.getHealth().getCurrent()).isEqualTo(500 - 75);

    // chainedHitCount=3 means 3 total enemies hit (1 primary + 2 chains)
    // target2 and target3 are the 2 closest chain candidates
    assertThat(target2.getHealth().getCurrent()).isEqualTo(500 - 75);
    assertThat(target3.getHealth().getCurrent()).isEqualTo(500 - 75);

    // target4 is within chain radius but should NOT be hit (chain limit reached)
    assertThat(target4.getHealth().getCurrent()).isEqualTo(500);
  }

  private Troop createChainTarget(Team team, float x, float y) {
    return Troop.builder()
        .name("ChainTarget")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .deployTime(0f)
        .combat(Combat.builder()
            .damage(0)
            .range(1.5f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .build())
        .build();
  }
}
