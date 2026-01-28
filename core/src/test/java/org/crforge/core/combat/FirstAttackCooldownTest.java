package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirstAttackCooldownTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void unitShouldUseFirstAttackCooldownInitially() {
    // Unit with 2.0s first attack, 1.0s normal attack
    float firstAttackCooldown = 2.0f;
    float attackCooldown = 1.0f;

    Troop attacker = Troop.builder()
        .name("Attacker")
        .team(Team.BLUE)
        .position(new Position(0, 0))
        .health(new Health(100))
        .deployTime(0f)
        .combat(Combat.builder()
            .damage(10)
            .range(1.0f)
            .firstAttackCooldown(firstAttackCooldown)
            .attackCooldown(attackCooldown)
            .build())
        .build();

    Troop target = Troop.builder()
        .name("Target")
        .team(Team.RED)
        .position(new Position(1, 0))
        .health(new Health(100))
        .deployTime(0f)
        .build();
    target.onSpawn();
    attacker.onSpawn();

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    // 1. Acquire Target
    attacker.getCombat().setCurrentTarget(target);

    // 2. Initial Update - Should Start Loading with First Attack Time
    // Note: In GameEngine, entity.update() runs BEFORE combatSystem.update().
    // So in this first tick:
    // a. attacker.update(): Timer is 0. No change.
    // b. combatSystem.update(): Sees valid target, starts attack, sets timer to 2.0s.
    attacker.update(0.1f);
    combatSystem.update(0.1f);

    assertThat(attacker.getCombat().isLoading()).isTrue();
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    // Timer was set at end of tick, so it hasn't decremented yet. It is exactly 2.0f.
    assertThat(attacker.getCombat().getCurrentLoadTime()).isEqualTo(2.0f);

    // 3. Fast Forward 1.0s - Should still be loading
    // Run 10 ticks of 0.1s -> 1.0s total decrement
    for (int i = 0; i < 10; i++) {
      attacker.update(0.1f); // Decrements timer
      combatSystem.update(0.1f);
    }
    // Remaining time: 2.0 - 1.0 = 1.0s
    assertThat(attacker.getCombat().getCurrentLoadTime()).isCloseTo(1.0f, within(0.001f));
    assertThat(attacker.getCombat().isLoading()).isTrue();
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    // 4. Fast Forward another 1.1s (11 ticks) - Should have attacked
    // We run 11 ticks (1.1s) to ensure the 1.0s remaining is fully consumed and we pass 0.
    for (int i = 0; i < 11; i++) {
      attacker.update(0.1f);
      combatSystem.update(0.1f);
    }

    // Timer reached 0, attack executed, state reset
    assertThat(attacker.getCombat().isLoading()).isFalse();
    assertThat(attacker.getCombat().isAttacking()).isFalse();
    assertThat(target.getHealth().getCurrent()).isEqualTo(90);

    // 5. Next Attack - Should use standard cooldown (1.0s) + default load (0s)
    // Cooldown is 1.0s now.
    // Run 11 ticks (1.1s) to clear cooldown and execute next attack
    for (int i = 0; i < 11; i++) {
      attacker.update(0.1f);
      combatSystem.update(0.1f);
    }

    assertThat(target.getHealth().getCurrent()).isEqualTo(80);
    assertThat(attacker.getCombat().isFirstAttackOnTarget()).isFalse();
  }
}
