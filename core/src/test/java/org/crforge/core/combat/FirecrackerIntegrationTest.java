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
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirecrackerIntegrationTest {

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
  void cardRegistry_shouldLoadFirecrackerWithCorrectStats() {
    Card card = CardRegistry.get("firecracker");
    assertThat(card).isNotNull();
    assertThat(card.getType()).isEqualTo(CardType.TROOP);
    assertThat(card.getCost()).isEqualTo(3);
    assertThat(card.getRarity()).isEqualTo(Rarity.COMMON);

    TroopStats unit = card.getUnitStats();
    assertThat(unit).isNotNull();
    assertThat(unit.getName()).isEqualTo("Firecracker");
    assertThat(unit.getHealth()).isEqualTo(119);
    assertThat(unit.getRange()).isEqualTo(6.0f);
    assertThat(unit.getAttackCooldown()).isEqualTo(3.0f);
    assertThat(unit.getMovementType()).isEqualTo(MovementType.GROUND);
    assertThat(unit.getAttackPushBack()).isEqualTo(1.0f);

    // Main projectile -> spawnProjectile chain
    ProjectileStats proj = Objects.requireNonNull(unit.getProjectile());
    assertThat(proj.getName()).isEqualTo("FirecrackerProjectile");
    assertThat(proj.isHoming()).isFalse();

    ProjectileStats explosion = Objects.requireNonNull(proj.getSpawnProjectile());
    assertThat(explosion.getName()).isEqualTo("FirecrackerExplosion");
    assertThat(explosion.getDamage()).isEqualTo(25);
    assertThat(explosion.getSpawnCount()).isEqualTo(5);
    assertThat(explosion.getProjectileRange()).isEqualTo(5.0f);
    assertThat(explosion.getRadius()).isEqualTo(0.4f);
    assertThat(explosion.isAoeToGround()).isTrue();
    assertThat(explosion.isAoeToAir()).isTrue();
  }

  @Test
  void attackRecoil_shouldPushFirecrackerBackward() {
    Troop firecracker = createFirecracker(Team.BLUE, 9f, 10f);
    Troop target = createTarget(Team.RED, 9f, 15f, MovementType.GROUND);

    gameState.spawnEntity(firecracker);
    gameState.spawnEntity(target);
    gameState.processPending();
    // Skip deploy time
    firecracker.update(2.0f);
    target.update(2.0f);

    float initialY = firecracker.getPosition().getY();

    // Trigger attack
    firecracker.getCombat().setCurrentTarget(target);
    firecracker.getCombat().startAttackSequence();
    firecracker.getCombat().setCurrentWindup(0);
    combatSystem.update(1.0f / 30f);

    // Firecracker should be knocked back (pushed away from target, i.e. Y decreases)
    assertThat(firecracker.getMovement().isKnockedBack()).isTrue();

    // Simulate a few ticks for knockback to displace position
    float dt = 1.0f / 30f;
    for (int i = 0; i < 10; i++) {
      firecracker.getMovement().tickKnockback(firecracker.getPosition(), dt);
    }

    // Y should have decreased (pushed backward, away from the target above)
    assertThat(firecracker.getPosition().getY()).isLessThan(initialY);
  }

  @Test
  void mainProjectile_shouldSpawnFiveShrapnelOnImpact() {
    Troop firecracker = createFirecracker(Team.BLUE, 9f, 10f);
    Troop target = createTarget(Team.RED, 9f, 15f, MovementType.GROUND);

    gameState.spawnEntity(firecracker);
    gameState.spawnEntity(target);
    gameState.processPending();
    firecracker.update(2.0f);
    target.update(2.0f);

    // Fire at target
    firecracker.getCombat().setCurrentTarget(target);
    firecracker.getCombat().startAttackSequence();
    firecracker.getCombat().setCurrentWindup(0);
    combatSystem.update(1.0f / 30f);

    // Should have spawned 1 main projectile (FirecrackerProjectile)
    assertThat(gameState.getProjectiles()).hasSize(1);

    // Simulate tick by tick, tracking the max number of piercing projectiles seen.
    // The main projectile hits after ~22 ticks, spawning 5 shrapnel which then expire.
    float dt = 1.0f / 30f;
    long maxPiercingCount = 0;
    for (int i = 0; i < 300; i++) {
      combatSystem.update(dt);
      long piercingCount =
          gameState.getProjectiles().stream().filter(Projectile::isPiercing).count();
      maxPiercingCount = Math.max(maxPiercingCount, piercingCount);
    }

    // 5 piercing shrapnel projectiles should have been spawned on impact
    assertThat(maxPiercingCount).isEqualTo(5);
  }

  @Test
  void shrapnel_shouldPierceThroughMultipleEnemiesAndDealCorrectDamage() {
    Troop firecracker = createFirecracker(Team.BLUE, 9f, 10f);
    // Place targets in a line behind each other (along the Y axis)
    Troop enemy1 = createTarget(Team.RED, 9f, 15f, MovementType.GROUND);
    Troop enemy2 = createTarget(Team.RED, 9f, 17f, MovementType.GROUND);

    gameState.spawnEntity(firecracker);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();
    firecracker.update(2.0f);
    enemy1.update(2.0f);
    enemy2.update(2.0f);

    int hp1Before = enemy1.getHealth().getCurrent();
    int hp2Before = enemy2.getHealth().getCurrent();

    // Fire at enemy1
    firecracker.getCombat().setCurrentTarget(enemy1);
    firecracker.getCombat().startAttackSequence();
    firecracker.getCombat().setCurrentWindup(0);

    // Run enough ticks for main projectile to hit and shrapnel to travel through
    float dt = 1.0f / 30f;
    for (int i = 0; i < 600; i++) {
      combatSystem.update(dt);
    }

    // enemy1 should be hit by the center shrapnel (at minimum)
    assertThat(enemy1.getHealth().getCurrent()).isLessThan(hp1Before);

    // enemy2 is 2 tiles behind enemy1, within the 5-tile shrapnel range + 0.4 radius.
    // The center shrapnel should pierce through to hit enemy2.
    assertThat(enemy2.getHealth().getCurrent()).isLessThan(hp2Before);

    // Each shrapnel deals 25 damage. enemy1 takes at least 25 (center shrapnel).
    int dmg1 = hp1Before - enemy1.getHealth().getCurrent();
    assertThat(dmg1).isGreaterThanOrEqualTo(25);
    assertThat(dmg1 % 25).isEqualTo(0); // Damage should be a multiple of 25
  }

  /**
   * Creates a Firecracker troop matching the JSON data. HP=119, range=6.0, attackCooldown=3.0,
   * attackPushBack=1.0f. Main projectile: FirecrackerProjectile (non-homing, spawnProjectile ->
   * FirecrackerExplosion with spawnCount=5).
   */
  private Troop createFirecracker(Team team, float x, float y) {
    ProjectileStats explosionStats =
        ProjectileStats.builder()
            .name("FirecrackerExplosion")
            .damage(25)
            .speed(550f / 60f)
            .radius(0.4f)
            .homing(false)
            .aoeToGround(true)
            .aoeToAir(true)
            .projectileRange(5.0f)
            .spawnCount(5)
            .spawnRadius(0.08f)
            .build();

    ProjectileStats mainProjectileStats =
        ProjectileStats.builder()
            .name("FirecrackerProjectile")
            .damage(0)
            .speed(400f / 60f)
            .homing(false)
            .aoeToGround(true)
            .aoeToAir(true)
            .spawnProjectile(explosionStats)
            .spawnCount(5)
            .build();

    return Troop.builder()
        .name("Firecracker")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(119))
        .movement(new Movement(90f / 60f, 6f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(0)
                .range(6.0f)
                .sightRange(8.0f)
                .attackCooldown(3.0f)
                .loadTime(2.35f)
                .accumulatedLoadTime(2.35f)
                .projectileStats(mainProjectileStats)
                .attackPushBack(1.0f)
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
