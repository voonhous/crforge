package org.crforge.core.battle.match;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The order a deck is dealt in at the start of a battle.
 *
 * <p>The deck's indices are walked from the end: a card that must start in the hand is queued at
 * once, and one that may not is held back. A generator seeded with one draw of the battle's random
 * source plus a word of the player's then draws cards at random from the rest until four are
 * queued; the held cards come back, the last held first; and the rest are drawn to the end. The
 * opening hand is the first four of that order.
 *
 * <p>The generator is a variant of the MT19937 Mersenne Twister: its seeding adds the multiplier
 * itself where the reference adds the word's index, and its twist and tempering shift right
 * arithmetically. A draw is the tempered word, kept to 31 bits, modulo the bound.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: the generator, the walk from the end, the four"
            + " draws before the held cards return and the rest. Held by match_elixir_150s, both"
            + " opening hands and queues. Not modelled: a game mode's forced card, same decks on"
            + " both sides and a fixed deck order, which Ladder does not set.")
public final class DeckShuffle {

  private static final int WORDS = 624;

  /** Cards drawn before the held-back ones return. */
  private static final int FIRST_DRAWS = 4;

  private final int[] words = new int[WORDS];
  private int index;

  /** What the shuffle needs to know of one card of the deck. */
  public interface Card {

    /** True for a card that must be in the opening hand. */
    boolean forceToStartingHand();

    /** True for a card that may not be in the opening hand while four others remain. */
    boolean omitFromStartingHand();
  }

  private DeckShuffle(int seed) {
    words[0] = seed;
    for (int i = 1; i < WORDS; i++) {
      int previous = words[i - 1];
      int x = previous ^ (previous >> 30);
      words[i] = x * 0x6c078965 + 0x6c078965;
    }
  }

  /** The next draw below a bound; a bound below 1 answers 0 without stepping. */
  private int next(int bound) {
    if (bound < 1) {
      return 0;
    }
    if (index == 0) {
      for (int i = 0; i < WORDS; i++) {
        int next = words[i == WORDS - 1 ? 0 : i + 1];
        int far = words[i < 227 ? i + 397 : i - 227];
        int y = (words[i] & 0x80000000) | (next & 0x7ffffffe);
        y = far ^ (y >> 1);
        words[i] = (next & 1) != 0 ? y ^ 0x9908b0df : y;
      }
    }
    int y = words[index];
    y ^= y >> 11;
    y ^= (y << 7) & 0x9d2c5680;
    y ^= (y << 15) & 0xefc60000;
    y ^= y >> 18;
    y &= 0x7fffffff;
    index = (index + 1) % WORDS;
    return y % bound;
  }

  /**
   * The deck's battle order.
   *
   * @param deck the deck's cards by index; a null entry is an empty index
   * @param seed the battle's draw plus the player's word
   * @return the deck indices in the order they are dealt
   */
  public static List<Integer> order(List<? extends Card> deck, int seed) {
    List<Integer> rest = new ArrayList<>();
    for (int i = 0; i < deck.size(); i++) {
      if (deck.get(i) != null) {
        rest.add(i);
      }
    }
    List<Integer> order = new ArrayList<>();
    List<Integer> held = new ArrayList<>();
    for (int k = rest.size() - 1; k >= 0; k--) {
      Card card = deck.get(rest.get(k));
      if (card.forceToStartingHand()) {
        order.add(rest.remove(k));
      } else if (card.omitFromStartingHand()) {
        held.add(rest.remove(k));
      }
    }
    DeckShuffle generator = new DeckShuffle(seed);
    while (!rest.isEmpty() && order.size() < FIRST_DRAWS) {
      order.add(rest.remove(generator.next(rest.size())));
    }
    for (int k = held.size() - 1; k >= 0; k--) {
      rest.add(held.remove(k));
    }
    while (!rest.isEmpty()) {
      order.add(rest.remove(generator.next(rest.size())));
    }
    return order;
  }
}
