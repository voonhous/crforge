/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The order a deck is dealt in, from the draw that seeds it. */
class DeckShuffleTest {

  /** A card with neither opening-hand column set. */
  private static MatchCard plain(String name) {
    return new MatchCard(name, 3, false, false, 0, false, null);
  }

  private static List<MatchCard> eightPlain() {
    List<MatchCard> deck = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      deck.add(plain("Card" + i));
    }
    return deck;
  }

  @Test
  @DisplayName(
      "two battle-source draws deal their two orders; the shuffle is held by the opening hands"
          + " every reference battle observes")
  void theReferenceOrders() {
    // Side 0's draw from the battle source's first state, then side 1's from the next.
    assertThat(DeckShuffle.order(eightPlain(), 270369)).containsExactly(5, 2, 1, 7, 6, 3, 0, 4);
    assertThat(DeckShuffle.order(eightPlain(), 67601921)).containsExactly(0, 4, 1, 3, 7, 2, 6, 5);
  }

  @Test
  @DisplayName("a card kept out of the opening hand never is in it, for any seed")
  void anOmittedCardIsNeverOpened() {
    List<MatchCard> deck = eightPlain();
    deck.set(2, new MatchCard("Mirror", 1, false, true, 0, true, null));
    deck.set(6, new MatchCard("ElixirCollector", 6, false, true, 0, false, null));
    for (int seed = 0; seed < 500; seed++) {
      List<Integer> order = DeckShuffle.order(deck, seed * 7919);
      assertThat(order.subList(0, 4)).doesNotContain(2, 6);
      assertThat(order).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
    }
  }

  @Test
  @DisplayName("a card that must start in the hand is queued first")
  void aForcedCardIsFirst() {
    List<MatchCard> deck = eightPlain();
    deck.set(5, new MatchCard("Forced", 3, true, false, 0, false, null));

    assertThat(DeckShuffle.order(deck, 12345).get(0)).isEqualTo(5);
  }
}
