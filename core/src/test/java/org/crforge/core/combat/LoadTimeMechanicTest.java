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

class LoadTimeMechanicTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void loadTimeShouldReduceFirstAttackDelay() {
    float attackCooldown = 1.2f;
    float loadTime = 0.7f;

    Troop attacker = Troop.builder()
        .name("Knight")
        .team(Team.BLUE)
        .position(new Position(0, 0))
        .health(new Health(100))
        .deployTime(1.0f)
        .combat(Combat.builder()
            .damage(10)
            .range(1.0f)
            .attackCooldown(attackCooldown)
            .loadTime(loadTime)
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

    // 1. Simulate Deploy Phase
    float deployStep = 0.1f;
    for(float t=0; t<1.0f; t+=deployStep) {
      attacker.update(deployStep);
    }

    assertThat(attacker.isDeploying()).isFalse();
    assertThat(attacker.getCombat().getAccumulatedLoadTime()).isEqualTo(loadTime);

    // 2. Acquire Target
    attacker.getCombat().setCurrentTarget(target);

    // 3. Start Combat
    // Tick 1: Starts Attack logic
    combatSystem.update(0.1f);

    assertThat(attacker.getCombat().isAttacking()).isTrue();

    assertThat(attacker.getCombat().getCurrentWindup()).isCloseTo(0.5f, within(0.001f));

    // 4. Wait 0.5s for First Hit
    // We need 6 ticks to be safe (5 * 0.1 = 0.5 exactly, but float precision/order might mean 6th tick executes)
    for(int i=0; i<6; i++) {
      attacker.update(0.1f); // Decrement windup
      combatSystem.update(0.1f); // Check execute
    }

    assertThat(target.getHealth().getCurrent()).isEqualTo(90);
    assertThat(attacker.getCombat().isAttacking()).isFalse();

    // 5. Check Next Attack Cycle
    assertThat(attacker.getCombat().getAccumulatedLoadTime()).isEqualTo(0);

    combatSystem.update(0.1f);
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    assertThat(attacker.getCombat().getCurrentWindup()).isCloseTo(1.2f, within(0.001f));
  }
}
