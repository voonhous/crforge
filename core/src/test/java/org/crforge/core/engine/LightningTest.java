package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.TroopTemplate;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.Test;

/** Integration tests for the Lightning spell (hitBiggestTargets area effect). */
class LightningTest {

  @Test
  void lightning_shouldLoadFromCardRegistry() {
    Card card = CardRegistry.get("lightning");
    assertThat(card).isNotNull();
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getCost()).isEqualTo(6);
    assertThat(card.getRarity()).isEqualTo(Rarity.EPIC);

    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae).isNotNull();
    assertThat(ae.getName()).isEqualTo("Lightning");
    assertThat(ae.getRadius()).isEqualTo(3.5f);
    assertThat(ae.isHitBiggestTargets()).isTrue();
    assertThat(ae.getDamage()).isEqualTo(413);
    assertThat(ae.getHitSpeed()).isEqualTo(0.46f);
    assertThat(ae.getLifeDuration()).isEqualTo(1.5f);
    assertThat(ae.getBuff()).isEqualTo("ZapFreeze");
    assertThat(ae.getBuffDuration()).isEqualTo(0.5f);
    assertThat(ae.getCrownTowerDamagePercent()).isEqualTo(-73);
    assertThat(ae.isHitsGround()).isTrue();
    assertThat(ae.isHitsAir()).isTrue();
  }

  @Test
  void lightning_shouldHitThreeHighestHpTargets() {
    // Set up 3 enemies with different HP values in a cluster
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(TroopTemplate.target("Low", Team.RED).hp(300).at(10, 10))
            .spawn(TroopTemplate.target("Mid", Team.RED).hp(700).at(10, 11))
            .spawn(TroopTemplate.target("High", Team.RED).hp(1200).at(11, 10))
            .deployed()
            .build();

    // Deploy Lightning area effect centered on the cluster
    Card lightning = CardRegistry.get("lightning");
    AreaEffectStats stats = lightning.getAreaEffect();
    int scaledDamage = stats.getDamage(); // level 1, no scaling

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(scaledDamage)
            .remainingLifetime(stats.getLifeDuration())
            .build();

    sim.gameState().spawnEntity(effect);
    sim.gameState().processPending();

    Troop high = sim.troop("High");
    Troop mid = sim.troop("Mid");
    Troop low = sim.troop("Low");

    // Run enough ticks to cover all 3 strikes (1.5s = 45 ticks)
    sim.tick(45);

    // All 3 targets should have been damaged
    assertThat(high.getHealth().getCurrent()).isEqualTo(1200 - scaledDamage);
    assertThat(mid.getHealth().getCurrent()).isEqualTo(700 - scaledDamage);
    assertThat(low.getHealth().getCurrent()).isLessThan(300); // may be dead
  }

  @Test
  void lightning_shouldStunTargets() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(TroopTemplate.target("Victim", Team.RED).hp(2000).at(10, 10))
            .deployed()
            .build();

    Card lightning = CardRegistry.get("lightning");
    AreaEffectStats stats = lightning.getAreaEffect();

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(stats.getDamage())
            .remainingLifetime(stats.getLifeDuration())
            .build();

    sim.gameState().spawnEntity(effect);
    sim.gameState().processPending();

    // Advance past first tick (0.46s = ~14 ticks)
    sim.tick(15);

    Troop victim = sim.troop("Victim");
    boolean hasStun =
        victim.getAppliedEffects().stream().anyMatch(e -> e.getType() == StatusEffectType.STUN);
    assertThat(hasStun).as("Lightning should apply STUN via ZapFreeze buff").isTrue();
  }

  @Test
  void lightning_shouldDealReducedDamageToTower() {
    SimHarness sim = SimHarness.create().withAllSystems().deployed().build();

    // Manually spawn a princess tower within Lightning range
    org.crforge.core.entity.structure.Tower tower =
        org.crforge.core.entity.structure.Tower.createPrincessTower(Team.RED, 10, 10, 1);
    tower.update(2.0f); // make targetable
    sim.gameState().spawnEntity(tower);
    sim.gameState().processPending();

    int towerHpBefore = tower.getHealth().getCurrent();

    Card lightning = CardRegistry.get("lightning");
    AreaEffectStats stats = lightning.getAreaEffect();

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .stats(stats)
            .scaledDamage(stats.getDamage())
            .remainingLifetime(stats.getLifeDuration())
            .build();

    sim.gameState().spawnEntity(effect);
    sim.gameState().processPending();

    // Advance past first strike
    sim.tick(15);

    // Crown tower damage: 413 * (100 + (-73)) / 100 = 413 * 27 / 100 = 111
    int expectedDamage = stats.getDamage() * 27 / 100;
    assertThat(tower.getHealth().getCurrent()).isEqualTo(towerHpBefore - expectedDamage);
  }

  @Test
  void lightning_levelScalingApplied() {
    Card lightning = CardRegistry.get("lightning");
    AreaEffectStats stats = lightning.getAreaEffect();

    // Level 11 scaling for Epic rarity (Epic min level = 6, so 5 upgrade steps)
    int scaledDamage = LevelScaling.scaleCard(stats.getDamage(), Rarity.EPIC, 11);
    assertThat(scaledDamage).isGreaterThan(stats.getDamage());

    // Verify via LevelScaling's multiplier formula: m starts at 100, floor(m * 1.10) per step
    int m = 100;
    for (int i = 0; i < 5; i++) {
      m = (int) Math.floor(m * 1.10);
    }
    int expected = (int) Math.floor(stats.getDamage() * m / 100.0);
    assertThat(scaledDamage).isEqualTo(expected);
  }
}
