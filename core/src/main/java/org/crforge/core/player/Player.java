package org.crforge.core.player;

import java.util.Random;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.player.dto.PlayerActionDTO;

/** Represents a player in the game, managing their state, resources, and deck. */
@Getter
public class Player {

  private final Team team;
  private final Deck deck;
  private final Hand hand;
  private final Elixir elixir;
  private final boolean isBot; // Simple flag for now, maybe expanded later
  private final LevelConfig levelConfig;

  /** Last non-mirror card played by this player. Used by Mirror to determine what to replay. */
  @Setter private Card lastPlayedCard;

  /**
   * Set when Mirror resolves, consumed by DeploymentSystem to override card level. -1 means no
   * mirror level pending.
   */
  @Getter private int pendingMirrorLevel = -1;

  public Player(Team team, Deck deck, boolean isBot) {
    this(team, deck, isBot, LevelConfig.standard());
  }

  public Player(Team team, Deck deck, boolean isBot, LevelConfig levelConfig) {
    this(team, deck, isBot, levelConfig, new Random());
  }

  public Player(Team team, Deck deck, boolean isBot, LevelConfig levelConfig, Random random) {
    this.team = team;
    this.deck = deck;
    this.isBot = isBot;
    this.levelConfig = levelConfig;

    // Initialize systems with seeded RNG for deterministic deck shuffling
    this.hand = new Hand(deck, random);
    // Start with 5 elixir (Standard Clash Royale start) or configurable
    this.elixir = new Elixir(5.0f);
  }

  public void update(float deltaTime) {
    elixir.update(deltaTime);
  }

  public void setElixirMultiplier(int multiplier) {
    elixir.setRegenMultiplier(multiplier);
  }

  /**
   * Attempts to execute a player action. Checks if card exists in hand and player has enough
   * elixir. Does NOT check placement validity (that is GameEngine/Arena responsibility).
   *
   * <p>For Mirror cards: replays the last non-mirror card at +1 elixir cost and +1 level. Mirror
   * itself is cycled from the hand, but the returned card is the mirrored card.
   *
   * @param action The action to validate and charge for
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

    if (cardToPlay.isMirror()) {
      // Mirror: replay last card at +1 cost, +1 level
      if (lastPlayedCard == null || lastPlayedCard.isMirror()) {
        return null;
      }
      int mirrorCost = Math.min(lastPlayedCard.getCost() + 1, 10);
      if (!elixir.has(mirrorCost)) {
        return null;
      }
      elixir.spend(mirrorCost);
      hand.playCard(action.getHandIndex());
      int mirrorLevel = levelConfig.getLevelFor(cardToPlay); // Mirror's own level
      pendingMirrorLevel = Math.min(mirrorLevel + 1, LevelScaling.MAX_CARD_LEVEL);
      // Do NOT update lastPlayedCard -- Mirror doesn't count
      return lastPlayedCard;
    }

    // Normal card path
    int cost = cardToPlay.getCost();
    if (!elixir.has(cost)) {
      return null;
    }
    elixir.spend(cost);
    hand.playCard(action.getHandIndex());
    lastPlayedCard = cardToPlay;
    pendingMirrorLevel = -1;
    return cardToPlay;
  }

  /** Clears the pending mirror level after DeploymentSystem has consumed it. */
  public void clearPendingMirrorLevel() {
    pendingMirrorLevel = -1;
  }
}
