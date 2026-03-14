package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Snowball spell: a position-targeted projectile that deals AOE damage, applies a
 * slow effect (IceWizardSlowDown: 30% speed/attack/spawn reduction for 3s), pushes back enemies,
 * and deals reduced damage to crown towers (crownTowerDamagePercent=-70, i.e. 30% effective).
 */
class SnowballTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private PhysicsSystem physicsSystem;

  // SnowballSpell stats from projectiles.json (level-1 base values)
  private static final int DAMAGE = 70;
  private static final float AOE_RADIUS = 2.5f;
  private static final float SPEED = 800f / 60f; // Raw CSV speed / SPEED_BASE
  private static final int CROWN_TOWER_DAMAGE_PCT = -70; // 30% effective damage on towers
  private static final float SLOW_DURATION = 3.0f;
  private static final float PUSHBACK = 1800f / 1000f; // 1.8 tiles

  // IceWizardSlowDown slow effect applied after damage
  private static final List<EffectStats> SNOWBALL_EFFECTS =
      List.of(
          EffectStats.builder()
              .type(StatusEffectType.SLOW)
              .duration(SLOW_DURATION)
              .buffName("IceWizardSlowDown")
              .applyAfterDamage(true)
              .build());

  private static final float DT = 1.0f / 30f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    Arena arena = new Arena("TestArena");
    physicsSystem = new PhysicsSystem(arena);
    physicsSystem.setGameState(gameState);
  }

  @Test
  void snowball_dealsAoeDamageToEnemies() {
    Troop enemy = createTroop("Enemy", Team.RED, 10f, 16f, 1000);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(2.0f);

    spawnSnowball(Team.BLUE, 10f, 16f);
    advanceUntilResolved();

    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take %d damage from Snowball", DAMAGE)
        .isEqualTo(1000 - DAMAGE);
  }

  @Test
  void snowball_appliesSlowEffect() {
    Troop enemy = createTroop("Enemy", Team.RED, 10f, 16f, 1000);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(2.0f);

    spawnSnowball(Team.BLUE, 10f, 16f);
    advanceUntilResolved();

    assertThat(enemy.getAppliedEffects())
        .as("Enemy should have SLOW effect after being hit by Snowball")
        .anyMatch(e -> e.getType() == StatusEffectType.SLOW);
  }

  @Test
  void snowball_appliesKnockback() {
    Troop enemy = createTroop("Enemy", Team.RED, 10f, 18f, 1000);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(2.0f);

    float impactX = 10f;
    float impactY = 17f;

    spawnSnowball(Team.BLUE, impactX, impactY);
    advanceUntilResolved();

    // Enemy is above the impact (y > impactY), should be pushed further up
    assertThat(enemy.getMovement().isKnockedBack())
        .as("Enemy should be in knockback state after Snowball hit")
        .isTrue();

    float yBefore = enemy.getPosition().getY();
    physicsSystem.update(gameState.getAliveEntities(), DT);

    assertThat(enemy.getPosition().getY())
        .as("Enemy should be pushed away from impact center")
        .isGreaterThan(yBefore);
  }

  @Test
  void snowball_crownTowerTakesReducedDamage() {
    Tower tower = Tower.createPrincessTower(Team.RED, 10f, 16f, 1);
    int initialHp = tower.getHealth().getMax();
    gameState.spawnEntity(tower);
    gameState.processPending();

    spawnSnowball(Team.BLUE, 10f, 16f);
    advanceUntilResolved();

    // crownTowerDamagePercent = -70, so effective damage = 70 * (100 + (-70)) / 100 = 21
    int expectedDamage = DAMAGE * (100 + CROWN_TOWER_DAMAGE_PCT) / 100;
    assertThat(expectedDamage).as("Sanity check: expected crown tower damage").isEqualTo(21);

    assertThat(tower.getHealth().getCurrent())
        .as("Crown tower should take reduced damage (%d instead of %d)", expectedDamage, DAMAGE)
        .isEqualTo(initialHp - expectedDamage);
  }

  @Test
  void snowball_buildingsIgnoreKnockback() {
    Building building =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(10f, 16f))
            .health(new Health(500))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .lifetime(30f)
            .remainingLifetime(30f)
            .deployTime(1.0f)
            .deployTimer(0f)
            .build();

    gameState.spawnEntity(building);
    gameState.processPending();

    spawnSnowball(Team.BLUE, 10f, 17f);
    advanceUntilResolved();

    // Building should take damage but not be knocked back
    assertThat(building.getHealth().getCurrent())
        .as("Building should take damage from Snowball")
        .isEqualTo(500 - DAMAGE);
    assertThat(building.getMovement().isKnockedBack())
        .as("Building should not be knocked back")
        .isFalse();
  }

  @Test
  void snowball_hitsMultipleEnemiesInAoe() {
    Troop enemy1 = createTroop("Enemy1", Team.RED, 9.5f, 16f, 1000);
    Troop enemy2 = createTroop("Enemy2", Team.RED, 10.5f, 16f, 1000);
    gameState.spawnEntity(enemy1);
    gameState.spawnEntity(enemy2);
    gameState.processPending();
    enemy1.update(2.0f);
    enemy2.update(2.0f);

    spawnSnowball(Team.BLUE, 10f, 16f);
    advanceUntilResolved();

    assertThat(enemy1.getHealth().getCurrent())
        .as("Enemy1 should take damage")
        .isEqualTo(1000 - DAMAGE);
    assertThat(enemy2.getHealth().getCurrent())
        .as("Enemy2 should take damage")
        .isEqualTo(1000 - DAMAGE);
    assertThat(enemy1.getAppliedEffects()).anyMatch(e -> e.getType() == StatusEffectType.SLOW);
    assertThat(enemy2.getAppliedEffects()).anyMatch(e -> e.getType() == StatusEffectType.SLOW);
  }

  @Test
  void snowball_doesNotAffectFriendlyUnits() {
    Troop friendly = createTroop("Friendly", Team.BLUE, 10f, 16f, 1000);
    Troop enemy = createTroop("Enemy", Team.RED, 11f, 16f, 1000);
    gameState.spawnEntity(friendly);
    gameState.spawnEntity(enemy);
    gameState.processPending();
    friendly.update(2.0f);
    enemy.update(2.0f);

    spawnSnowball(Team.BLUE, 10f, 16f);
    advanceUntilResolved();

    assertThat(friendly.getHealth().getCurrent())
        .as("Friendly unit should not take damage from own Snowball")
        .isEqualTo(1000);
    assertThat(friendly.getAppliedEffects()).as("Friendly unit should not be slowed").isEmpty();
    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should still take damage")
        .isEqualTo(1000 - DAMAGE);
  }

  // -- Helper methods --

  /** Spawns a position-targeted Snowball projectile with all stats matching projectiles.json. */
  private void spawnSnowball(Team team, float targetX, float targetY) {
    float startY = (team == Team.BLUE) ? targetY - 10f : targetY + 10f;
    Projectile snowball =
        new Projectile(
            team,
            targetX,
            startY,
            targetX,
            targetY,
            DAMAGE,
            AOE_RADIUS,
            SPEED,
            SNOWBALL_EFFECTS,
            CROWN_TOWER_DAMAGE_PCT);
    snowball.setPushback(PUSHBACK);
    gameState.spawnProjectile(snowball);
  }

  /** Advances simulation until the snowball projectile has resolved. */
  private void advanceUntilResolved() {
    for (int tick = 0; tick < 300; tick++) {
      gameState.processPending();
      combatSystem.update(DT);
      if (gameState.getProjectiles().isEmpty()) {
        break;
      }
    }
  }

  private Troop createTroop(String name, Team team, float x, float y, int hp) {
    return Troop.builder()
        .name(name)
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(0)
                .range(1.0f)
                .sightRange(5.0f)
                .attackCooldown(1.0f)
                .targetType(TargetType.GROUND)
                .build())
        .deployTime(0.5f)
        .deployTimer(0.5f)
        .build();
  }
}
