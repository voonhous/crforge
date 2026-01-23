package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentSystemTest {

  private GameState gameState;
  private DeploymentSystem deploymentSystem;
  private Player player;

  @BeforeEach
  void setUp() {
    gameState = new GameState();
    deploymentSystem = new DeploymentSystem(gameState);

    // Create a deck of 8 dummy cards
    List<Card> cards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(Card.builder()
          .name("Troop " + i)
          .cost(3) // Cost is 3
          .type(CardType.TROOP)
          .build());
    }
    Deck deck = new Deck(cards);

    // Player starts with 5.0 Elixir by default
    player = new Player(Team.BLUE, deck, false);
  }

  @Test
  void testSuccessfulDeployment() {
    // Capture state before action
    Card cardSlot0 = player.getHand().getCard(0);
    float initialElixir = player.getElixir().getCurrent(); // 5.0

    // Create action to play card at slot 0 (Cost 3)
    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(20f)
        .build();

    // Queue and Update
    deploymentSystem.queueAction(player, action);
    deploymentSystem.update();

    // 1. Verify Elixir Spent (5.0 - 3.0 = 2.0)
    assertThat(player.getElixir().getCurrent()).isEqualTo(initialElixir - 3);

    // 2. Verify Hand Cycled (Slot 0 should have a new card)
    assertThat(player.getHand().getCard(0)).isNotEqualTo(cardSlot0);
  }

  @Test
  void testInsufficientElixir() {
    // Drain player elixir to 1.0 (Card costs 3)
    player.getElixir().spend(4);
    assertThat(player.getElixir().getCurrent()).isEqualTo(1.0f);

    Card cardSlot0 = player.getHand().getCard(0);

    // Create action
    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(20f)
        .build();

    // Queue and Update
    deploymentSystem.queueAction(player, action);
    deploymentSystem.update();

    // 1. Verify Elixir Unchanged
    assertThat(player.getElixir().getCurrent()).isEqualTo(1.0f);

    // 2. Verify Hand NOT Cycled
    assertThat(player.getHand().getCard(0)).isEqualTo(cardSlot0);
  }
}