/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A hand's rearranging, as a search's fork deals again what a player cannot see. */
class HandTest {

  private static Hand dealt() {
    Hand hand = new Hand();
    hand.deal(List.of(0, 1, 2, 3, 4, 5, 6, 7));
    return hand;
  }

  @Test
  void aRearrangedHandHoldsTheSameCardsInTheGivenPlaces() {
    Hand hand = dealt();
    // Slot 1 and the queue's front and back exchange cards.
    hand.rearrange(new int[] {1, 4, 7}, new int[] {7, 1, 4});
    assertThat(hand.slots()).containsExactly(0, 7, 2, 3);
    assertThat(hand.queue()).containsExactly(1, 5, 6, 4);
  }

  @Test
  void aRearrangementOfOtherCardsOrPlacesIsRefused() {
    Hand hand = dealt();
    assertThatThrownBy(() -> hand.rearrange(new int[] {0, 1}, new int[] {0, 5}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hand.rearrange(new int[] {8}, new int[] {0}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(hand.slots()).containsExactly(0, 1, 2, 3);
  }
}
