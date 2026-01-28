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

  @Test
  void applySpellDamage_shouldDamageEnemiesInRadius() {
    Troop enemy1 = createMeleeTroop(Team.RED, 10f, 10f, 0);
    enemy1.getHealth().takeDamage(0); // Ensure alive
    Troop enemy2 = createMeleeTroop(Team.RED, 11f, 10f, 0);
    Troop ally = createMeleeTroop(Team.BLUE, 10.5f, 10f, 0);
    Troop farEnemy = createMeleeTroop(Team.RED, 50f, 50f, 0);

    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.spawnEntity(ally);
    gameState.spawnEntity(farEnemy);
    gameState.processPending();

    enemy1.update(2.0f);
    enemy2.update(2.0f);
    ally.update(2.0f);
    farEnemy.update(2.0f);

    combatSystem.applySpellDamage(Team.BLUE, 10f, 10f, 50, 3.0f, Collections.emptyList());

    // Both close enemies should be damaged
    assertThat(enemy1.getHealth().getCurrent()).isEqualTo(50);
    assertThat(enemy2.getHealth().getCurrent()).isEqualTo(50);
    // Ally should NOT be damaged
    assertThat(ally.getHealth().getCurrent()).isEqualTo(100);
    // Far enemy should NOT be damaged
    assertThat(farEnemy.getHealth().getCurrent()).isEqualTo(100);
  }

  @Test
  void positionTargetedProjectile_shouldApplySpellDamageOnHit() {
    Troop enemy = createMeleeTroop(Team.RED, 10f, 10f, 0);

    gameState.spawnEntity(enemy);
    gameState.processPending();

    enemy.update(2.0f);

    // Create a position-targeted projectile aimed at (10, 10) — very close to start so it hits fast
    Projectile projectile = new Projectile(Team.BLUE, 10f, 10.5f, 10f, 10f,
        75, 3.0f, 50f, Collections.emptyList());
    gameState.spawnProjectile(projectile);

    // Run enough updates for the projectile to arrive
    for (int i = 0; i < 30; i++) {
      combatSystem.update(1.0f / 30f);
    }

    // Enemy should have taken damage from the position-targeted projectile
    assertThat(enemy.getHealth().getCurrent()).isLessThan(100);
  }

  @Test
  void curseShouldApplyBeforeLethalDamage() {
    // 1. Create a unit with 10 HP
    Troop victim = createMeleeTroop(Team.RED, 10f, 10f, 0);
    victim.getHealth().takeDamage(90); // Set current HP to 10

    // 2. Create a Projectile that deals 20 damage (Lethal) AND applies Curse
    TroopStats hogStats = TroopStats.builder().name("Cursed Hog").build();
    List<EffectStats> effects = List.of(
        EffectStats.builder().type(StatusEffectType.CURSE).duration(5f).spawnSpecies(hogStats)
            .build()
    );

    // Create projectile targeting victim
    // We mock the source/target interaction by creating a manual projectile or calling onHit
    Projectile projectile = new Projectile(createMeleeTroop(Team.BLUE, 0, 0, 0), victim, 20, 0, 10f,
        effects);

    // 3. Add to game state so update logic works (although we check effects on victim directly)
    gameState.spawnEntity(victim);
    gameState.processPending();

    // 4. Manually trigger hit logic for test precision (bypassing movement update)
    // Or better, let's let the system update naturally.
    gameState.spawnProjectile(projectile);

    // Move projectile to target immediately
    projectile.getPosition().set(10f, 10f); // Snap to target

    // Run combat update
    combatSystem.update(1.0f); // Should detect hit

    // 5. Verify Victim is Dead
    assertThat(victim.getHealth().isDead()).isTrue();

    // 6. Verify Curse Effect was applied despite death
    // Note: In a real tick, SpawnerSystem would process this. Here we just check the list.
    List<AppliedEffect> appliedEffects = victim.getAppliedEffects();
    assertThat(appliedEffects).hasSize(1);
    assertThat(appliedEffects.get(0).getType()).isEqualTo(StatusEffectType.CURSE);
  }

  @Test
  void curseShouldNotApplyToBuildings() {
    // 1. Create a Building
    Building tombstone = Building.builder()
        .name("Tombstone")
        .team(Team.RED)
        .position(new Position(10f, 10f))
        .health(new Health(500))
        .movement(new Movement(0, 0, 1, MovementType.BUILDING))
        .build();

    gameState.spawnEntity(tombstone);
    gameState.processPending();

    // 2. Apply Curse via Spell Damage (simulating Mother Witch hit or Curse spell)
    List<EffectStats> effects = List.of(
        EffectStats.builder().type(StatusEffectType.CURSE).duration(5f).build()
    );

    combatSystem.applySpellDamage(Team.BLUE, 10f, 10f, 50, 2.0f, effects);

    // 3. Verify Damage Taken
    assertThat(tombstone.getHealth().getCurrent()).isEqualTo(450);

    // 4. Verify No Effect Applied
    assertThat(tombstone.getAppliedEffects()).isEmpty();
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
                .aoeRadius(aoeRadius)
                .build())
        .build();
  }
}
