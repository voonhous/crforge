package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.engine.DeploymentSystem;
import org.crforge.core.engine.EntityTimerSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LoadTimeMechanicTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
  }

  @Test
  void loadTimeShouldReduceFirstAttackDelay() {
    float attackCooldown = 1.2f;
    float loadTime = 0.7f;

    Troop attacker =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(0, 0))
            .health(new Health(100))
            .deployTime(1.0f)
            .combat(
                Combat.builder()
                    .damage(10)
                    .range(1.0f)
                    .attackCooldown(attackCooldown)
                    .loadTime(loadTime)
                    .build())
            .build();

    Troop target =
        Troop.builder()
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
    // Use combatSystem.update() which ticks combat timers (load time accumulation)
    // and skips combat decisions for deploying entities.
    EntityTimerSystem entityTimerSystem = new EntityTimerSystem();
    float deployStep = 0.1f;
    for (float t = 0; t < 1.0f; t += deployStep) {
      entityTimerSystem.update(gameState.getAliveEntities(), deployStep);
      combatSystem.update(deployStep);
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
    // We need 6 ticks to be safe (5 * 0.1 = 0.5 exactly, but float precision/order might mean 6th
    // tick executes)
    for (int i = 0; i < 6; i++) {
      combatSystem.update(0.1f); // Ticks combat timers and checks execute
    }

    assertThat(target.getHealth().getCurrent()).isEqualTo(90);
    assertThat(attacker.getCombat().isAttacking()).isFalse();

    // 5. Check Next Attack Cycle
    assertThat(attacker.getCombat().getAccumulatedLoadTime()).isEqualTo(0);

    combatSystem.update(0.1f);
    assertThat(attacker.getCombat().isAttacking()).isTrue();

    // CombatSystem now ticks combat timers before processing, so 0.1f of load time
    // is accumulated before startAttackSequence: windup = max(0, 1.2 - 0.1) = 1.1
    assertThat(attacker.getCombat().getCurrentWindup()).isCloseTo(1.1f, within(0.001f));
  }

  /**
   * Tests for the "preloaded" behaviour described in: https://royaleapi.com/blog/secret-stats
   *
   * <p>Troops enter the arena with their Load Time already fully charged, so their first attack
   * windup is max(0, attackCooldown - loadTime) rather than the full attackCooldown. Sparky is the
   * only exception (noPreload = true).
   */
  @Nested
  class PreloadBehavior {

    private DeploymentSystem deploymentSystem;

    @BeforeEach
    void setUpDeployment() {
      deploymentSystem =
          new DeploymentSystem(
              gameState, new AoeDamageService(gameState, new DefaultCombatAbilityBridge()));
    }

    private void deployTroops(TroopStats stats) {
      Card card =
          Card.builder()
              .name(stats.getName())
              .cost(1)
              .type(CardType.TROOP)
              .unitStats(stats)
              .build();
      // Fill all 8 slots with the same card - Hand shuffles on init, so this ensures
      // whichever slot ends up at index 0 will always have troops defined.
      List<Card> cards = new ArrayList<>(Collections.nCopies(8, card));
      Player player = new Player(Team.BLUE, new Deck(cards), false);
      deploymentSystem.queueAction(
          player, PlayerActionDTO.builder().handIndex(0).x(9f).y(9f).build());
      deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
      gameState.processPending();
    }

    private Troop getFirstTroop() {
      return gameState.getEntities().stream()
          .filter(e -> e instanceof Troop)
          .map(e -> (Troop) e)
          .findFirst()
          .orElseThrow(() -> new AssertionError("No troop found in game state"));
    }

    @Test
    void troopIsPreloadedImmediatelyAtSpawn() {
      float loadTime = 0.7f;
      deployTroops(
          TroopStats.builder()
              .name("Knight")
              .health(100)
              .damage(10)
              .range(1.0f)
              .attackCooldown(1.2f)
              .loadTime(loadTime)
              .deployTime(1.0f)
              .build());

      Troop troop = getFirstTroop();

      // Preloaded immediately - before any deploy ticks
      assertThat(troop.isDeploying()).isTrue();
      assertThat(troop.getCombat().getAccumulatedLoadTime()).isEqualTo(loadTime);
    }

    @Test
    void unitWithLoadTimeExceedingDeployTimeGetsCorrectFirstAttack() {
      // Regression: with the old accumulate-during-deploy approach, a unit with
      // loadTime=2.8 and deployTime=1.0 would only charge 1.0s -> windup=2.0s.
      // Correct behaviour: fully preloaded -> windup = max(0, 3.0 - 2.8) = 0.2s.
      float loadTime = 2.8f;
      float attackCooldown = 3.0f;
      float deployTime = 1.0f;

      deployTroops(
          TroopStats.builder()
              .name("Balloon")
              .health(100)
              .damage(10)
              .range(1.5f)
              .attackCooldown(attackCooldown)
              .loadTime(loadTime)
              .deployTime(deployTime)
              .build());

      Troop attacker = getFirstTroop();

      // Finish deploy phase
      EntityTimerSystem entityTimerSystem = new EntityTimerSystem();
      for (float t = 0; t < deployTime; t += 0.1f) {
        entityTimerSystem.update(java.util.List.of(attacker), 0.1f);
      }
      assertThat(attacker.isDeploying()).isFalse();
      assertThat(attacker.getCombat().getAccumulatedLoadTime()).isCloseTo(loadTime, within(0.001f));

      // Place target in range and trigger attack
      Troop target =
          Troop.builder()
              .name("Target")
              .team(Team.RED)
              .position(new Position(9f, 10f))
              .health(new Health(100))
              .deployTime(0f)
              .build();
      target.onSpawn();
      gameState.spawnEntity(target);
      gameState.processPending();

      attacker.getCombat().setCurrentTarget(target);
      combatSystem.update(0.1f);

      assertThat(attacker.getCombat().isAttacking()).isTrue();
      assertThat(attacker.getCombat().getCurrentWindup())
          .isCloseTo(attackCooldown - loadTime, within(0.001f)); // 0.2s, not 2.0s
    }

    @Test
    void noPreloadUnitStartsWithZeroCharge() {
      deployTroops(
          TroopStats.builder()
              .name("Sparky")
              .health(100)
              .damage(10)
              .range(3.0f)
              .attackCooldown(4.0f)
              .loadTime(4.0f)
              .deployTime(1.0f)
              .noPreload(true)
              .build());

      Troop troop = getFirstTroop();

      assertThat(troop.getCombat().getAccumulatedLoadTime()).isEqualTo(0f);
    }
  }
}
