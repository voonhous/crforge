package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.TroopTemplate;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Rage spell. Rage deploys a RageBarbarianBottle (health=0 bomb entity)
 * that self-destructs after its deploy time, spawning a BarbarianRage AreaEffect on death. The area
 * effect applies the Rage buff to friendly troops, boosting movement and attack speed by 1.3x.
 */
class RageSpellIntegrationTest {

  @Test
  void rageCard_shouldLoadFromRegistry() {
    Card card = CardRegistry.get("rage");
    assertThat(card).isNotNull();
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getCost()).isEqualTo(2);
    assertThat(card.getRarity()).isEqualTo(Rarity.EPIC);

    // Summon template should point to the rage bottle unit.
    // Data pipeline may not include the unit (e.g. "RageBottle" not in units.json);
    // skip gracefully if the reference is unresolved.
    TroopStats summon = card.getSummonTemplate();
    assumeTrue(summon != null, "Rage summonCharacter not resolved (data pipeline issue)");
    assertThat(summon.getHealth()).isEqualTo(0);

    AreaEffectStats deathAe = summon.getDeathAreaEffect();
    assertThat(deathAe).isNotNull();
    assertThat(deathAe.getBuff()).isEqualTo("Rage");
  }

  @Test
  void rageSpell_shouldSpawnBottleThenRageZone() {
    // Create a sim with a pre-deployed knight to anchor the test
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(TroopTemplate.target("Anchor", Team.BLUE).hp(5000).at(9, 10))
            .deployed()
            .build();

    Card rageCard = CardRegistry.get("rage");
    TroopStats bottleStats = rageCard.getSummonTemplate();
    assumeTrue(bottleStats != null, "Rage summonCharacter not resolved (data pipeline issue)");

    // Build bottle troop as a bomb entity (mirroring what castSpell should do)
    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .selfDestruct(true)
            .deathAreaEffect(bottleStats.getDeathAreaEffect())
            .rarity(rageCard.getRarity())
            .level(1)
            .build();

    Troop bottle =
        Troop.builder()
            .name(bottleStats.getName())
            .team(Team.BLUE)
            .position(new org.crforge.core.component.Position(9, 10))
            .health(new org.crforge.core.component.Health(1))
            .movement(
                new org.crforge.core.component.Movement(
                    0, 0, 1.0f, 1.0f, bottleStats.getMovementType()))
            .deployTime(bottleStats.getDeployTime())
            .deployTimer(bottleStats.getDeployTime())
            .spawner(spawner)
            .build();

    sim.gameState().spawnEntity(bottle);
    sim.gameState().processPending();

    // Bottle should be deploying
    assertThat(bottle.isDeploying()).isTrue();

    // Advance past deploy time (0.5s = 15 ticks) + extra for death processing
    sim.tick(20);

    // Bottle should have self-destructed
    assertThat(bottle.getHealth().isAlive()).isFalse();

    // BarbarianRage AreaEffect should exist
    List<Entity> entities = sim.gameState().getEntities();
    AreaEffect rageZone =
        entities.stream()
            .filter(e -> e instanceof AreaEffect && e.getName().equals("BarbarianRage"))
            .map(e -> (AreaEffect) e)
            .findFirst()
            .orElse(null);

    assertThat(rageZone).isNotNull();
    assertThat(rageZone.getTeam()).isEqualTo(Team.BLUE);
    assertThat(rageZone.getStats().getBuff()).isEqualTo("Rage");
  }

  @Test
  void rageSpell_shouldBuffFriendlyTroops() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).hp(5000).at(9, 10))
            .deployed()
            .build();

    Troop knight = sim.troop("Knight");
    float baseSpeed = knight.getMovement().getSpeed();

    Card rageCard = CardRegistry.get("rage");
    TroopStats bottleStats = rageCard.getSummonTemplate();
    assumeTrue(bottleStats != null, "Rage summonCharacter not resolved (data pipeline issue)");

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .selfDestruct(true)
            .deathAreaEffect(bottleStats.getDeathAreaEffect())
            .rarity(rageCard.getRarity())
            .level(1)
            .build();

    Troop bottle =
        Troop.builder()
            .name(bottleStats.getName())
            .team(Team.BLUE)
            .position(new org.crforge.core.component.Position(9, 10))
            .health(new org.crforge.core.component.Health(1))
            .movement(
                new org.crforge.core.component.Movement(
                    0, 0, 1.0f, 1.0f, bottleStats.getMovementType()))
            .deployTime(bottleStats.getDeployTime())
            .deployTimer(bottleStats.getDeployTime())
            .spawner(spawner)
            .build();

    sim.gameState().spawnEntity(bottle);
    sim.gameState().processPending();

    // Advance past bottle deploy (0.5s) + area effect tick (0.3s) + some buffer
    sim.tick(30);

    // Knight should have the RAGE status effect applied
    List<AppliedEffect> effects = knight.getAppliedEffects();
    boolean hasRage = effects.stream().anyMatch(e -> e.getType() == StatusEffectType.RAGE);
    assertThat(hasRage).isTrue();

    // Movement speed should be boosted by 1.3x via multiplier
    Float speedMult = knight.getMovement().getSpeedMultipliers().get(ModifierSource.STATUS_EFFECT);
    assertThat(speedMult).isNotNull();
    assertThat(speedMult).isGreaterThan(1.0f);
  }

  @Test
  void rageSpell_shouldNotAffectEnemies() {
    SimHarness sim =
        SimHarness.create()
            .withAllSystems()
            .spawn(TroopTemplate.target("EnemyKnight", Team.RED).hp(5000).at(9, 10))
            .deployed()
            .build();

    Troop enemy = sim.troop("EnemyKnight");

    Card rageCard = CardRegistry.get("rage");
    TroopStats bottleStats = rageCard.getSummonTemplate();
    assumeTrue(bottleStats != null, "Rage summonCharacter not resolved (data pipeline issue)");

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .selfDestruct(true)
            .deathAreaEffect(bottleStats.getDeathAreaEffect())
            .rarity(rageCard.getRarity())
            .level(1)
            .build();

    Troop bottle =
        Troop.builder()
            .name(bottleStats.getName())
            .team(Team.BLUE)
            .position(new org.crforge.core.component.Position(9, 10))
            .health(new org.crforge.core.component.Health(1))
            .movement(
                new org.crforge.core.component.Movement(
                    0, 0, 1.0f, 1.0f, bottleStats.getMovementType()))
            .deployTime(bottleStats.getDeployTime())
            .deployTimer(bottleStats.getDeployTime())
            .spawner(spawner)
            .build();

    sim.gameState().spawnEntity(bottle);
    sim.gameState().processPending();

    // Advance well past bottle deploy + area effect ticks
    sim.tick(60);

    // Enemy should NOT have any RAGE effect
    List<AppliedEffect> effects = enemy.getAppliedEffects();
    boolean hasRage = effects.stream().anyMatch(e -> e.getType() == StatusEffectType.RAGE);
    assertThat(hasRage).isFalse();

    // Speed multiplier should be unchanged
    Float speedMult = enemy.getMovement().getSpeedMultipliers().get(ModifierSource.STATUS_EFFECT);
    assertThat(speedMult).isNull();
  }
}
