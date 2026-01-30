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
}
