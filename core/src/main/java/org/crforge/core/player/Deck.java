package org.crforge.core.player;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.crforge.core.card.Card;

/** Represents the player's 8-card deck. */
@Getter
public class Deck {

  public static final int SIZE = 8;

  private final List<Card> cards;

  public Deck(List<Card> cards) {
    checkArgument(
        cards.size() == SIZE,
        () -> "Deck must have exactly " + SIZE + " cards. Received: " + cards.size());
    // Defensive copy to prevent external modification of the deck list structure
    this.cards = new ArrayList<>(cards);
  }

  public void shuffle() {
    Collections.shuffle(cards);
  }

  public Card get(int index) {
    return cards.get(index);
  }
}
