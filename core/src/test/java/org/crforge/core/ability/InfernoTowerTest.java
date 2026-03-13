package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.BuildingTemplate;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.SimSystems;
import org.crforge.core.testing.TroopTemplate;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Inferno Tower -- a Building with the VARIABLE_DAMAGE ability. Verifies that
 * the ability system processes buildings, damage escalates through stages, stun/freeze resets the
 * inferno, and the deploying guard prevents premature attacks.
 */
class InfernoTowerTest {

  // Inferno Tower stage values (level 1 stats from units.json)
  private static final int STAGE_0_DAMAGE = 17;
  private static final int STAGE_1_DAMAGE = 62;
  private static final int STAGE_2_DAMAGE = 331;
  private static final float STAGE_DURATION = 2.0f;

  @Test
  void building_shouldSpawnWithVariableDamageAbility() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY)
            .spawn(infernoTower(Team.BLUE).at(9, 10))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");
    assertThat(tower.getAbility()).isNotNull();
    assertThat(tower.getAbility().getData().type()).isEqualTo(AbilityType.VARIABLE_DAMAGE);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
  }

  @Test
  void damage_shouldEscalateThroughStages() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY, SimSystems.COMBAT)
            .spawn(infernoTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Tank", Team.RED).hp(50000).at(9, 12))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");

    // Let targeting acquire the tank
    sim.tick(1);

    // Stage 0: initial damage
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_0_DAMAGE);

    // Advance 2 seconds (60 ticks) to reach stage 1
    sim.tick(60);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(1);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_1_DAMAGE);

    // Advance another 2 seconds to reach stage 2
    sim.tick(60);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(2);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_2_DAMAGE);
  }

  @Test
  void stun_shouldResetInfernoDamage() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(infernoTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Tank", Team.RED).hp(50000).at(9, 12))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");

    // Let targeting and ability ramp up to stage 1
    sim.tick(61);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(1);

    // Apply stun via the static resetVariableDamage (same code path as
    // AoeDamageService.applyEffects)
    AbilitySystem.resetVariableDamage(tower.getAbility(), tower.getCombat());

    // Should reset to stage 0
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(tower.getAbility().getStageTimer()).isEqualTo(0f);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_0_DAMAGE);
  }

  @Test
  void stun_shouldResetViaZapSpellDamage() {
    // Tests the full AoeDamageService code path: Zap spell deals damage + stun to the tower
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(infernoTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Tank", Team.RED).hp(50000).at(9, 12))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");
    int initialHp = tower.getHealth().getCurrent();

    // Ramp up to stage 1
    sim.tick(61);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(1);

    // Simulate Zap hitting the tower via applySpellDamage (public API)
    EffectStats stunEffect =
        EffectStats.builder()
            .type(StatusEffectType.STUN)
            .duration(0.5f)
            .applyAfterDamage(true)
            .build();
    sim.aoeDamageService().applySpellDamage(Team.RED, 9f, 10f, 75, 2.5f, List.of(stunEffect));

    // Tower should have taken Zap damage
    assertThat(tower.getHealth().getCurrent()).isLessThan(initialHp);

    // Variable damage should be fully reset to stage 0
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(tower.getAbility().getStageTimer()).isEqualTo(0f);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_0_DAMAGE);
  }

  @Test
  void targetSwitch_shouldResetDamageToStageZero() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY, SimSystems.COMBAT)
            .spawn(infernoTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Tank1", Team.RED).hp(50000).at(9, 12))
            .spawn(TroopTemplate.target("Tank2", Team.RED).hp(50000).at(9, 14))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");

    // Manually target Tank1 and ramp up
    Troop tank1 = sim.troop("Tank1");
    Troop tank2 = sim.troop("Tank2");
    tower.getCombat().setCurrentTarget(tank1);
    sim.tick(61);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(1);

    // Switch target to Tank2
    tower.getCombat().setCurrentTarget(tank2);
    sim.tick(1);

    // Should have reset back to stage 0
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_0_DAMAGE);
  }

  @Test
  void building_shouldNotAttackWhileDeploying() {
    // Use a non-zero deploy time and do NOT call deployed()
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.ABILITY, SimSystems.COMBAT)
            .spawn(infernoTower(Team.BLUE).at(9, 10).deployTime(1.0f))
            .spawn(TroopTemplate.target("Tank", Team.RED).hp(1000).at(9, 12))
            .build();

    Building tower = sim.building("InfernoTower");
    Troop tank = sim.troop("Tank");

    // Fast-forward the tank's deploy but NOT the tower's
    tank.update(2.0f);

    // Run for 15 ticks (0.5s) -- tower should still be deploying
    sim.tick(15);
    assertThat(tower.isDeploying()).isTrue();

    // Tank should not have taken any damage while tower is deploying
    assertThat(tank.getHealth().getCurrent()).isEqualTo(1000);

    // Ability should not have progressed either
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
  }

  @Test
  void lifetime_shouldDecayAndExpire() {
    // Short lifetime for test (3 seconds)
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING)
            .spawn(infernoTower(Team.BLUE).at(9, 10).lifetime(3.0f).hp(900))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");
    assertThat(tower.isAlive()).isTrue();

    // Run for 3 seconds (90 ticks) -- building should expire
    sim.tick(90);

    assertThat(tower.getHealth().getCurrent()).isLessThanOrEqualTo(0);
  }

  @Test
  void freeze_shouldResetInfernoDamageViaAreaEffect() {
    // Tests the AreaEffectSystem code path for freeze resetting variable damage on buildings
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(infernoTower(Team.BLUE).at(9, 10))
            .spawn(TroopTemplate.target("Tank", Team.RED).hp(50000).at(9, 12))
            .deployed()
            .build();

    Building tower = sim.building("InfernoTower");

    // Ramp up to stage 2
    sim.tick(121);
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(2);

    // Cast Freeze spell via AreaEffectSystem (same pattern as AbilitySystemTest)
    AreaEffectStats freezeStats =
        AreaEffectStats.builder()
            .name("Freeze")
            .radius(3.0f)
            .lifeDuration(0.1f)
            .buff("Freeze")
            .buffDuration(4.0f)
            .damage(0)
            .build();
    AreaEffect freeze =
        AreaEffect.builder()
            .name("Freeze")
            .team(Team.RED)
            .position(new Position(9, 10))
            .health(new Health(1))
            .movement(new Movement(0, 0, 0, 0, MovementType.GROUND))
            .stats(freezeStats)
            .remainingLifetime(0.1f)
            .build();
    sim.gameState().spawnEntity(freeze);

    // One tick processes the freeze area effect.
    // Note: AbilitySystem runs after AreaEffectSystem in the same tick, so it may
    // add one tick's worth of stage time after the reset. Check stage is 0 and
    // timer is at most one tick.
    sim.tick(1);

    // Variable damage should be fully reset to stage 0
    assertThat(tower.getAbility().getCurrentStage()).isEqualTo(0);
    assertThat(tower.getAbility().getStageTimer()).isLessThan(SimHarness.dt() + 0.001f);
    assertThat(tower.getCombat().getDamageOverride()).isEqualTo(STAGE_0_DAMAGE);
  }

  // -- Helper factory --

  /**
   * Creates a BuildingTemplate for an Inferno Tower with VARIABLE_DAMAGE ability. Uses Inferno
   * Tower's real stage values: 17 -> 62 -> 331 (2s per stage transition).
   */
  private BuildingTemplate infernoTower(Team team) {
    AbilityData varDmgData =
        new VariableDamageAbility(
            List.of(
                new VariableDamageStage(STAGE_0_DAMAGE, 0f),
                new VariableDamageStage(STAGE_1_DAMAGE, STAGE_DURATION),
                new VariableDamageStage(STAGE_2_DAMAGE, STAGE_DURATION)));

    Combat combat =
        Combat.builder()
            .damage(STAGE_0_DAMAGE)
            .range(6.0f)
            .sightRange(9.5f)
            .attackCooldown(0.4f)
            .loadTime(0.4f)
            .accumulatedLoadTime(0.4f)
            .build();

    return BuildingTemplate.defense("InfernoTower", team)
        .hp(1500)
        .lifetime(30f)
        .deployTime(1.0f)
        .combat(combat)
        .ability(new AbilityComponent(varDmgData));
  }
}
