package org.crforge.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandTest {

  private Deck deck;
  private Hand hand;
  private List<Card> createdCards;

  @BeforeEach
  void setUp() {
    // Create 8 distinct cards
    createdCards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      createdCards.add(Card.builder()
          .name("Card " + i)
          .type(CardType.TROOP)
          .cost(i + 1)
          .build());
    }
    deck = new Deck(createdCards);
    hand = new Hand(deck);
  }

  @Test
  void testInitialization() {
    // Verify 4 slots are filled
    for (int i = 0; i < 4; i++) {
      assertThat(hand.getCard(i)).isNotNull();
    }
    // Verify next card is filled
    assertThat(hand.getNextCard()).isNotNull();

    // Verify all 5 visible cards (hand + next) are unique
    Set<Card> visible = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      visible.add(hand.getCard(i));
    }
    visible.add(hand.getNextCard());

    assertThat(visible).hasSize(5);
  }

  @Test
  void testPlayCardCycleLogic() {
    // Capture initial state
    Card cardAtSlot0 = hand.getCard(0);
    Card initialNextCard = hand.getNextCard();

    // PLAY CARD 1 (from slot 0)
    Card played = hand.playCard(0);

    // Assertions after play
    assertThat(played).isEqualTo(cardAtSlot0);
    assertThat(hand.getCard(0)).isEqualTo(initialNextCard)
        .as("Slot 0 should be filled by the previous Next card");
    assertThat(hand.getNextCard()).isNotEqualTo(initialNextCard)
        .as("Next card should be refreshed");
  }

  @Test
  void testFullRotation() {
    // We know the cycle works like this:
    // Visible: 4 in Hand, 1 Next. Hidden: 3 in Queue. Total 8.
    // When we play a card, it goes to the back of the queue.
    // Queue size is 3.
    // So we need to play (3 cards in queue) + (1 next card currently visible) = 4 plays
    // for the first played card to reappear as 'Next'.

    Card firstPlayed = hand.getCard(0);
    hand.playCard(0); // Play 1. Queue now has [..., firstPlayed] at end.

    // Play 3 more times to burn through the queue
    hand.playCard(0);
    hand.playCard(0);
    hand.playCard(0);

    // Now, 'firstPlayed' should have moved from back of queue to 'Next'
    assertThat(hand.getNextCard()).isEqualTo(firstPlayed)
        .as("The first played card should appear as Next Card after 4 plays");
  }

  @Test
  void testPlayInvalidSlot() {
    assertThat(hand.playCard(-1)).isNull();
    assertThat(hand.playCard(4)).isNull();
    assertThat(hand.playCard(99)).isNull();
  }
}
