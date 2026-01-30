package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CombatRangeTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    combatSystem = new CombatSystem(gameState);
  }

  @Test
  void isRangedShouldDeriveFromRange() {
    // Melee Short
    Combat c1 = Combat.builder().range(0.8f).build();
    assertThat(c1.isRanged()).isFalse();

    // Melee Medium
    Combat c2 = Combat.builder().range(1.2f).build();
    assertThat(c2.isRanged()).isFalse();

    // Melee Long
    Combat c3 = Combat.builder().range(1.6f).build();
    assertThat(c3.isRanged()).isFalse();

    // Ranged (Minions)
    Combat c4 = Combat.builder().range(2.0f).build();
    assertThat(c4.isRanged()).isTrue();

    // Ranged (Musketeer)
    Combat c5 = Combat.builder().range(6.0f).build();
    assertThat(c5.isRanged()).isTrue();
  }

  @Test
  void shortMeleeShouldNotAttackOutOfRange() {
    // 0.8 range (Short Melee)
    Troop attacker = createTroop(Team.BLUE, 10f, 10f, 0.8f);
    // Target at distance 1.0 (center to center distance)
    // Both have 0 collision radius in this test helper
    Troop target = createTroop(Team.RED, 11f, 10f, 0.8f);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    attacker.getCombat().setCurrentTarget(target);

    // Simulate enough time to potentially attack
    runCombatUpdates(2.0f);

    // Should NOT have attacked (HP remains 100) because 1.0 > 0.8 (effective range with 0 radii)
    assertThat(target.getHealth().getCurrent()).isEqualTo(100);
  }

  @Test
  void mediumMeleeShouldAttackAtDistanceOne() {
    // 1.2 range (Medium Melee, e.g. Knight)
    Troop attacker = createTroop(Team.BLUE, 10f, 10f, 1.2f);
    // Target at distance 1.0
    Troop target = createTroop(Team.RED, 11f, 10f, 0.8f);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    attacker.getCombat().setCurrentTarget(target);

    runCombatUpdates(2.0f);

    // Should HAVE attacked (HP < 100) because 1.0 <= 1.2
    assertThat(target.getHealth().getCurrent()).isLessThan(100);
  }

  @Test
  void longMeleeShouldAttackAtDistanceOnePointFive() {
    // 1.6 range (Long Melee, e.g. Prince)
    Troop attacker = createTroop(Team.BLUE, 10f, 10f, 1.6f);
    // Target at distance 1.5
    Troop target = createTroop(Team.RED, 11.5f, 10f, 0.8f);

    gameState.spawnEntity(attacker);
    gameState.spawnEntity(target);
    gameState.processPending();

    attacker.getCombat().setCurrentTarget(target);

    runCombatUpdates(2.0f);

    // Should HAVE attacked because 1.5 <= 1.6
    assertThat(target.getHealth().getCurrent()).isLessThan(100);
  }

  private void runCombatUpdates(float duration) {
    float dt = 0.1f;
    int ticks = (int) (duration / dt);
    for (int i = 0; i < ticks; i++) {
      // We need to update entities (for timers) AND combat system
      for (org.crforge.core.entity.base.Entity e : gameState.getAliveEntities()) {
        e.update(dt);
      }
      combatSystem.update(dt);
    }
  }

  private Troop createTroop(Team team, float x, float y, float range) {
    Troop troop = Troop.builder()
        .name("TestTroop")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(100))
        // Create troops with size 0 to test raw range
        .movement(new Movement(0f, 0f, 0f, 0f, MovementType.GROUND)) // Size 0 for range testing
        .deployTime(0f)
        .combat(Combat.builder()
            .damage(10)
            .range(range)
            .attackCooldown(1.0f)
            .loadTime(0f) // Instant windup for this test
            .build())
        .build();
    troop.onSpawn();
    return troop;
  }
}
