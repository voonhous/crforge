package org.crforge.core.player;

import lombok.Getter;
import org.crforge.core.card.Card;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Represents a player in the game, managing their state, resources, and deck.
 */
@Getter
public class Player {

  private final Team team;
  private final Deck deck;
  private final Hand hand;
  private final Elixir elixir;
  private final boolean isBot; // Simple flag for now, maybe expanded later

  public Player(Team team, Deck deck, boolean isBot) {
    this.team = team;
    this.deck = deck;
    this.isBot = isBot;

    // Initialize systems
    this.hand = new Hand(deck);
    // Start with 5 elixir (Standard Clash Royale start) or configurable
    this.elixir = new Elixir(5.0f);
  }

  public void update(float deltaTime) {
    elixir.update(deltaTime);
  }

  public void setOvertime(boolean overtime) {
    elixir.setOvertime(overtime);
  }

  /**
   * Attempts to execute a player action. Checks if card exists in hand and player has enough
   * elixir. Does NOT check placement validity (that is GameEngine/Arena responsibility). * @param
   * action The action to validate and charge for
   *
   * @return The Card to be spawned if successful, null otherwise.
   */
  public Card tryPlayCard(PlayerActionDTO action) {
    if (!action.isValid()) {
      return null;
    }

    Card cardToPlay = hand.getCard(action.getHandIndex());

    if (cardToPlay == null) {
      return null;
    }

    int cost = cardToPlay.getCost();

    if (elixir.has(cost)) {
      // Spend elixir
      elixir.spend(cost);
      // Cycle card
      hand.playCard(action.getHandIndex());
      return cardToPlay;
    }

    return null;
  }
}
