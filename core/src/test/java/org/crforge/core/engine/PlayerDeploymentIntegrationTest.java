package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardRegistry;
import org.crforge.core.entity.AbstractEntity;
import org.crforge.core.entity.EntityType;
import org.crforge.core.entity.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the full player deployment flow.
 */
class PlayerDeploymentIntegrationTest {

  private GameEngine engine;
  private Standard1v1Match match;
  private Player bluePlayer;
  private Player redPlayer;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();

    // Create deck with real cards from registry
    List<Card> deckCards = new ArrayList<>();
    deckCards.add(CardRegistry.get("knight"));
    deckCards.add(CardRegistry.get("giant"));
    deckCards.add(CardRegistry.get("musketeer"));
    deckCards.add(CardRegistry.get("archers"));
    deckCards.add(CardRegistry.get("goblins"));
    deckCards.add(CardRegistry.get("valkyrie"));
    deckCards.add(CardRegistry.get("bomber"));
    deckCards.add(CardRegistry.get("minions"));

    bluePlayer = new Player(Team.BLUE, new Deck(deckCards), false);
    redPlayer = new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false);

    match = new Standard1v1Match();
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
  }

  @Test
  void playCard_shouldSpawnTroopAndDeductElixir() {
    // Get initial state
    float initialElixir = bluePlayer.getElixir().getCurrent(); // 5.0f
    int initialEntityCount = engine.getGameState().getEntities().size(); // 6 towers

    // Find which card is at slot 0 and its cost
    Card cardAtSlot0 = bluePlayer.getHand().getCard(0);
    int cost = cardAtSlot0.getCost();

    // Queue action to play card at slot 0
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 10f);
    engine.queueAction(bluePlayer, action);

    // Tick to process deployment, then another to process pending spawns
    engine.tick();
    engine.tick();

    // Verify elixir was spent (account for regen during 2 ticks)
    float expectedElixir = initialElixir - cost + (2 * GameEngine.DELTA_TIME / 2.8f);
    assertThat(bluePlayer.getElixir().getCurrent()).isCloseTo(expectedElixir, within(0.1f));

    // Verify entity was spawned (count depends on card - some spawn multiple)
    assertThat(engine.getGameState().getEntities().size()).isGreaterThan(initialEntityCount);
  }

  @Test
  void playCard_withInsufficientElixir_shouldNotSpawn() {
    // Drain elixir to 1
    bluePlayer.getElixir().spend(4);
    float elixirBefore = bluePlayer.getElixir().getCurrent();
    assertThat(elixirBefore).isEqualTo(1.0f);

    int initialEntityCount = engine.getGameState().getEntities().size();

    // Try to play a card (all cards in deck cost at least 2)
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 10f);
    engine.queueAction(bluePlayer, action);
    engine.tick();
    engine.tick();

    // Elixir should only have increased from regen (not spent)
    float expectedElixir = elixirBefore + (2 * GameEngine.DELTA_TIME / 2.8f);
    assertThat(bluePlayer.getElixir().getCurrent()).isCloseTo(expectedElixir, within(0.01f));

    // No new entities (besides towers)
    assertThat(engine.getGameState().getEntities().size()).isEqualTo(initialEntityCount);
  }

  @Test
  void playCard_onEnemySide_shouldBeRejected() {
    float initialElixir = bluePlayer.getElixir().getCurrent();
    int initialEntityCount = engine.getGameState().getEntities().size();

    // Try to place on enemy side (y=25 is in red zone)
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    engine.queueAction(bluePlayer, action);
    engine.tick();
    engine.tick();

    // Elixir should only have increased from regen (action was rejected by match validation)
    float expectedElixir = initialElixir + (2 * GameEngine.DELTA_TIME / 2.8f);
    assertThat(bluePlayer.getElixir().getCurrent()).isCloseTo(expectedElixir, within(0.01f));

    // No new entities
    assertThat(engine.getGameState().getEntities().size()).isEqualTo(initialEntityCount);
  }

  @Test
  void playMultiUnitCard_shouldSpawnAllUnits() {
    // Make sure we have enough elixir
    assertThat(bluePlayer.getElixir().getCurrent()).isGreaterThanOrEqualTo(5.0f);

    // Find goblins in hand (spawns 3 units)
    int goblinsSlot = -1;
    for (int i = 0; i < 4; i++) {
      if ("Goblins".equals(bluePlayer.getHand().getCard(i).getName())) {
        goblinsSlot = i;
        break;
      }
    }

    // If goblins not in initial hand, skip this test
    if (goblinsSlot == -1) {
      return;
    }

    int initialTroopCount = (int) engine.getGameState().getAliveEntities().stream()
        .filter(e -> e.getEntityType() == EntityType.TROOP)
        .count();

    PlayerActionDTO action = PlayerActionDTO.play(goblinsSlot, 9f, 10f);
    engine.queueAction(bluePlayer, action);
    engine.tick();
    engine.tick(); // Process pending spawns

    int newTroopCount = (int) engine.getGameState().getAliveEntities().stream()
        .filter(e -> e.getEntityType() == EntityType.TROOP)
        .count();

    // Goblins should spawn 3 units
    assertThat(newTroopCount - initialTroopCount).isEqualTo(3);
  }

  @Test
  void elixirRegen_shouldWorkDuringMatch() {
    // Spend some elixir
    bluePlayer.getElixir().spend(3);
    float afterSpend = bluePlayer.getElixir().getCurrent();

    // Run for 3 seconds (should regenerate ~1 elixir at 2.8s rate)
    engine.runSeconds(3.0f);

    assertThat(bluePlayer.getElixir().getCurrent()).isGreaterThan(afterSpend);
  }

  @Test
  void overtime_shouldDoubleElixirRegen() {
    // Get elixir state at 2.0
    bluePlayer.getElixir().spend(3); // Down to 2.0
    float startElixir = bluePlayer.getElixir().getCurrent();

    // Normal regen for 2.8 seconds = 1 elixir
    engine.runSeconds(2.8f);
    float afterNormalRegen = bluePlayer.getElixir().getCurrent();
    float normalRegenAmount = afterNormalRegen - startElixir;

    // Enter overtime
    match.enterOvertime();

    // Reset to same starting point
    bluePlayer.getElixir().spend((int) (bluePlayer.getElixir().getCurrent() - startElixir));

    // Overtime regen for 2.8 seconds should give ~2 elixir
    engine.runSeconds(2.8f);
    float afterOvertimeRegen = bluePlayer.getElixir().getCurrent();
    float overtimeRegenAmount = afterOvertimeRegen - startElixir;

    // Overtime should regenerate roughly double
    assertThat(overtimeRegenAmount).isGreaterThan(normalRegenAmount * 1.5f);
  }

  @Test
  void spawnedTroop_shouldHaveCorrectStats() {
    // Find knight in hand
    int knightSlot = -1;
    for (int i = 0; i < 4; i++) {
      if ("Knight".equals(bluePlayer.getHand().getCard(i).getName())) {
        knightSlot = i;
        break;
      }
    }

    if (knightSlot == -1) {
      return; // Knight not in initial hand
    }

    PlayerActionDTO action = PlayerActionDTO.play(knightSlot, 9f, 10f);
    engine.queueAction(bluePlayer, action);
    engine.tick();
    engine.tick(); // Process pending spawns

    // Find the spawned knight
    Troop knight = engine.getGameState().getAliveEntities().stream()
        .filter(e -> e.getEntityType() == EntityType.TROOP)
        .filter(e -> "Knight".equals(e.getName()))
        .map(e -> (Troop) e)
        .findFirst()
        .orElse(null);

    assertThat(knight).isNotNull();
    assertThat(knight.getTeam()).isEqualTo(Team.BLUE);
    assertThat(knight.getHealth().getMax()).isEqualTo(1452); // Knight's HP from CardRegistry
    assertThat(knight.getCombat().getDamage()).isEqualTo(167);
    // Position may have shifted slightly due to physics, use approximate check
    assertThat(knight.getPosition().getX()).isCloseTo(9f, within(1f));
    assertThat(knight.getPosition().getY()).isCloseTo(10f, within(1f));
  }
}