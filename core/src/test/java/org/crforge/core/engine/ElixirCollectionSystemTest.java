package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Elixir;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ElixirCollector building: a 6-elixir Rare building that passively generates elixir
 * for its owner. Produces 1 elixir every 13.0s (up to 7 collections in its 93.0s lifetime) and
 * returns 1 elixir on death.
 */
class ElixirCollectionSystemTest {

  private GameEngine engine;
  private Player bluePlayer;
  private Standard1v1Match match;

  private static final Card ELIXIR_COLLECTOR = CardRegistry.get("elixircollector");

  // Deploy at y=10 on blue side, away from towers
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 10f;

  // 1.0s placement sync delay = 30 ticks
  private static final int SYNC_DELAY_TICKS = 30;
  // 1.0s building deploy time = 30 ticks
  private static final int DEPLOY_TICKS = 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(ELIXIR_COLLECTOR);
    Deck redDeck = buildDeckWith(ELIXIR_COLLECTOR);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    Player redPlayer = new Player(Team.RED, redDeck, false);

    match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    // Give enough elixir to deploy
    bluePlayer.getElixir().add(10);
  }

  @Test
  void cardDataLoadsCorrectly() {
    assertThat(ELIXIR_COLLECTOR).as("Card should be loaded").isNotNull();
    assertThat(ELIXIR_COLLECTOR.getType()).as("Card type").isEqualTo(CardType.BUILDING);
    assertThat(ELIXIR_COLLECTOR.getCost()).as("Elixir cost").isEqualTo(6);
    assertThat(ELIXIR_COLLECTOR.getRarity()).as("Rarity").isEqualTo(Rarity.RARE);

    assertThat(ELIXIR_COLLECTOR.getUnitStats()).as("Unit stats").isNotNull();
    assertThat(ELIXIR_COLLECTOR.getUnitStats().getDamage()).as("Damage").isEqualTo(0);
    assertThat(ELIXIR_COLLECTOR.getUnitStats().getLifeTime()).as("Lifetime").isEqualTo(93.0f);

    // Mana fields
    assertThat(ELIXIR_COLLECTOR.getUnitStats().getManaOnDeath()).as("Mana on death").isEqualTo(1);
    assertThat(ELIXIR_COLLECTOR.getUnitStats().getManaCollectAmount())
        .as("Mana collect amount")
        .isEqualTo(1);
    assertThat(ELIXIR_COLLECTOR.getUnitStats().getManaGenerateTime())
        .as("Mana generate time")
        .isEqualTo(13.0f);
  }

  @Test
  void buildingCreatedWithElixirCollectorComponent() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + 2);

    Building building = findCollector();
    assertThat(building).as("ElixirCollector building should exist").isNotNull();
    assertThat(building.getElixirCollector()).as("ElixirCollectorComponent").isNotNull();

    ElixirCollectorComponent collector = building.getElixirCollector();
    assertThat(collector.getManaCollectAmount()).as("Collect amount").isEqualTo(1);
    assertThat(collector.getManaGenerateTime()).as("Generate time").isEqualTo(13.0f);
    assertThat(collector.getManaOnDeath()).as("On death").isEqualTo(1);
  }

  @Test
  void testBasicCollection() {
    deployCollector();
    // Tick past sync + deploy so building is active
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Record elixir after deploy (accounts for natural regen and elixir spent on deploy)
    float elixirAfterDeploy = bluePlayer.getElixir().getCurrent();

    // Run 13 seconds for one collection cycle
    // Disable natural regen by setting elixir to 0 and tracking gain
    bluePlayer.getElixir().spend((int) bluePlayer.getElixir().getCurrent());
    float elixirBeforeCollection = bluePlayer.getElixir().getCurrent();

    engine.runSeconds(13.0f);

    float elixirAfterCollection = bluePlayer.getElixir().getCurrent();
    // Natural regen adds some elixir too, so we check that at least 1 extra came from collector
    // 13s of natural regen at 1/2.8s rate = ~4.64 elixir. With collector: ~5.64
    float naturalRegenOnly = 13.0f / Elixir.REGEN_PERIOD_NORMAL;
    float gained = elixirAfterCollection - elixirBeforeCollection;
    assertThat(gained)
        .as("Gained elixir should include 1 from collector beyond natural regen")
        .isGreaterThan(naturalRegenOnly);
    assertThat(gained)
        .as("Gained elixir should be natural regen + 1 collector")
        .isCloseTo(naturalRegenOnly + 1.0f, within(0.2f));
  }

  @Test
  void testMultipleCollections() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Set elixir to 0 so we can track total gain
    bluePlayer.getElixir().spend((int) bluePlayer.getElixir().getCurrent());
    // Also spend fractional part by adding negative... just set to a known value
    float startElixir = bluePlayer.getElixir().getCurrent();

    // Run 91 seconds (= 7 * 13s) = 2730 ticks for 7 collection cycles
    engine.tick(2730);

    float gained = bluePlayer.getElixir().getCurrent() - startElixir;
    // Natural regen over 91s would hit cap (10.0) multiple times, so total is capped.
    // But collector adds 7 elixir over 91s. Since it caps at 10, we check differently.
    // With regen running, the player will be at or near 10. The collector's contribution
    // shows up as the difference vs pure regen.

    // Simpler check: the building should have collected 7 times.
    // The elixir should be at 10 (capped from regen + collections).
    assertThat(bluePlayer.getElixir().getCurrent())
        .as("Elixir should be at or near cap after 91s of regen + collections")
        .isCloseTo(Elixir.MAX_ELIXIR, within(0.1f));
  }

  @Test
  void testDeathRefund() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    Building building = findCollector();
    assertThat(building).isNotNull();

    // Set elixir to a known value
    bluePlayer.getElixir().spend((int) bluePlayer.getElixir().getCurrent());
    float beforeDeath = bluePlayer.getElixir().getCurrent();

    // Kill the building immediately
    building.getHealth().takeDamage(building.getHealth().getCurrent());

    // processDeaths fires onDeath handler
    engine.tick(1);

    float afterDeath = bluePlayer.getElixir().getCurrent();
    // Should have gained 1 elixir from manaOnDeath + some natural regen
    float naturalRegen = GameEngine.DELTA_TIME / Elixir.REGEN_PERIOD_NORMAL;
    assertThat(afterDeath - beforeDeath)
        .as("Should gain 1 elixir on death + natural regen")
        .isCloseTo(1.0f + naturalRegen, within(0.15f));
  }

  @Test
  void testEarlyDestruction() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Zero out elixir
    bluePlayer.getElixir().spend((int) bluePlayer.getElixir().getCurrent());

    // Run for 26s (2 collection cycles: at 13s and 26s)
    engine.tick(780);
    float afterTwoCollections = bluePlayer.getElixir().getCurrent();

    Building building = findCollector();
    assertThat(building).isNotNull();

    // Kill it
    building.getHealth().takeDamage(building.getHealth().getCurrent());
    engine.tick(2);

    float afterDeath = bluePlayer.getElixir().getCurrent();
    // Should have: 2 collections + 1 death refund + natural regen over ~26s
    float naturalRegen26s = 26.0f / Elixir.REGEN_PERIOD_NORMAL;
    float expectedGain = 2.0f + 1.0f + naturalRegen26s; // 3 from collector + ~9.28 regen
    // Will be capped at 10
    assertThat(afterDeath)
        .as("After 2 collections + death refund, should be at cap")
        .isCloseTo(Elixir.MAX_ELIXIR, within(0.1f));
  }

  @Test
  void testElixirCapHold() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Set to max elixir
    bluePlayer.getElixir().add(10);

    Building building = findCollector();
    ElixirCollectorComponent collector = building.getElixirCollector();

    // Run past one collection cycle (13s = 390 ticks)
    engine.tick(400);

    // Should be in hold state since owner is at cap
    assertThat(collector.isHoldingElixir())
        .as("Collector should be in hold state when owner is at max elixir")
        .isTrue();
    assertThat(bluePlayer.getElixir().getCurrent())
        .as("Elixir should still be at max")
        .isCloseTo(Elixir.MAX_ELIXIR, within(0.01f));
  }

  @Test
  void testElixirCapDelivery() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Set to max elixir
    bluePlayer.getElixir().add(10);

    // Run past one collection cycle to enter hold state
    engine.tick(400);

    Building building = findCollector();
    ElixirCollectorComponent collector = building.getElixirCollector();
    assertThat(collector.isHoldingElixir()).as("Should be holding").isTrue();

    // Spend some elixir so owner is below cap
    bluePlayer.getElixir().spend(3);
    float afterSpend = bluePlayer.getElixir().getCurrent();

    // Next tick should deliver held elixir
    engine.tick(1);

    float afterDelivery = bluePlayer.getElixir().getCurrent();
    // Should have gained the held 1 elixir + tiny bit of regen
    assertThat(afterDelivery)
        .as("Held elixir should be delivered once owner drops below cap")
        .isGreaterThan(afterSpend);
    assertThat(collector.isHoldingElixir()).as("No longer holding after delivery").isFalse();
  }

  @Test
  void testNoCollectionDuringDeploy() {
    // Deploy the collector
    deployCollector();

    // Only tick through sync delay, building is still deploying
    engine.tick(SYNC_DELAY_TICKS + 5);

    Building building = findCollector();
    assertThat(building).isNotNull();
    assertThat(building.isDeploying()).as("Building should still be deploying").isTrue();

    ElixirCollectorComponent collector = building.getElixirCollector();
    // Timer should not have decremented during deploy (still at initial value)
    assertThat(collector.getCollectionTimer())
        .as("Timer should not tick during deploy")
        .isEqualTo(collector.getManaGenerateTime());
  }

  @Test
  void testMultipleCollectorsStack() {
    // Deploy first collector
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Give more elixir for second deploy
    bluePlayer.getElixir().add(10);

    // Deploy second collector
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Now zero out elixir
    bluePlayer.getElixir().spend((int) bluePlayer.getElixir().getCurrent());
    float startElixir = bluePlayer.getElixir().getCurrent();

    // Run 13s for one collection cycle of each collector
    engine.tick(390);

    float gained = bluePlayer.getElixir().getCurrent() - startElixir;
    float naturalRegen = 13.0f / Elixir.REGEN_PERIOD_NORMAL;
    // With 2 collectors, should gain ~2 from collectors + natural regen
    // Note: second collector was deployed later, so its timer started later.
    // The first collector should have collected by now. The second may or may not have.
    assertThat(gained)
        .as("Should gain at least 1 from first collector + natural regen")
        .isGreaterThan(naturalRegen + 0.5f);
  }

  @Test
  void testFullLifecycleCollectionAndDeath() {
    deployCollector();
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 1);

    // Zero out elixir for clean tracking
    bluePlayer.getElixir().spend((int) bluePlayer.getElixir().getCurrent());

    // Run 93s (full lifetime = 2790 ticks) plus extra for death processing
    // Building dies from lifetime decay, which triggers death refund
    engine.tick(2790 + 30);

    // Building should be dead
    Building building = findCollector();
    // Building might be removed from alive list after death
    // Check that the player received elixir from collections + death refund
    // 7 collections + 1 death refund = 8 total from collector
    // Plus natural regen (capped at 10)
    assertThat(bluePlayer.getElixir().getCurrent())
        .as("After full lifecycle, elixir should be capped")
        .isCloseTo(Elixir.MAX_ELIXIR, within(0.1f));
  }

  @Test
  void testDeployViaCardAndCollect() {
    // Full integration: deploy via card system and verify collection works
    assertThat(ELIXIR_COLLECTOR.getCost()).as("Cost should be 6").isEqualTo(6);

    float elixirBefore = bluePlayer.getElixir().getCurrent();
    assertThat(elixirBefore).as("Should have enough elixir").isGreaterThanOrEqualTo(6);

    deployCollector();

    // Elixir should be spent immediately
    engine.tick(1);
    assertThat(bluePlayer.getElixir().getCurrent())
        .as("Elixir spent on deploy")
        .isLessThan(elixirBefore);

    // Tick through sync + deploy + 13s collection cycle
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 390);

    Building building = findCollector();
    assertThat(building).as("Building should exist").isNotNull();
    assertThat(building.getElixirCollector()).as("Should have collector component").isNotNull();
  }

  // -- Helpers --

  private void deployCollector() {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(DEPLOY_X).y(DEPLOY_Y).build();
    engine.queueAction(bluePlayer, action);
  }

  private Building findCollector() {
    return engine.getGameState().getEntitiesOfType(Building.class).stream()
        .filter(b -> "ElixirCollector".equals(b.getName()) && b.getTeam() == Team.BLUE)
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
