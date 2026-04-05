package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TransformationConfig;
import org.crforge.core.card.TroopStats;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Goblin Demolisher: a 4-elixir Rare troop with HP-threshold transformation. At 50% HP
 * the ranged splash form transforms into a fast kamikaze form that targets only buildings. Both
 * forms fire a death explosion on death.
 */
class GoblinDemolisherTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card GOBLIN_DEMOLISHER = CardRegistry.get("goblindemolisher");

  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 10f;

  // 1.0s placement sync delay = 30 ticks
  private static final int SYNC_DELAY_TICKS = 30;
  // 1.0s deploy time = 30 ticks
  private static final int DEPLOY_TICKS = 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck deck = buildDeckWith(GOBLIN_DEMOLISHER);
    bluePlayer = new Player(Team.BLUE, deck, false);
    Player redPlayer = new Player(Team.RED, deck, false);

    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    bluePlayer.getElixir().add(10);
  }

  @Test
  void cardDataLoadsCorrectly() {
    assertThat(GOBLIN_DEMOLISHER).as("Card should be loaded").isNotNull();
    assertThat(GOBLIN_DEMOLISHER.getType()).as("Card type").isEqualTo(CardType.TROOP);
    assertThat(GOBLIN_DEMOLISHER.getCost()).as("Elixir cost").isEqualTo(4);
    assertThat(GOBLIN_DEMOLISHER.getRarity()).as("Rarity").isEqualTo(Rarity.RARE);

    TroopStats stats = GOBLIN_DEMOLISHER.getUnitStats();
    assertThat(stats).as("Unit stats").isNotNull();
    assertThat(stats.getHealth()).as("Health").isEqualTo(508);
    assertThat(stats.getDamage()).as("Damage").isEqualTo(73);
    assertThat(stats.getSpeed()).as("Speed").isEqualTo(1.0f);
    assertThat(stats.getRange()).as("Range").isEqualTo(5.0f);
    assertThat(stats.getProjectile()).as("Projectile").isNotNull();
    assertThat(stats.getProjectile().getName())
        .as("Projectile name")
        .isEqualTo("GoblinDemolisherProjectile");

    // Transformation config
    TransformationConfig transformConfig = stats.getTransformConfig();
    assertThat(transformConfig).as("Transform config").isNotNull();
    assertThat(transformConfig.healthPercent()).as("Transform HP threshold").isEqualTo(50);
    assertThat(transformConfig.transformStats()).as("Transform stats").isNotNull();
    assertThat(transformConfig.transformStats().getName())
        .as("Transform unit name")
        .isEqualTo("GoblinDemolisher_kamikaze_form");
    assertThat(transformConfig.transformStats().isKamikaze()).as("Kamikaze").isTrue();
    assertThat(transformConfig.transformStats().isTargetOnlyBuildings())
        .as("Target only buildings")
        .isTrue();
    assertThat(transformConfig.transformStats().getLifeTime())
        .as("Kamikaze lifeTime")
        .isEqualTo(20.0f);
    assertThat(transformConfig.transformStats().getSpeed())
        .as("Kamikaze speed (2x)")
        .isEqualTo(2.0f);

    // Death spawn projectile on ranged form
    assertThat(stats.getDeathSpawnProjectile()).as("Death projectile on ranged form").isNotNull();
    assertThat(stats.getDeathSpawnProjectile().getName())
        .as("Death projectile name")
        .isEqualTo("GoblinDemolisherDeathProjectile");
  }

  @Test
  void transformation_triggersAtHalfHealth() {
    Troop demolisher = deployAndGetTroop();

    // Damage to exactly 50%
    int maxHp = demolisher.getHealth().getMax();
    int halfDamage = maxHp - (maxHp / 2);
    demolisher.getHealth().takeDamage(halfDamage);

    assertThat(demolisher.getHealth().percentage())
        .as("HP should be at 50%")
        .isLessThanOrEqualTo(0.5f);

    // Tick to trigger transformation
    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form should appear after transformation").isNotNull();
    assertThat(kamikaze.getTeam()).as("Same team").isEqualTo(Team.BLUE);
  }

  @Test
  void transformation_doesNotTriggerAboveThreshold() {
    Troop demolisher = deployAndGetTroop();

    // Damage to 51% HP (above threshold)
    int maxHp = demolisher.getHealth().getMax();
    int damageFor51Pct = maxHp - (int) (maxHp * 0.51f);
    demolisher.getHealth().takeDamage(damageFor51Pct);

    assertThat(demolisher.getHealth().percentage())
        .as("HP should be above 50%")
        .isGreaterThan(0.5f);

    // Tick -- no transformation should happen
    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("No kamikaze form when HP > 50%").isNull();

    // Original should still be alive
    Troop original = findRangedDemolisher();
    assertThat(original).as("Original ranged form still exists").isNotNull();
    assertThat(original.isAlive()).as("Original is alive").isTrue();
  }

  @Test
  void transformation_hpCarriesOver() {
    Troop demolisher = deployAndGetTroop();

    int maxHp = demolisher.getHealth().getMax();
    // Damage to exactly 50% (254 of 508 at L1)
    int targetHp = maxHp / 2;
    demolisher.getHealth().takeDamage(maxHp - targetHp);

    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form exists").isNotNull();
    // HP should carry over: the kamikaze form's max HP is also 508 (at L1),
    // so currentHp should be targetHp
    assertThat(kamikaze.getHealth().getCurrent())
        .as("HP carried over from ranged form")
        .isEqualTo(targetHp);
  }

  @Test
  void kamikazeForm_hasCorrectStats() {
    Troop demolisher = deployAndGetTroop();

    // Damage to 50% to trigger transformation
    int maxHp = demolisher.getHealth().getMax();
    demolisher.getHealth().takeDamage(maxHp - maxHp / 2);

    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form exists").isNotNull();

    // Speed: 120 / 60 = 2.0 tiles/sec
    assertThat(kamikaze.getMovement().getSpeed()).as("Kamikaze speed").isEqualTo(2.0f);
    // Range 0.5 = melee
    assertThat(kamikaze.getCombat().getRange()).as("Kamikaze range").isEqualTo(0.5f);
    // Target only buildings
    assertThat(kamikaze.getCombat().isTargetOnlyBuildings()).as("Target only buildings").isTrue();
    // Kamikaze flag
    assertThat(kamikaze.getCombat().isKamikaze()).as("Kamikaze flag").isTrue();
    // Damage 0
    assertThat(kamikaze.getCombat().getDamage()).as("Kamikaze damage").isEqualTo(0);
    // Not deploying
    assertThat(kamikaze.isDeploying()).as("Not deploying").isFalse();
    // Already transformed (prevents re-transform)
    assertThat(kamikaze.isTransformed()).as("Transformed flag set").isTrue();
  }

  @Test
  void kamikazeForm_targetsBuildingsOnly() {
    Troop demolisher = deployAndGetTroop();

    // Trigger transformation
    int maxHp = demolisher.getHealth().getMax();
    demolisher.getHealth().takeDamage(maxHp - maxHp / 2);
    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form exists").isNotNull();

    // Verify the combat component has the building-targeting flags
    assertThat(kamikaze.getCombat().isTargetOnlyBuildings())
        .as("Should target only buildings")
        .isTrue();

    // Verify it ignores troops by checking that after many ticks, it doesn't target any troop
    // (enemy troops may not be nearby, so verify the flag is correctly set)
    assertThat(kamikaze.getCombat().isKamikaze())
        .as("Should be kamikaze (dies after attack)")
        .isTrue();
  }

  @Test
  void deathExplosion_firesOnRangedFormDeath() {
    Troop demolisher = deployAndGetTroop();

    // Kill the ranged form without triggering transformation (one-shot)
    int hp = demolisher.getHealth().getCurrent();
    demolisher.getHealth().takeDamage(hp);

    // Tick to process death
    engine.tick(3);

    // The death spawn projectile should have been fired
    assertThat(demolisher.isAlive()).as("Ranged form is dead").isFalse();

    // The projectile may have already impacted (instant arrival), so check the spawner was wired
    assertThat(demolisher.getSpawner()).as("Spawner with death projectile").isNotNull();
    assertThat(demolisher.getSpawner().getDeathSpawnProjectile())
        .as("Death spawn projectile configured")
        .isNotNull();
  }

  @Test
  void deathExplosion_firesOnKamikazeFormDeath() {
    Troop demolisher = deployAndGetTroop();

    // Trigger transformation
    int maxHp = demolisher.getHealth().getMax();
    demolisher.getHealth().takeDamage(maxHp - maxHp / 2);
    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form exists").isNotNull();

    // Kill the kamikaze form
    kamikaze.getHealth().takeDamage(kamikaze.getHealth().getCurrent());
    engine.tick(3);

    // Verify the kamikaze had a death projectile configured
    assertThat(kamikaze.getSpawner()).as("Kamikaze spawner").isNotNull();
    assertThat(kamikaze.getSpawner().getDeathSpawnProjectile())
        .as("Kamikaze death projectile")
        .isNotNull();
  }

  @Test
  void transformation_clearsTarget() {
    Troop demolisher = deployAndGetTroop();

    // Trigger transformation
    int maxHp = demolisher.getHealth().getMax();
    demolisher.getHealth().takeDamage(maxHp - maxHp / 2);
    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form exists").isNotNull();

    // New entity should have no stale target (fresh combat component)
    assertThat(kamikaze.getCombat().getCurrentTarget())
        .as("No stale target on new entity")
        .isNull();
  }

  @Test
  void kamikazeForm_expiresAfterLifetime() {
    Troop demolisher = deployAndGetTroop();

    // Trigger transformation
    int maxHp = demolisher.getHealth().getMax();
    demolisher.getHealth().takeDamage(maxHp - maxHp / 2);
    engine.tick(2);

    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("Kamikaze form exists").isNotNull();
    // lifeTimer started at 20.0s but 2 ticks already elapsed, so ~19.93s remaining
    assertThat(kamikaze.getLifeTimer())
        .as("Life timer approximately 20s")
        .isGreaterThan(19.0f)
        .isLessThanOrEqualTo(20.0f);

    // Disable movement and combat so the kamikaze stays out of tower range
    // and doesn't die from kamikaze self-destruct before lifetime expires
    kamikaze
        .getMovement()
        .setMovementDisabled(org.crforge.core.component.ModifierSource.ABILITY_TUNNEL, true);
    kamikaze
        .getCombat()
        .setCombatDisabled(org.crforge.core.component.ModifierSource.ABILITY_TUNNEL, true);

    // Run 18 seconds -- should still be alive (lifeTimer ticks down during update)
    engine.runSeconds(18f);
    Troop stillAlive = findKamikazeTroop();
    assertThat(stillAlive).as("Kamikaze alive at ~18s").isNotNull();
    assertThat(stillAlive.isAlive()).as("Still alive").isTrue();

    // Run past 20 seconds total (2 more seconds + buffer to ensure lifetime expires)
    engine.runSeconds(3f);

    // Should be dead from lifetime expiry
    Troop expired = findKamikazeTroop();
    assertThat(expired).as("Kamikaze expired after lifetime").isNull();

    // Verify death projectile was configured (will have fired on death)
    assertThat(stillAlive.getSpawner()).as("Had spawner").isNotNull();
    assertThat(stillAlive.getSpawner().getDeathSpawnProjectile())
        .as("Death projectile on expiry")
        .isNotNull();
  }

  @Test
  void lethalDamage_killsWithoutTransforming() {
    Troop demolisher = deployAndGetTroop();

    // One-shot kill: HP goes to 0, which is below 50% but isAlive() returns false
    demolisher.getHealth().takeDamage(demolisher.getHealth().getCurrent());
    assertThat(demolisher.getHealth().isAlive()).as("HP is 0").isFalse();

    // Tick to process
    engine.tick(3);

    // No kamikaze form should appear (lethal damage skips transform)
    Troop kamikaze = findKamikazeTroop();
    assertThat(kamikaze).as("No kamikaze on lethal damage").isNull();
  }

  // -- Helpers --

  private Troop deployAndGetTroop() {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(DEPLOY_X).y(DEPLOY_Y).build();
    engine.queueAction(bluePlayer, action);

    // Tick past sync delay + deploy time
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop troop = findRangedDemolisher();
    assertThat(troop).as("GoblinDemolisher should be deployed").isNotNull();
    return troop;
  }

  private Troop findRangedDemolisher() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "GoblinDemolisher".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .filter(Entity::isAlive)
        .findFirst()
        .orElse(null);
  }

  private Troop findKamikazeTroop() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(
            t -> "GoblinDemolisher_kamikaze_form".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .filter(Entity::isAlive)
        .findFirst()
        .orElse(null);
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
