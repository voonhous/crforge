package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
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
  void meleeAttack_shouldDealDamageImmediately() {
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

    // Run combat update
    combatSystem.update(1.0f / 30f);

    // Target should have taken damage
    assertThat(target.getHealth().getCurrent()).isEqualTo(50);
  }

  @Test
  void rangedAttack_shouldSpawnProjectile() {
    Troop attacker = createRangedTroop(Team.BLUE, 5, 5, 50);
    Troop target = createMeleeTroop(Team.RED, 10, 5, 100);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Finish deploy
    attacker.update(2.0f);
    target.update(2.0f);

    attacker.getCombat().setCurrentTarget(target);

    // Run combat update
    combatSystem.update(1.0f / 30f);

    // Projectile should have been spawned
    assertThat(gameState.getProjectiles()).hasSize(1);
    assertThat(gameState.getProjectiles().get(0).getDamage()).isEqualTo(50);
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

    // Spawn projectile
    combatSystem.update(1.0f / 30f);
    assertThat(gameState.getProjectiles()).hasSize(1);

    // Update until projectile hits (should be fast since target is close)
    for (int i = 0; i < 30; i++) {
      combatSystem.update(1.0f / 30f);
    }

    // Projectile should have hit and dealt damage
    assertThat(target.getHealth().getCurrent()).isLessThan(100);
  }

  @Test
  void cooldown_shouldPreventImmediateReattack() {
    Troop attacker = createMeleeTroop(Team.BLUE, 5, 5, 10);
    Troop target = createMeleeTroop(Team.RED, 6, 5, 100);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    attacker.update(2.0f);
    target.update(2.0f);
    attacker.getCombat().setCurrentTarget(target);

    // First attack
    combatSystem.update(1.0f / 30f);
    assertThat(target.getHealth().getCurrent()).isEqualTo(90);

    // Immediate second attempt should not attack (on cooldown)
    combatSystem.update(1.0f / 30f);
    assertThat(target.getHealth().getCurrent()).isEqualTo(90);

    // After cooldown (1 second), should attack again
    attacker.update(1.0f);
    combatSystem.update(1.0f / 30f);
    assertThat(target.getHealth().getCurrent()).isEqualTo(80);
  }

  @Test
  void aoeAttack_shouldDamageMultipleTargets() {
    Troop attacker = createAoeTroop(Team.BLUE, 5, 5, 25, 3.0f);
    Troop target1 = createMeleeTroop(Team.RED, 6, 5, 100);
    Troop target2 = createMeleeTroop(Team.RED, 6.5f, 5, 100);
    Troop farTarget = createMeleeTroop(Team.RED, 15, 15, 100); // Out of AOE range

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target1);
    gameState.spawnEntity(target2);
    gameState.spawnEntity(farTarget);
    gameState.processPending();

    attacker.update(2.0f);
    target1.update(2.0f);
    target2.update(2.0f);
    farTarget.update(2.0f);

    attacker.getCombat().setCurrentTarget(target1);

    combatSystem.update(1.0f / 30f);

    // Both close targets should be damaged
    assertThat(target1.getHealth().getCurrent()).isEqualTo(75);
    assertThat(target2.getHealth().getCurrent()).isEqualTo(75);
    // Far target should be unharmed
    assertThat(farTarget.getHealth().getCurrent()).isEqualTo(100);
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
  void canAttack_shouldNotAttackAllies() {
    Troop attacker = createMeleeTroop(Team.BLUE, 5, 5, 50);
    Troop ally = createMeleeTroop(Team.BLUE, 6, 5, 100);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(ally);
    gameState.processPending();

    attacker.update(2.0f);
    ally.update(2.0f);

    assertThat(combatSystem.canAttack(attacker, ally)).isFalse();
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
                .ranged(false)
                .build())
        .build();
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
                .ranged(true)
                .build())
        .build();
  }

  private Troop createAoeTroop(Team team, float x, float y, int damage, float aoeRadius) {
    return Troop.builder()
        .name("AOE")
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
                .ranged(false)
                .aoeRadius(aoeRadius)
                .build())
        .build();
  }
}
