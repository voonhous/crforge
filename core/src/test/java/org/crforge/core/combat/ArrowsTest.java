package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Arrows spell wave mechanic. Arrows fires 3 independent position-targeted
 * projectiles at frames 0, 6, 12 (projectileWaveInterval=0.2s, 0.2*30=6 frames). Each wave deals
 * the full per-wave damage (48). Crown towers take 25% damage (crownTowerDamagePercent=-75).
 */
class ArrowsTest {

  private GameState gameState;
  private CombatSystem combatSystem;

  // ArrowsSpell stats from projectiles.json (level-1 base values)
  private static final int DAMAGE_PER_VOLLEY = 48;
  private static final float AOE_RADIUS = 1.4f;
  private static final float SPEED = 1100f / 60f; // Raw CSV speed / SPEED_BASE
  private static final int CROWN_TOWER_DAMAGE_PCT = -75;
  private static final int PROJECTILE_WAVES = 3;
  private static final int WAVE_DELAY_FRAMES = 6; // 0.2s * 30fps
  private static final float DELTA_TIME = GameEngine.DELTA_TIME;

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
  void volley_spawnsThreeProjectiles() {
    // Spawn 3 volley projectiles like DeploymentSystem would
    spawnArrowsVolley(Team.BLUE, 9f, 20f);

    assertThat(gameState.getProjectiles())
        .as("Arrows should spawn 3 projectiles (one per wave)")
        .hasSize(3);
  }

  @Test
  void volley_staggeredArrival_damageInThreeWaves() {
    // Place a target at the arrow destination
    Troop target = createTarget(Team.RED, 9f, 20f, 1000);
    gameState.spawnEntity(target);
    gameState.processPending();
    target.update(2.0f); // Complete deploy

    spawnArrowsVolley(Team.BLUE, 9f, 20f);

    // Tick until the first (no-delay) projectile arrives
    int maxTicks = 300; // ~10 seconds, more than enough
    int hitCount = 0;
    int lastHitTick = -999;
    int[] hitTicks = new int[3];

    for (int tick = 0; tick < maxTicks; tick++) {
      int hpBefore = target.getHealth().getCurrent();

      gameState.processPending();
      combatSystem.update(DELTA_TIME);

      int hpAfter = target.getHealth().getCurrent();
      if (hpAfter < hpBefore) {
        if (hitCount < 3) {
          hitTicks[hitCount] = tick;
        }
        hitCount++;
        lastHitTick = tick;
      }

      // Stop once all projectiles have been processed
      if (gameState.getProjectiles().isEmpty()) {
        break;
      }
    }

    assertThat(hitCount).as("Target should be hit exactly 3 times (one per wave)").isEqualTo(3);

    // The gap between hits should be approximately WAVE_DELAY_FRAMES ticks
    // (travel time is the same for all, but each subsequent wave has an extra delay)
    int gap1 = hitTicks[1] - hitTicks[0];
    int gap2 = hitTicks[2] - hitTicks[1];
    assertThat(gap1)
        .as("Gap between wave 1 and 2 should be ~%d frames", WAVE_DELAY_FRAMES)
        .isEqualTo(WAVE_DELAY_FRAMES);
    assertThat(gap2)
        .as("Gap between wave 2 and 3 should be ~%d frames", WAVE_DELAY_FRAMES)
        .isEqualTo(WAVE_DELAY_FRAMES);
  }

  @Test
  void volley_totalDamageCorrect() {
    Troop target = createTarget(Team.RED, 9f, 20f, 1000);
    gameState.spawnEntity(target);
    gameState.processPending();
    target.update(2.0f);

    spawnArrowsVolley(Team.BLUE, 9f, 20f);

    // Tick until all projectiles are resolved
    for (int tick = 0; tick < 300; tick++) {
      gameState.processPending();
      combatSystem.update(DELTA_TIME);
      if (gameState.getProjectiles().isEmpty()) {
        break;
      }
    }

    int expectedTotal = DAMAGE_PER_VOLLEY * PROJECTILE_WAVES; // 48 * 3 = 144
    assertThat(target.getHealth().getCurrent())
        .as("Target should take %d total damage (3 waves of %d)", expectedTotal, DAMAGE_PER_VOLLEY)
        .isEqualTo(1000 - expectedTotal);
  }

  @Test
  void volley_crownTowerReduction() {
    // Create a princess tower at the target location
    Tower tower = Tower.createPrincessTower(Team.RED, 9f, 20f, 1);
    int initialHp = tower.getHealth().getMax();
    gameState.spawnEntity(tower);
    gameState.processPending();

    spawnArrowsVolley(Team.BLUE, 9f, 20f);

    for (int tick = 0; tick < 300; tick++) {
      gameState.processPending();
      combatSystem.update(DELTA_TIME);
      if (gameState.getProjectiles().isEmpty()) {
        break;
      }
    }

    // Each volley deals 25% of 48 = 12 damage to crown towers
    int expectedPerWave =
        DAMAGE_PER_VOLLEY * (100 + CROWN_TOWER_DAMAGE_PCT) / 100; // 48 * 25/100 = 12
    int expectedTotal = expectedPerWave * PROJECTILE_WAVES; // 12 * 3 = 36
    assertThat(tower.getHealth().getCurrent())
        .as(
            "Crown tower should take %d total damage (%d per wave at 25%%)",
            expectedTotal, expectedPerWave)
        .isEqualTo(initialHp - expectedTotal);
  }

  @Test
  void volley_unitMovesOutAfterFirstHit_takesPartialDamage() {
    // Place target at arrow destination
    Troop target = createTarget(Team.RED, 9f, 20f, 1000);
    gameState.spawnEntity(target);
    gameState.processPending();
    target.update(2.0f);

    spawnArrowsVolley(Team.BLUE, 9f, 20f);

    // Tick until first damage is applied, then move the target out of AOE range
    boolean firstHitDealt = false;
    for (int tick = 0; tick < 300; tick++) {
      int hpBefore = target.getHealth().getCurrent();

      gameState.processPending();
      combatSystem.update(DELTA_TIME);

      if (!firstHitDealt && target.getHealth().getCurrent() < hpBefore) {
        firstHitDealt = true;
        // Move target far away from the AOE zone
        target.getPosition().set(0f, 0f);
      }

      if (gameState.getProjectiles().isEmpty()) {
        break;
      }
    }

    assertThat(firstHitDealt).as("First wave should have hit").isTrue();
    // Only first wave should have dealt damage
    assertThat(target.getHealth().getCurrent())
        .as("Target should only take first wave damage (%d) after moving out", DAMAGE_PER_VOLLEY)
        .isEqualTo(1000 - DAMAGE_PER_VOLLEY);
  }

  @Test
  void delayFrames_projectileDoesNotMoveUntilDelayExpires() {
    // Create a single projectile with delayFrames set
    Projectile p =
        new Projectile(Team.BLUE, 9f, 10f, 9f, 20f, 100, 2.0f, SPEED, Collections.emptyList());
    p.setDelayFrames(6);

    float startX = p.getPosition().getX();
    float startY = p.getPosition().getY();

    // Tick for 5 frames -- projectile should not move
    for (int i = 0; i < 5; i++) {
      boolean hit = p.update(DELTA_TIME);
      assertThat(hit).isFalse();
    }
    assertThat(p.getPosition().getX()).isEqualTo(startX);
    assertThat(p.getPosition().getY()).isEqualTo(startY);

    // 6th tick consumes the last delay frame, still no movement
    p.update(DELTA_TIME);
    assertThat(p.getPosition().getX()).isEqualTo(startX);
    assertThat(p.getPosition().getY()).isEqualTo(startY);

    // 7th tick: delay expired, projectile should now move
    p.update(DELTA_TIME);
    float distMoved =
        (float)
            Math.sqrt(
                Math.pow(p.getPosition().getX() - startX, 2)
                    + Math.pow(p.getPosition().getY() - startY, 2));
    assertThat(distMoved).as("Projectile should have moved after delay expired").isGreaterThan(0f);
  }

  // -- Helper methods --

  /** Spawns 3 wave projectiles mimicking the DeploymentSystem castSpell() logic for Arrows. */
  private void spawnArrowsVolley(Team team, float targetX, float targetY) {
    float startY = (team == Team.BLUE) ? targetY - 10f : targetY + 10f;
    for (int i = 0; i < PROJECTILE_WAVES; i++) {
      Projectile p =
          new Projectile(
              team,
              targetX,
              startY,
              targetX,
              targetY,
              DAMAGE_PER_VOLLEY,
              AOE_RADIUS,
              SPEED,
              Collections.emptyList(),
              CROWN_TOWER_DAMAGE_PCT);
      if (i > 0) {
        p.setDelayFrames(i * WAVE_DELAY_FRAMES);
      }
      gameState.spawnProjectile(p);
    }
  }

  private Troop createTarget(Team team, float x, float y, int hp) {
    return Troop.builder()
        .name("Target")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.GROUND))
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
