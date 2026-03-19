package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.ability.handler.BuffAllyHandler;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.component.AttachedComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.EntityTimerSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GiantBufferTest {

  private GameState gameState;
  private AbilitySystem abilitySystem;
  private CombatSystem combatSystem;
  private TargetingSystem targetingSystem;
  private PhysicsSystem physicsSystem;
  private final EntityTimerSystem entityTimerSystem = new EntityTimerSystem();

  private static final float DT = 1.0f / 30;

  // GiantBuffer base stats (level 1, Epic)
  private static final int BASE_ADDED_DAMAGE = 86;
  private static final int BASE_ADDED_CT_DAMAGE = 86;
  private static final int ATTACK_AMOUNT = 3;
  private static final float ACTION_DELAY = 1.0f;
  private static final float COOLDOWN = 3.0f;
  private static final float BUFF_DELAY = 0.28f;
  private static final float SEARCH_RANGE = 7.0f;
  private static final int MAX_TARGETS = 2;
  private static final float PERSIST_AFTER_DEATH = 5.0f;

  private static final List<DamageMultiplierEntry> MULTIPLIERS =
      List.of(
          new DamageMultiplierEntry("ElectroWizard", 500),
          new DamageMultiplierEntry("FirecrackerExplosion", 200),
          new DamageMultiplierEntry("RamRiderBola", 0));

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    abilitySystem = new AbilitySystem(gameState);
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    targetingSystem = new TargetingSystem();
    physicsSystem = new PhysicsSystem(new Arena("Test Arena"));
  }

  // -- BUFF_ALLY ability loading --

  @Test
  void buffAllyAbility_loadsCorrectly() {
    BuffAllyAbility data = createBuffAllyAbility();
    assertThat(data.type()).isEqualTo(AbilityType.BUFF_ALLY);
    assertThat(data.addedDamage()).isEqualTo(BASE_ADDED_DAMAGE);
    assertThat(data.addedCrownTowerDamage()).isEqualTo(BASE_ADDED_CT_DAMAGE);
    assertThat(data.attackAmount()).isEqualTo(ATTACK_AMOUNT);
    assertThat(data.searchRange()).isEqualTo(SEARCH_RANGE);
    assertThat(data.maxTargets()).isEqualTo(MAX_TARGETS);
    assertThat(data.cooldown()).isEqualTo(COOLDOWN);
    assertThat(data.actionDelay()).isEqualTo(ACTION_DELAY);
    assertThat(data.buffDelay()).isEqualTo(BUFF_DELAY);
    assertThat(data.persistAfterDeath()).isEqualTo(PERSIST_AFTER_DEATH);
    assertThat(data.damageMultipliers()).hasSize(3);
  }

  // -- Action delay --

  @Test
  void giantBuffer_appliesBuffAfterActionDelay() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop ally = createAllyTroop(Team.BLUE, 6, 5);

    spawnAndDeploy(buffer, ally);

    // Run for just under 1.0s action delay -- no buff yet
    runTicks(29);
    assertThat(ally.getGiantBuff()).isNull();

    // One more tick crosses the 1.0s threshold
    runTicks(1);
    assertThat(ally.getGiantBuff()).isNotNull();
  }

  // -- Buff delay --

  @Test
  void giantBuffer_buffPendingDuringDelay() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop ally = createAllyTroop(Team.BLUE, 6, 5);

    spawnAndDeploy(buffer, ally);

    // Cross the action delay
    runTicks(30);
    GiantBuffState buff = ally.getGiantBuff();
    assertThat(buff).isNotNull();

    // Buff should not be active yet (0.28s delay)
    assertThat(buff.isActive()).isFalse();

    // Run past the buff delay (0.28s = ~9 ticks)
    runTicks(9);
    assertThat(buff.isActive()).isTrue();
  }

  // -- Cooldown cycling --

  @Test
  void giantBuffer_cyclesEvery3Seconds() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop ally = createAllyTroop(Team.BLUE, 6, 5);

    spawnAndDeploy(buffer, ally);

    // 1.0s action delay -> first buff applied
    runTicks(30);
    assertThat(ally.getGiantBuff()).isNotNull();

    // Activate it, then destroy it to track next application
    GiantBuffState firstBuff = ally.getGiantBuff();
    // Wait for it to activate
    runTicks(9);
    assertThat(firstBuff.isActive()).isTrue();

    // After 3.0s cooldown (90 ticks) from first cycle, another cycle fires
    // It will refresh the existing buff (singleton)
    firstBuff.setAttackCounter(2); // Set counter to verify refresh resets it

    // Run 82 ticks (90 - 9 + 1 extra for float precision) to ensure cooldown expires
    runTicks(82);
    // The refresh should have reset the attack counter
    assertThat(ally.getGiantBuff().getAttackCounter()).isEqualTo(0);
  }

  // -- Target selection --

  @Test
  void giantBuffer_selectsClosest2Friendlies() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop nearAlly = createAllyTroop(Team.BLUE, 6, 5); // 1 tile away
    Troop midAlly = createAllyTroop(Team.BLUE, 8, 5); // 3 tiles away
    Troop farAlly = createAllyTroop(Team.BLUE, 11, 5); // 6 tiles away

    spawnAndDeploy(buffer, nearAlly, midAlly, farAlly);

    // Cross action delay
    runTicks(30);

    // The 2 closest (nearAlly, midAlly) should get buffed; farAlly should not
    assertThat(nearAlly.getGiantBuff()).isNotNull();
    assertThat(midAlly.getGiantBuff()).isNotNull();
    assertThat(farAlly.getGiantBuff()).isNull();
  }

  @Test
  void giantBuffer_excludesSelfFromTargets() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    // Only the buffer itself is a friendly troop (no allies)

    spawnAndDeploy(buffer);

    runTicks(30);

    // Buffer should not buff itself
    assertThat(buffer.getGiantBuff()).isNull();
  }

  @Test
  void giantBuffer_excludesAttachedTroops() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop parent = createAllyTroop(Team.BLUE, 6, 5);

    // Create an attached troop (e.g. Spear Goblin on Goblin Giant)
    Troop rider =
        Troop.builder()
            .name("Rider")
            .team(Team.BLUE)
            .position(new Position(6, 5))
            .health(new Health(100))
            .movement(new Movement(1f, 4f, 0.3f, 0.3f, MovementType.GROUND))
            .attached(new AttachedComponent(parent, 0f, 0.5f))
            .deployTime(0f)
            .deployTimer(0f)
            .build();

    spawnAndDeploy(buffer, parent, rider);

    runTicks(30);

    // Parent should get buffed, but attached rider should not
    assertThat(parent.getGiantBuff()).isNotNull();
    assertThat(rider.getGiantBuff()).isNull();
  }

  // -- Bonus damage proc --

  @Test
  void giantBuffer_bonusDamageOnEvery3rdAttack() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 16);
    Troop ally = createMeleeAlly(Team.BLUE, 5, 20);
    Troop enemy = createDummyTarget(Team.RED, 5, 21);

    spawnAndDeploy(buffer, ally, enemy);

    // Apply buff and activate it
    runTicks(30); // action delay
    runTicks(9); // buff delay

    GiantBuffState buff = ally.getGiantBuff();
    assertThat(buff).isNotNull();
    assertThat(buff.isActive()).isTrue();

    Combat combat = ally.getCombat();

    // Attacks 1 and 2: no bonus
    int bonus1 = BuffAllyHandler.processGiantBuffHit(ally, enemy, combat);
    assertThat(bonus1).isEqualTo(0);
    assertThat(buff.getAttackCounter()).isEqualTo(1);

    int bonus2 = BuffAllyHandler.processGiantBuffHit(ally, enemy, combat);
    assertThat(bonus2).isEqualTo(0);
    assertThat(buff.getAttackCounter()).isEqualTo(2);

    // Attack 3: proc! Bonus = addedDamage * 100/100 = addedDamage
    int bonus3 = BuffAllyHandler.processGiantBuffHit(ally, enemy, combat);
    assertThat(bonus3).isEqualTo(BASE_ADDED_DAMAGE);
    assertThat(buff.getAttackCounter()).isEqualTo(0);
  }

  @Test
  void giantBuffer_counterResetsAndCycles() {
    Troop ally = createMeleeAlly(Team.BLUE, 5, 5);
    Combat combat = ally.getCombat();
    Troop enemy = createDummyTarget(Team.RED, 5, 6);

    // Directly apply a buff
    GiantBuffState buff = createActiveBuff(ally);

    // Attacks 1-3: proc on 3
    for (int i = 0; i < 2; i++) {
      assertThat(BuffAllyHandler.processGiantBuffHit(ally, enemy, combat)).isEqualTo(0);
    }
    assertThat(BuffAllyHandler.processGiantBuffHit(ally, enemy, combat))
        .isEqualTo(BASE_ADDED_DAMAGE);

    // Attacks 4-6: proc on 6
    for (int i = 0; i < 2; i++) {
      assertThat(BuffAllyHandler.processGiantBuffHit(ally, enemy, combat)).isEqualTo(0);
    }
    assertThat(BuffAllyHandler.processGiantBuffHit(ally, enemy, combat))
        .isEqualTo(BASE_ADDED_DAMAGE);
  }

  // -- Singleton refresh --

  @Test
  void giantBuffer_singletonRefresh() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop ally = createAllyTroop(Team.BLUE, 6, 5);

    spawnAndDeploy(buffer, ally);

    // First buff cycle
    runTicks(30);
    GiantBuffState firstBuff = ally.getGiantBuff();
    assertThat(firstBuff).isNotNull();

    // Activate it and increment counter
    runTicks(9);
    firstBuff.setAttackCounter(2);

    // Wait for next cycle (3.0s = 90 ticks from first cycle, +1 for float precision)
    runTicks(82);

    // Should be the same object (refreshed, not replaced)
    GiantBuffState refreshed = ally.getGiantBuff();
    assertThat(refreshed).isSameAs(firstBuff);

    // Counter should be reset by refresh
    assertThat(refreshed.getAttackCounter()).isEqualTo(0);
  }

  // -- Persist after death --

  @Test
  void giantBuffer_persistsAfterDeath() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop ally = createAllyTroop(Team.BLUE, 6, 5);

    spawnAndDeploy(buffer, ally);

    // Apply and activate buff
    runTicks(30);
    runTicks(9);
    GiantBuffState buff = ally.getGiantBuff();
    assertThat(buff.isActive()).isTrue();

    // Kill the GiantBuffer
    buffer.getHealth().kill();
    gameState.processPending();

    // Run for 4.9s (147 ticks) -- buff should still be active
    runTicks(147);
    assertThat(ally.getGiantBuff()).isNotNull();
    assertThat(buff.isSourceDead()).isTrue();

    // Run past 5.0s persist duration (3 more ticks to be safe)
    runTicks(4);
    assertThat(ally.getGiantBuff()).isNull();
  }

  @Test
  void giantBuffer_noNewCyclesAfterDeath() {
    Troop buffer = createGiantBuffer(Team.BLUE, 5, 5);
    Troop ally1 = createAllyTroop(Team.BLUE, 6, 5);
    Troop ally2 = createAllyTroop(Team.BLUE, 7, 5);

    spawnAndDeploy(buffer, ally1, ally2);

    // First cycle applies buff to ally1, ally2
    runTicks(30);
    assertThat(ally1.getGiantBuff()).isNotNull();
    assertThat(ally2.getGiantBuff()).isNotNull();

    // Kill the GiantBuffer before next cycle
    buffer.getHealth().kill();
    gameState.processPending();

    // Spawn a new ally after the buffer dies
    Troop lateAlly = createAllyTroop(Team.BLUE, 6.5f, 5);
    gameState.spawnEntity(lateAlly);
    gameState.processPending();
    lateAlly.setDeployTimer(0);

    // Wait past what would be the next cooldown
    runTicks(90);

    // New ally should NOT have received a buff (dead GiantBuffer doesn't fire new cycles)
    assertThat(lateAlly.getGiantBuff()).isNull();
  }

  // -- Crown tower bonus --

  @Test
  void giantBuffer_crownTowerBonusDamage() {
    Troop ally = createMeleeAlly(Team.BLUE, 5, 5);
    Combat combat = ally.getCombat();

    // Create a tower target
    Tower tower =
        Tower.builder()
            .name("PrincessTower")
            .team(Team.RED)
            .position(new Position(5, 6))
            .health(new Health(2000))
            .movement(new Movement(0f, 0f, 1.0f, 1.0f, MovementType.BUILDING))
            .build();

    GiantBuffState buff = createActiveBuff(ally);

    // Skip to proc (3 attacks)
    BuffAllyHandler.processGiantBuffHit(ally, tower, combat);
    BuffAllyHandler.processGiantBuffHit(ally, tower, combat);
    int bonus = BuffAllyHandler.processGiantBuffHit(ally, tower, combat);

    // Tower should use addedCrownTowerDamage
    assertThat(bonus).isEqualTo(BASE_ADDED_CT_DAMAGE);
  }

  // -- Damage multiplier overrides --

  @Test
  void giantBuffer_damageMultiplier_override() {
    // EWiz has 500 multiplier = 5x
    Troop ewiz =
        Troop.builder()
            .name("ElectroWizard")
            .team(Team.BLUE)
            .position(new Position(5, 5))
            .health(new Health(500))
            .movement(new Movement(1f, 4f, 0.4f, 0.4f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(50)
                    .range(5.0f)
                    .attackCooldown(1.8f)
                    .targetType(TargetType.ALL)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();

    Troop enemy = createDummyTarget(Team.RED, 5, 6);

    GiantBuffState buff = createActiveBuff(ewiz);
    Combat combat = ewiz.getCombat();

    // Skip to proc
    BuffAllyHandler.processGiantBuffHit(ewiz, enemy, combat);
    BuffAllyHandler.processGiantBuffHit(ewiz, enemy, combat);
    int bonus = BuffAllyHandler.processGiantBuffHit(ewiz, enemy, combat);

    // 86 * 500 / 100 = 430
    assertThat(bonus).isEqualTo(BASE_ADDED_DAMAGE * 500 / 100);
  }

  @Test
  void giantBuffer_damageMultiplier_zero() {
    // RamRiderBola has multiplier 0 = no bonus
    Troop ramRider =
        Troop.builder()
            .name("RamRider")
            .team(Team.BLUE)
            .position(new Position(5, 5))
            .health(new Health(500))
            .movement(new Movement(1f, 4f, 0.4f, 0.4f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(50)
                    .range(5.0f)
                    .attackCooldown(1.0f)
                    .targetType(TargetType.ALL)
                    .projectileStats(
                        ProjectileStats.builder().name("RamRiderBola").damage(50).build())
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();

    Troop enemy = createDummyTarget(Team.RED, 5, 6);

    GiantBuffState buff = createActiveBuff(ramRider);
    Combat combat = ramRider.getCombat();

    // Skip to proc
    BuffAllyHandler.processGiantBuffHit(ramRider, enemy, combat);
    BuffAllyHandler.processGiantBuffHit(ramRider, enemy, combat);
    int bonus = BuffAllyHandler.processGiantBuffHit(ramRider, enemy, combat);

    // Multiplier 0 = 86 * 0 / 100 = 0
    assertThat(bonus).isEqualTo(0);
  }

  // -- Multi-target counter increment --

  @Test
  void giantBuffer_multiTargetIncrementsTwice() {
    // EWiz-style: 2 targets per attack. Each target increments counter independently.
    Troop ally = createMeleeAlly(Team.BLUE, 5, 5);
    Combat combat = ally.getCombat();
    Troop enemy1 = createDummyTarget(Team.RED, 5, 6);
    Troop enemy2 = createDummyTarget(Team.RED, 6, 6);

    GiantBuffState buff = createActiveBuff(ally);

    // Attack 1: primary target (counter 0 -> 1)
    int bonus1 = BuffAllyHandler.processGiantBuffHit(ally, enemy1, combat);
    assertThat(bonus1).isEqualTo(0);
    assertThat(buff.getAttackCounter()).isEqualTo(1);

    // Attack 1: additional target (counter 1 -> 2)
    int bonus2 = BuffAllyHandler.processGiantBuffHit(ally, enemy2, combat);
    assertThat(bonus2).isEqualTo(0);
    assertThat(buff.getAttackCounter()).isEqualTo(2);

    // Attack 2: primary target (counter 2 -> 3 = proc!)
    int bonus3 = BuffAllyHandler.processGiantBuffHit(ally, enemy1, combat);
    assertThat(bonus3).isEqualTo(BASE_ADDED_DAMAGE);
    assertThat(buff.getAttackCounter()).isEqualTo(0);
  }

  // -- Level scaling --

  @Test
  void giantBuffer_levelScaling() {
    // At level 11 with Epic rarity, the scaled damage should match LevelScaling
    int level = 11;
    int expected = LevelScaling.scaleCard(BASE_ADDED_DAMAGE, Rarity.EPIC, level);

    AbilityComponent component = new AbilityComponent(createBuffAllyAbility());
    component.setScaledAddedDamage(expected);
    component.setScaledAddedCrownTowerDamage(
        LevelScaling.scaleCard(BASE_ADDED_CT_DAMAGE, Rarity.EPIC, level));

    Troop ally = createMeleeAlly(Team.BLUE, 5, 5);
    ally.setGiantBuff(
        new GiantBuffState(
            expected,
            component.getScaledAddedCrownTowerDamage(),
            ATTACK_AMOUNT,
            List.of(),
            0,
            0f,
            PERSIST_AFTER_DEATH));
    ally.getGiantBuff().setActive(true);

    Combat combat = ally.getCombat();
    Troop enemy = createDummyTarget(Team.RED, 5, 6);

    // Skip to proc
    BuffAllyHandler.processGiantBuffHit(ally, enemy, combat);
    BuffAllyHandler.processGiantBuffHit(ally, enemy, combat);
    int bonus = BuffAllyHandler.processGiantBuffHit(ally, enemy, combat);

    assertThat(bonus).isEqualTo(expected);
    // Verify level 11 Epic scaling is greater than base
    assertThat(expected).isGreaterThan(BASE_ADDED_DAMAGE);
  }

  // -- Helper methods --

  private BuffAllyAbility createBuffAllyAbility() {
    return new BuffAllyAbility(
        BASE_ADDED_DAMAGE,
        BASE_ADDED_CT_DAMAGE,
        ATTACK_AMOUNT,
        SEARCH_RANGE,
        MAX_TARGETS,
        COOLDOWN,
        ACTION_DELAY,
        BUFF_DELAY,
        8.5f,
        PERSIST_AFTER_DEATH,
        MULTIPLIERS);
  }

  private Troop createGiantBuffer(Team team, float x, float y) {
    AbilityComponent ability = new AbilityComponent(createBuffAllyAbility());
    // Simulate level-1 scaling (no scaling = base values)
    ability.setScaledAddedDamage(BASE_ADDED_DAMAGE);
    ability.setScaledAddedCrownTowerDamage(BASE_ADDED_CT_DAMAGE);

    return Troop.builder()
        .name("GiantBuffer")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1040))
        .movement(new Movement(1f, 18f, 0.75f, 0.75f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(47)
                .range(1.2f)
                .attackCooldown(1.5f)
                .targetType(TargetType.GROUND)
                .targetOnlyBuildings(true)
                .build())
        .ability(ability)
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }

  private Troop createAllyTroop(Team team, float x, float y) {
    return Troop.builder()
        .name("Knight")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(690))
        .movement(new Movement(1f, 6f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(79)
                .range(1.2f)
                .attackCooldown(1.2f)
                .targetType(TargetType.GROUND)
                .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }

  private Troop createMeleeAlly(Team team, float x, float y) {
    return Troop.builder()
        .name("MeleeAlly")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .movement(new Movement(1f, 4f, 0.4f, 0.4f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(100)
                .range(1.2f)
                .attackCooldown(1.0f)
                .targetType(TargetType.GROUND)
                .build())
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  private Troop createDummyTarget(Team team, float x, float y) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(5000))
        .movement(new Movement(0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  /** Creates and applies an already-active GiantBuff on the given troop. */
  private GiantBuffState createActiveBuff(Troop troop) {
    GiantBuffState buff =
        new GiantBuffState(
            BASE_ADDED_DAMAGE,
            BASE_ADDED_CT_DAMAGE,
            ATTACK_AMOUNT,
            MULTIPLIERS,
            0, // source entity ID (not needed for direct tests)
            0f, // no delay
            PERSIST_AFTER_DEATH);
    buff.setActive(true);
    troop.setGiantBuff(buff);
    return buff;
  }

  private void spawnAndDeploy(Troop... troops) {
    for (Troop t : troops) {
      gameState.spawnEntity(t);
    }
    gameState.processPending();

    // Fast-forward deploy timers
    for (Troop t : troops) {
      t.setDeployTimer(0); // skip deploy phase
    }
  }

  private void runTicks(int ticks) {
    for (int i = 0; i < ticks; i++) {
      entityTimerSystem.update(gameState.getAliveEntities(), DT);
      abilitySystem.update(DT);
    }
  }
}
