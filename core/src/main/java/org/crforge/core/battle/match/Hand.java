package org.crforge.core.battle.match;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A king's hand: four slots of deck indices, the queue of the rest in the order they come, and the
 * cooldown before an empty slot is refilled.
 *
 * <p>A played card leaves its slot for the back of the queue at once. Each step the king's visit
 * counts the cooldown down by 50 ms; once it has run out, the first empty slot takes the queue's
 * front and the cooldown restarts at the timeline's next-card cooldown. So the replacement enters
 * on the step of the play when the cooldown has run out, and a second empty slot waits a whole
 * cooldown more.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: the refill, the cycle and the slot lookup. Held"
            + " by match_elixir_150s, both hands on every tick.")
public final class Hand {

  /** The number of slots. */
  public static final int SLOTS = 4;

  /** A slot holding no card. */
  public static final int EMPTY = -1;

  private final int[] slots = {EMPTY, EMPTY, EMPTY, EMPTY};

  private final List<Integer> queue = new ArrayList<>();

  /** What is left of the cooldown before an empty slot is refilled, in milliseconds. */
  @Getter private int cooldownMs;

  /**
   * Deals the opening hand from the deck order: the first four into the slots, the rest queued.
   *
   * @param order the deck indices in their battle order
   */
  void deal(List<Integer> order) {
    queue.clear();
    queue.addAll(order);
    int count = Math.min(queue.size(), SLOTS);
    for (int k = 0; k < count; k++) {
      slots[k] = queue.remove(0);
    }
  }

  /** The four slots, {@link #EMPTY} for an empty one. */
  public int[] slots() {
    return slots.clone();
  }

  /** The queue, its front the next card. */
  public List<Integer> queue() {
    return Collections.unmodifiableList(queue);
  }

  /**
   * The first slot holding a deck index, or -1 when none does.
   *
   * @param index the deck index
   */
  public int slotOf(int index) {
    for (int k = 0; k < SLOTS; k++) {
      if (slots[k] == index) {
        return k;
      }
    }
    return -1;
  }

  /**
   * Moves the card of a slot to the back of the queue and empties the slot, as a play does. A slot
   * out of range or already empty changes nothing.
   *
   * @param slot the slot
   * @return the card, or {@link #EMPTY} when nothing moved
   */
  int removeFromHand(int slot) {
    if (slot < 0 || slot >= SLOTS || slots[slot] == EMPTY) {
      return EMPTY;
    }
    int card = slots[slot];
    queue.add(card);
    slots[slot] = EMPTY;
    return card;
  }

  /**
   * Counts the cooldown down by one step and, once it has run out, refills the first empty slot
   * from the queue's front and restarts the cooldown.
   *
   * @param nextCardCooldownMs the timeline's next-card cooldown now
   * @return the slot refilled, or -1 when none was
   */
  int refill(int nextCardCooldownMs) {
    int left = cooldownMs - 50;
    cooldownMs = Math.max(left, 0);
    if (left > 0) {
      return -1;
    }
    for (int k = 0; k < SLOTS; k++) {
      if (slots[k] == EMPTY) {
        if (!queue.isEmpty()) {
          slots[k] = queue.remove(0);
        }
        cooldownMs = nextCardCooldownMs;
        return k;
      }
    }
    return -1;
  }

  @Override
  public String toString() {
    return Arrays.toString(slots) + " " + queue + " cd " + cooldownMs;
  }
}
