package org.crforge.core.battle.match;

import java.util.List;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One player of a match, as its king carries it: the elixir, in ten-thousandths, the deck and the
 * hand.
 *
 * <p>Each step the king's visit first refills the hand and then regenerates the elixir: the step is
 * {@code MAX_MANA * 500000 / ElixirFullBarMS}, truncated - 178 at 1x, 357 at 2x, 537 at 3x - held
 * to {@code [0, MAX_MANA * 10000]}; what goes above the cap is counted as wasted. While a play's
 * production stop runs, the elixir waits and the stop loses 50 ms instead. A play is checked
 * against the whole elixir, the truncated quotient by 10000, and takes its cost times 10000, never
 * more than there is.
 *
 * <p>The king keeps the last card played, which a play of any card but the Mirror replaces, and a
 * copy of it its visit makes after the regeneration. The copy is the card a Mirror repeats, so a
 * Mirror after a Mirror repeats the same card again. A variant card's play keeps the variant card.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: the starting elixir, the regeneration, the cap"
            + " and the waste, the production stop, the whole elixir and the spend. Held by"
            + " match_elixir_150s, both elixirs on every tick, and the adds a collector and a"
            + " death make by match_elixir_sources. The last card and its copy are held by"
            + " mirror_knight and mirror_fireball. Not modelled: a boost's scaled rate"
            + " and a paused regeneration, which no Ladder battle has, and the views' counters.")
public final class MatchSide {

  /** The elixir's scale: ten thousand to a whole elixir. */
  public static final int SCALE = 10000;

  private final List<MatchCard> deck;

  @Getter private final Hand hand = new Hand();

  /** The elixir, in ten-thousandths. */
  @Getter private int elixir;

  /** What is left of a play's production stop, in milliseconds. */
  @Getter private int productionStopMs;

  /** The elixir spent so far, in ten-thousandths. */
  @Getter private int spent;

  /** The elixir the cap has turned away so far, in ten-thousandths. */
  @Getter private int wasted;

  /** The last card played that was not the Mirror, or null before any. */
  private MatchCard lastPlayed;

  /** The copy of the last card the visit makes, which a Mirror repeats; null before any. */
  private MatchCard lastPlayedCopy;

  MatchSide(List<MatchCard> deck, int startingElixir) {
    this.deck = List.copyOf(deck);
    this.elixir = startingElixir * SCALE;
  }

  /** The deck, by index. */
  public List<MatchCard> deck() {
    return deck;
  }

  /** The last card played that was not the Mirror, or null before any. */
  public MatchCard lastPlayed() {
    return lastPlayed;
  }

  /** The copy of the last card the king's visit made, which a Mirror repeats; null before any. */
  public MatchCard lastPlayedCopy() {
    return lastPlayedCopy;
  }

  /** The whole elixir a play is checked against: the elixir over 10000, truncated. */
  public int wholeElixir() {
    return elixir / SCALE;
  }

  /**
   * The king's visit: the hand refill, then the regeneration, then the copy of the last card.
   *
   * @param timeline the battle's timeline, whose rate and cooldown apply now
   * @param maxMana the published maximum elixir
   * @return the slot the refill filled, or -1
   */
  int visit(Timeline timeline, int maxMana) {
    int refilled = hand.refill(timeline.getNextCardCooldownMs());
    regenerate(timeline.getFullBarMs(), maxMana);
    lastPlayedCopy = lastPlayed;
    return refilled;
  }

  private void regenerate(int fullBarMs, int maxMana) {
    if (fullBarMs == 0) {
      return;
    }
    if (productionStopMs >= 1) {
      productionStopMs = Math.max(productionStopMs, 50) - 50;
      return;
    }
    int step = maxMana * 500000 / fullBarMs;
    if (step < 1) {
      return;
    }
    add(step, maxMana);
  }

  /**
   * Adds elixir, as the regeneration, a collector and a death do: the total is clamped to {@code
   * [0, MAX_MANA * 10000]}, and what goes above the cap is counted as wasted.
   *
   * @param amount the elixir, in ten-thousandths
   * @param maxMana the published maximum elixir
   */
  void add(int amount, int maxMana) {
    int cap = maxMana * SCALE;
    int over = elixir + amount - cap;
    if (over >= 1) {
      wasted += over;
    }
    int total = elixir + amount;
    elixir = total > 0 ? Math.min(total, cap) : 0;
  }

  /**
   * A play of a card from the hand: its cost taken, its production stop started, the card moved
   * from its slot to the back of the queue, and the card kept as the last played.
   *
   * @param index the card's deck index
   */
  void play(int index) {
    MatchCard card = deck.get(index);
    play(index, card.cost(), card.elixirProductionStopTimeMs());
    lastPlayed = card;
  }

  /**
   * A play of a variant card: the picked option's cost taken and its production stop started, the
   * variant card moved from its slot to the back of the queue and kept as the last played.
   *
   * @param item the variant card's item
   */
  void playVariant(VariantItem item) {
    play(item.index(), item.cost(), item.elixirProductionStopTimeMs());
    lastPlayed = deck.get(item.index());
  }

  /**
   * A play of the Mirror: the item's cost taken, the repeated card's production stop started, and
   * the Mirror moved from its slot to the back of the queue. The last card played stays as it was.
   *
   * @param item the Mirror's item, which repeats a card
   */
  void playMirror(MirrorItem item) {
    play(item.index(), item.cost(), item.repeats().elixirProductionStopTimeMs());
  }

  private void play(int index, int cost, int productionStopTimeMs) {
    int spend = cost > 0 ? Math.min(cost * SCALE, elixir) : 0;
    spent += spend;
    elixir -= spend;
    if (productionStopTimeMs >= 1) {
      productionStopMs = productionStopTimeMs;
    }
    hand.removeFromHand(hand.slotOf(index));
  }
}
