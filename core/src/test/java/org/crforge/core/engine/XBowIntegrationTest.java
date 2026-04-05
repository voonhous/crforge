package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.BuildingTemplate;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.TroopTemplate;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for X-Bow -- a siege building with very long range (11.5 tiles) that fires
 * rapid homing projectiles targeting ground units only. Verifies card loading, targeting behavior,
 * projectile spawning, deploy guard, rapid fire damage, and siege range.
 */
class XBowIntegrationTest {

  // X-Bow level 1 stats from units.json
  private static final int XBOW_HP = 625;
  private static final int XBOW_DAMAGE = 17;
  private static final float XBOW_RANGE = 11.5f;
  private static final float XBOW_SIGHT_RANGE = 11.5f;
  private static final float XBOW_ATTACK_COOLDOWN = 0.3f;
  private static final float XBOW_DEPLOY_TIME = 3.5f;
  private static final float XBOW_LIFETIME = 30.0f;
  private static final float XBOW_COLLISION_RADIUS = 0.6f;

  // xbow_projectile stats
  private static final float XBOW_PROJECTILE_SPEED = 1400f / 60f;

  @Test
  void cardRegistry_shouldLoadXBowWithCorrectStats() {
    Card card = CardRegistry.get("xbow");
    assertThat(card).isNotNull();
    assertThat(card.getType()).isEqualTo(CardType.BUILDING);
    assertThat(card.getCost()).isEqualTo(6);
    assertThat(card.getRarity()).isEqualTo(Rarity.EPIC);

    // Unit stats should reference the Xbow unit
    TroopStats unitStats = card.getUnitStats();
    assertThat(unitStats).isNotNull();
    assertThat(unitStats.getName()).isEqualTo("Xbow");
    assertThat(unitStats.getHealth()).isEqualTo(XBOW_HP);
    assertThat(unitStats.getDamage()).isEqualTo(XBOW_DAMAGE);
    assertThat(unitStats.getRange()).isEqualTo(XBOW_RANGE);
    assertThat(unitStats.getSightRange()).isEqualTo(XBOW_SIGHT_RANGE);
    assertThat(unitStats.getAttackCooldown()).isEqualTo(XBOW_ATTACK_COOLDOWN);
    assertThat(unitStats.getLifeTime()).isEqualTo(XBOW_LIFETIME);
    assertThat(unitStats.getTargetType()).isEqualTo(TargetType.GROUND);

    // Should have a homing projectile with no AOE
    ProjectileStats projectile = unitStats.getProjectile();
    assertThat(projectile).isNotNull();
    assertThat(projectile.getName()).isEqualTo("xbow_projectile");
    assertThat(projectile.isHoming()).isTrue();
    assertThat(projectile.isAoeToAir()).isFalse();
    assertThat(projectile.isAoeToGround()).isFalse();
  }

  @Test
  void shouldTargetNearestGroundEnemy() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(xbow(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("FarTarget", Team.RED).hp(5000).at(9, 18))
            .spawn(TroopTemplate.target("NearTarget", Team.RED).hp(5000).at(9, 14))
            .deployed()
            .build();

    Building tower = sim.building("Xbow");

    // Let targeting acquire an enemy
    sim.tick(2);

    // Should target the nearest ground enemy
    assertThat(tower.getCombat().getCurrentTarget()).isEqualTo(sim.troop("NearTarget"));
  }

  @Test
  void shouldIgnoreAirUnits() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(xbow(Team.BLUE).at(9, 10))
            // Air troop close
            .spawn(TroopTemplate.air("AirTarget", Team.RED).hp(5000).damage(0).speed(0f).at(9, 12))
            // Ground troop farther away
            .spawn(TroopTemplate.target("GroundTarget", Team.RED).hp(5000).at(9, 18))
            .deployed()
            .build();

    Building tower = sim.building("Xbow");
    Troop airTarget = sim.troop("AirTarget");

    // Run enough ticks for targeting + combat
    sim.tick(120);

    // Air target should be untouched
    assertThat(airTarget.getHealth().getCurrent()).isEqualTo(5000);

    // Tower should have targeted the ground troop, not the air troop
    assertThat(tower.getCombat().getCurrentTarget()).isNotEqualTo(airTarget);
  }

  @Test
  void shouldFireHomingProjectiles() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(xbow(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Target", Team.RED).hp(5000).at(9, 14))
            .deployed()
            .build();

    // Tick enough for first attack (targeting + first attack cooldown of 0.3s)
    sim.tickSeconds(0.5f);

    // At least one projectile should have been spawned (projectiles live in a separate list)
    assertThat(sim.gameState().getProjectiles())
        .as("X-Bow should have fired at least one projectile")
        .isNotEmpty();
  }

  @Test
  void shouldNotAttackWhileDeploying() {
    // Do NOT call deployed() -- building starts with default deploy timer
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(xbow(Team.BLUE).at(9, 10).deployTime(1.0f))
            .spawn(TroopTemplate.target("Target", Team.RED).hp(5000).at(9, 14))
            .build();

    Building tower = sim.building("Xbow");
    Troop target = sim.troop("Target");

    // Fast-forward the target's deploy but not the tower's
    target.update(2.0f);

    // Run for 15 ticks (0.5s) -- tower should still be deploying
    sim.tick(15);
    assertThat(tower.isDeploying()).isTrue();

    // No target should have been acquired while deploying
    assertThat(tower.getCombat().getCurrentTarget()).isNull();

    // Target should not have taken any damage while tower is deploying
    assertThat(target.getHealth().getCurrent()).isEqualTo(5000);
  }

  @Test
  void shouldDealDamageWithRapidFire() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(xbow(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Tank", Team.RED).hp(5000).at(9, 14))
            .deployed()
            .build();

    Troop tank = sim.troop("Tank");
    int initialHp = tank.getHealth().getCurrent();

    // Run for 3 seconds (90 ticks) -- X-Bow fires every 0.3s, so ~10 shots
    sim.tick(90);

    // Should have dealt significant damage from multiple rapid hits
    int damageTaken = initialHp - tank.getHealth().getCurrent();
    assertThat(damageTaken)
        .as("X-Bow should deal significant damage over 3 seconds of rapid fire")
        .isGreaterThan(XBOW_DAMAGE * 3);
  }

  @Test
  void siegeRange_shouldReachDistantTargets() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            // X-Bow at (9, 10), target ~11 tiles away at (9, 21) -- within 11.5 range
            .spawn(xbow(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("InRange", Team.RED).hp(5000).at(9, 21))
            // Target ~14 tiles away at (9, 24) -- outside 11.5 range
            .spawn(TroopTemplate.target("OutOfRange", Team.RED).hp(5000).at(9, 24))
            .deployed()
            .build();

    Building tower = sim.building("Xbow");
    Troop inRange = sim.troop("InRange");
    Troop outOfRange = sim.troop("OutOfRange");

    // Run for 3 seconds to allow targeting + projectile travel over long distance
    sim.tick(90);

    // In-range target should have taken damage
    assertThat(inRange.getHealth().getCurrent()).isLessThan(5000);

    // Out-of-range target should be untouched
    assertThat(outOfRange.getHealth().getCurrent()).isEqualTo(5000);

    // Tower should be targeting the in-range target
    assertThat(tower.getCombat().getCurrentTarget()).isEqualTo(inRange);
  }

  // -- Helper factory --

  /**
   * Creates a BuildingTemplate for an X-Bow with rapid-fire homing projectile combat targeting
   * ground only. Uses level 1 stats from units.json.
   */
  private BuildingTemplate xbow(Team team) {
    ProjectileStats projectile =
        ProjectileStats.builder()
            .name("xbow_projectile")
            .damage(XBOW_DAMAGE)
            .speed(XBOW_PROJECTILE_SPEED)
            .homing(true)
            .aoeToAir(false)
            .aoeToGround(false)
            .build();

    Combat combat =
        Combat.builder()
            .damage(XBOW_DAMAGE)
            .range(XBOW_RANGE)
            .sightRange(XBOW_SIGHT_RANGE)
            .attackCooldown(XBOW_ATTACK_COOLDOWN)
            .targetType(TargetType.GROUND)
            .projectileStats(projectile)
            .build();

    return BuildingTemplate.defense("Xbow", team)
        .hp(XBOW_HP)
        .lifetime(XBOW_LIFETIME)
        .deployTime(XBOW_DEPLOY_TIME)
        .collisionRadius(XBOW_COLLISION_RADIUS)
        .combat(combat);
  }
}
