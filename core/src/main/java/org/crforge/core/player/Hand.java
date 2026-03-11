package org.crforge.core.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import lombok.Getter;
import org.crforge.core.card.Card;

/** Manages the 4 active cards and the cycle (queue). */
public class Hand {

  public static final int HAND_SIZE = 4;

  @Getter
  // The 4 play slots (some may be null temporarily if we wanted animations, but logic is instant)
  private final Card[] cards;

  // The waiting queue (draw pile)
  private final Queue<Card> cycle;

  @Getter private Card nextCard; // The "Next" card shown in UI

  public Hand(Deck deck) {
    this(deck, new Random());
  }

  public Hand(Deck deck, Random random) {
    this.cards = new Card[HAND_SIZE];

    // Initialize cycle with shuffled deck
    List<Card> deckCards = new ArrayList<>(deck.getCards());
    Collections.shuffle(deckCards, random);
    this.cycle = new LinkedList<>(deckCards);

    // Deal initial hand
    for (int i = 0; i < HAND_SIZE; i++) {
      cards[i] = cycle.poll();
    }
    // Set initial "Next" card
    nextCard = cycle.poll();
  }

  public Card getCard(int slotIndex) {
    if (slotIndex < 0 || slotIndex >= HAND_SIZE) {
      return null;
    }
    return cards[slotIndex];
  }

  /**
   * Plays a card from the specified slot and cycles the deck.
   *
   * <ol>
   *   <li>Returns the card at slotIndex.
   *   <li>Puts that card at the back of the cycle.
   *   <li>Moves 'Next' card to slotIndex.
   *   <li>Draws new 'Next' card.
   * </ol>
   *
   * @param slotIndex 0-3
   * @return The card played, or null if invalid/empty.
   */
  public Card playCard(int slotIndex) {
    if (slotIndex < 0 || slotIndex >= HAND_SIZE) {
      return null;
    }

    Card played = cards[slotIndex];
    if (played == null) {
      return null;
    }

    // 1. Put played card at bottom of cycle
    cycle.offer(played);

    // 2. Move "Next" card to the empty slot
    cards[slotIndex] = nextCard;

    // 3. Draw new "Next" card from top of cycle
    nextCard = cycle.poll();

    return played;
  }
}
