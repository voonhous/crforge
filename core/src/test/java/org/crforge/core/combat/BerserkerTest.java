package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.AttackSequenceHit;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.engine.EntityTimerSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.TroopTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BerserkerTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private final EntityTimerSystem entityTimerSystem = new EntityTimerSystem();

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
  }

  @Test
  void attackSequence_cyclesThroughHits() {
    // Berserker: 3-hit combo of 40, 40, 40 damage each
    List<AttackSequenceHit> sequence =
        List.of(new AttackSequenceHit(40), new AttackSequenceHit(40), new AttackSequenceHit(40));

    // Use 1.0s cooldown for reliable per-attack timing in tests (matches CombatSystemTest pattern)
    Troop berserker =
        TroopTemplate.melee("Berserker", Team.BLUE)
            .at(5, 5)
            .damage(0) // All damage comes from attack sequence
            .cooldown(1.0f)
            .attackSequence(sequence)
            .build();

    // Target with enough HP to survive 6 hits (6 * 40 = 240)
    Troop target = TroopTemplate.target("Dummy", Team.RED).at(6, 5).hp(500).build();

    gameState.spawnEntity(berserker);
    gameState.spawnEntity(target);
    gameState.processPending();

    // Finish deploy
    berserker.setDeployTimer(0);
    target.setDeployTimer(0);

    berserker.getCombat().setCurrentTarget(target);

    // Track damage per hit across 6 attacks (cycles through 0->1->2->0->1->2)
    int[] damagePerHit = new int[6];
    for (int i = 0; i < 6; i++) {
      int hpBefore = target.getHealth().getCurrent();
      runCombatUpdates(1.1f);
      int hpAfter = target.getHealth().getCurrent();
      damagePerHit[i] = hpBefore - hpAfter;
    }

    // Each hit should deal 40 damage, cycling through the sequence
    assertThat(damagePerHit).containsExactly(40, 40, 40, 40, 40, 40);
  }

  @Test
  void attackSequence_totalDamageAfterFullCombo() {
    List<AttackSequenceHit> sequence =
        List.of(new AttackSequenceHit(40), new AttackSequenceHit(40), new AttackSequenceHit(40));

    Troop berserker =
        TroopTemplate.melee("Berserker", Team.BLUE)
            .at(5, 5)
            .damage(0)
            .cooldown(1.0f)
            .attackSequence(sequence)
            .build();

    Troop target = TroopTemplate.target("Dummy", Team.RED).at(6, 5).hp(500).build();

    gameState.spawnEntity(berserker);
    gameState.spawnEntity(target);
    gameState.processPending();

    berserker.setDeployTimer(0);
    target.setDeployTimer(0);

    berserker.getCombat().setCurrentTarget(target);

    // Run 3 attacks (one full combo)
    for (int i = 0; i < 3; i++) {
      runCombatUpdates(1.1f);
    }

    // 3 hits * 40 damage = 120 total, 500 - 120 = 380
    assertThat(target.getHealth().getCurrent()).isEqualTo(380);
  }

  @Test
  void attackSequence_retarget_preservesHitIndex() {
    List<AttackSequenceHit> sequence =
        List.of(new AttackSequenceHit(40), new AttackSequenceHit(40), new AttackSequenceHit(40));

    Troop berserker =
        TroopTemplate.melee("Berserker", Team.BLUE)
            .at(5, 5)
            .damage(0)
            .cooldown(1.0f)
            .attackSequence(sequence)
            .build();

    // First target dies after 1 hit (40 HP)
    Troop target1 = TroopTemplate.target("Target1", Team.RED).at(6, 5).hp(40).build();
    Troop target2 = TroopTemplate.target("Target2", Team.RED).at(6, 5).hp(500).build();

    gameState.spawnEntity(berserker);
    gameState.spawnEntity(target1);
    gameState.spawnEntity(target2);
    gameState.processPending();

    berserker.setDeployTimer(0);
    target1.setDeployTimer(0);
    target2.setDeployTimer(0);

    // Attack first target (hit index 0 -> 40 damage, kills it)
    berserker.getCombat().setCurrentTarget(target1);
    runCombatUpdates(1.1f);
    assertThat(target1.isAlive()).isFalse();

    // After kill, attack sequence index should be at 1 (advanced from 0)
    assertThat(berserker.getCombat().getAttackSequenceIndex()).isEqualTo(1);

    // Retarget to second target -- index should NOT reset
    berserker.getCombat().setCurrentTarget(target2);
    int hpBefore = target2.getHealth().getCurrent();
    runCombatUpdates(1.1f);
    int damageDone = hpBefore - target2.getHealth().getCurrent();

    // Should deal 40 damage (index 1 hit), not reset to index 0
    assertThat(damageDone).isEqualTo(40);
    assertThat(berserker.getCombat().getAttackSequenceIndex()).isEqualTo(2);
  }

  @Test
  void attackSequence_withLevelScaling() {
    // Simulate level scaling: base 40 damage at level 1, scaled to level 11 Common
    int scaledDamage = LevelScaling.scaleCard(40, Rarity.COMMON, 11);

    List<AttackSequenceHit> sequence =
        List.of(
            new AttackSequenceHit(scaledDamage),
            new AttackSequenceHit(scaledDamage),
            new AttackSequenceHit(scaledDamage));

    Troop berserker =
        TroopTemplate.melee("Berserker", Team.BLUE)
            .at(5, 5)
            .damage(0)
            .cooldown(1.0f)
            .attackSequence(sequence)
            .build();

    Troop target = TroopTemplate.target("Dummy", Team.RED).at(6, 5).hp(5000).build();

    gameState.spawnEntity(berserker);
    gameState.spawnEntity(target);
    gameState.processPending();

    berserker.setDeployTimer(0);
    target.setDeployTimer(0);

    berserker.getCombat().setCurrentTarget(target);

    // One attack
    runCombatUpdates(1.1f);

    int damageDone = 5000 - target.getHealth().getCurrent();
    // Level 11 Common scaling: floor(40 * 1.10^10) = floor(40 * 2.5937...) = 103
    assertThat(damageDone).isEqualTo(scaledDamage);
    assertThat(damageDone).isGreaterThan(40); // Confirm scaling actually increased the damage
  }

  private void runCombatUpdates(float duration) {
    float dt = 0.1f;
    int ticks = (int) (duration / dt);
    for (int i = 0; i < ticks; i++) {
      entityTimerSystem.update(gameState.getAliveEntities(), dt);
      combatSystem.update(dt);
    }
  }
}
