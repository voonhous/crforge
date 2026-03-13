package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MagicArcherIntegrationTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
  }

  @Test
  void cardRegistry_shouldLoadMagicArcherWithCorrectStats() {
    Card card = CardRegistry.get("elitearcher");
    assertThat(card).isNotNull();
    assertThat(card.getType()).isEqualTo(CardType.TROOP);
    assertThat(card.getCost()).isEqualTo(4);
    assertThat(card.getRarity()).isEqualTo(Rarity.LEGENDARY);

    TroopStats unit = card.getUnitStats();
    assertThat(unit).isNotNull();
    assertThat(unit.getHealth()).isEqualTo(207);
    assertThat(unit.getDamage()).isEqualTo(56);
    assertThat(unit.getRange()).isEqualTo(7.0f);
    assertThat(unit.getAttackCooldown()).isEqualTo(1.1f);
    assertThat(unit.getMovementType()).isEqualTo(MovementType.GROUND);

    ProjectileStats proj = Objects.requireNonNull(unit.getProjectile());
    assertThat(proj.isHoming()).isFalse();
    assertThat(proj.isAoeToAir()).isTrue();
    assertThat(proj.isAoeToGround()).isTrue();
    assertThat(proj.getRadius()).isEqualTo(0.25f);
    assertThat(proj.getProjectileRange()).isEqualTo(11.0f);
    assertThat(proj.getPushback()).isEqualTo(0f);
  }

  @Test
  void arrow_shouldPierceMultipleGroundAndAirEnemies() {
    Troop archer = createMagicArcher(Team.BLUE, 9f, 10f);
    Troop enemy1 = createTarget(Team.RED, 9f, 13f, MovementType.GROUND);
    Troop enemy2 = createTarget(Team.RED, 9f, 15f, MovementType.AIR);
    Troop enemy3 = createTarget(Team.RED, 9f, 17f, MovementType.GROUND);

    gameState.spawnEntity(archer);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.spawnEntity(enemy3);
    gameState.processPending();
    archer.update(2.0f);
    enemy1.update(2.0f);
    enemy2.update(2.0f);
    enemy3.update(2.0f);

    int hp1 = enemy1.getHealth().getCurrent();
    int hp2 = enemy2.getHealth().getCurrent();
    int hp3 = enemy3.getHealth().getCurrent();

    // Fire the arrow at enemy1 -- it should pierce through all three
    archer.getCombat().setCurrentTarget(enemy1);
    archer.getCombat().startAttackSequence();
    archer.getCombat().setCurrentWindup(0);
    combatSystem.update(1.0f / 30f);

    float dt = 1.0f / 30f;
    for (int i = 0; i < 300; i++) {
      combatSystem.update(dt);
    }

    // All three enemies (ground and air) should take exactly 56 damage
    assertThat(hp1 - enemy1.getHealth().getCurrent()).isEqualTo(56);
    assertThat(hp2 - enemy2.getHealth().getCurrent()).isEqualTo(56);
    assertThat(hp3 - enemy3.getHealth().getCurrent()).isEqualTo(56);
  }

  @Test
  void arrow_shouldNotHitEnemiesOffAxis() {
    Troop archer = createMagicArcher(Team.BLUE, 9f, 10f);
    Troop onAxis = createTarget(Team.RED, 9f, 13f, MovementType.GROUND);
    // Off-axis target: 1 tile to the right, well beyond the 0.25 AOE radius
    Troop offAxis = createTarget(Team.RED, 10f, 15f, MovementType.GROUND);

    gameState.spawnEntity(archer);
    gameState.spawnEntity(onAxis);
    gameState.spawnEntity(offAxis);
    gameState.processPending();
    archer.update(2.0f);
    onAxis.update(2.0f);
    offAxis.update(2.0f);

    int onAxisHp = onAxis.getHealth().getCurrent();
    int offAxisHp = offAxis.getHealth().getCurrent();

    archer.getCombat().setCurrentTarget(onAxis);
    archer.getCombat().startAttackSequence();
    archer.getCombat().setCurrentWindup(0);

    float dt = 1.0f / 30f;
    for (int i = 0; i < 300; i++) {
      combatSystem.update(dt);
    }

    // On-axis target should take damage
    assertThat(onAxis.getHealth().getCurrent()).isLessThan(onAxisHp);
    // Off-axis target should NOT take damage (0.25 radius is tiny)
    assertThat(offAxis.getHealth().getCurrent()).isEqualTo(offAxisHp);
  }

  @Test
  void arrow_shouldNotKnockbackEnemies() {
    Troop archer = createMagicArcher(Team.BLUE, 9f, 10f);
    Troop target = createTarget(Team.RED, 9f, 13f, MovementType.GROUND);

    gameState.spawnEntity(archer);
    gameState.spawnEntity(target);
    gameState.processPending();
    archer.update(2.0f);
    target.update(2.0f);

    int initialHp = target.getHealth().getCurrent();

    archer.getCombat().setCurrentTarget(target);
    archer.getCombat().startAttackSequence();
    archer.getCombat().setCurrentWindup(0);

    float dt = 1.0f / 30f;
    for (int i = 0; i < 300; i++) {
      combatSystem.update(dt);
    }

    // Should take 56 damage
    assertThat(initialHp - target.getHealth().getCurrent()).isEqualTo(56);
    // Should NOT be knocked back (pushback=0, unlike Bowler)
    assertThat(target.getMovement().isKnockedBack()).isFalse();
  }

  @Test
  void arrow_shouldPierceTroopAndHitBuildingBehind() {
    Troop archer = createMagicArcher(Team.BLUE, 9f, 10f);
    Troop frontTroop = createTarget(Team.RED, 9f, 13f, MovementType.GROUND);
    Building backBuilding =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(9f, 17f))
            .health(new Health(500))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .lifetime(30f)
            .remainingLifetime(30f)
            .deployTime(1.0f)
            .deployTimer(0f)
            .build();

    gameState.spawnEntity(archer);
    gameState.spawnEntity(frontTroop);
    gameState.spawnEntity(backBuilding);
    gameState.processPending();
    archer.update(2.0f);
    frontTroop.update(2.0f);

    int troopHp = frontTroop.getHealth().getCurrent();
    int buildingHp = backBuilding.getHealth().getCurrent();

    // Aim at the front troop -- arrow should pierce through and hit the building
    archer.getCombat().setCurrentTarget(frontTroop);
    archer.getCombat().startAttackSequence();
    archer.getCombat().setCurrentWindup(0);

    float dt = 1.0f / 30f;
    for (int i = 0; i < 300; i++) {
      combatSystem.update(dt);
    }

    // Both the front troop and the building behind should take damage
    assertThat(frontTroop.getHealth().getCurrent()).isLessThan(troopHp);
    assertThat(backBuilding.getHealth().getCurrent()).isLessThan(buildingHp);
  }

  /**
   * Creates a Magic Archer troop with EliteArcherArrow projectile stats. Matches JSON data: HP=207,
   * damage=56, range=7.0, attackCooldown=1.1, speed=1.0 tiles/sec. Arrow: non-homing,
   * aoeToAir+aoeToGround, radius=0.25, projectileRange=11.0, speed=16.667 tiles/sec.
   */
  private Troop createMagicArcher(Team team, float x, float y) {
    ProjectileStats arrowStats =
        ProjectileStats.builder()
            .name("EliteArcherArrow")
            .damage(56)
            .speed(1000f / 60f) // 1000 raw / 60 = 16.667 tiles/sec
            .radius(0.25f)
            .homing(false)
            .aoeToGround(true)
            .aoeToAir(true)
            .projectileRange(11.0f)
            .pushback(0f)
            .build();

    return Troop.builder()
        .name("EliteArcher")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(207))
        .movement(new Movement(60f / 60f, 60f / 60f, 0.6f, 0.6f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(56)
                .range(7.0f)
                .sightRange(7.5f)
                .attackCooldown(1.1f)
                .projectileStats(arrowStats)
                .build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }

  /** Creates a stationary target troop with 500 HP. */
  private Troop createTarget(Team team, float x, float y, MovementType movementType) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(500))
        .movement(new Movement(0f, 0f, 0.5f, 0.5f, movementType))
        .combat(
            Combat.builder().damage(50).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .deployTime(1.0f)
        .deployTimer(1.0f)
        .build();
  }
}
