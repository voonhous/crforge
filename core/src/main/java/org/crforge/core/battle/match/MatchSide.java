/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.crforge.core.util.ValidationUtils.checkArgument;

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
 *
 * <p>Each deck card comes with its slot flags: bit 0 for the deck's evolution slot, bit 1 for its
 * hero slot. The king keeps one count per deck index, 0 at the battle's start. A play of an
 * evolution slot's card resets its count after an evolved or hero play, and otherwise adds one
 * while the count is below its evolved row's DarkElixirCost. A play's item is evolved once the
 * count has reached that cost, so a Knight whose evolved row costs 2 is played plain twice, evolved
 * on its third play and plain again on its fourth. A hero slot's card is played in its hero form
 * whenever it is not evolved. The play spends the cost of the row it is cast as and starts that
 * row's production stop; the last card kept is the deck card, and the field it was played with
 * decides what a Mirror repeats.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: the starting elixir, the regeneration, the cap"
            + " and the waste, the production stop, the whole elixir and the spend. Held by the"
            + " elixir every reference battle observes on both sides every tick, among them"
            + " timeline_spells_through_rates, and the adds a collector and a death make by"
            + " cg_elixir_collector_played and card_ElixirGolem. The last card and its copy are"
            + " held by golden-gaps-v1/mirror_after_troop and mirror_after_spell. The slot flags,"
            + " the counts and each play's item are held by knight_evolved_third_play and the"
            + " hero reference battles (hero_giant, deck_hero_and_champion); a Mirror of an"
            + " evolved play is not held by a recorded battle. Not modelled: a boost's scaled"
            + " rate and a paused regeneration, which no Ladder battle has, and the views'"
            + " counters.")
public final class MatchSide {

  /** The elixir's scale: ten thousand to a whole elixir. */
  public static final int SCALE = 10000;

  /** The slot flag of a card in the deck's evolution slot. */
  public static final int EVOLUTION_SLOT = 1;

  /** The slot flag of a card in the deck's hero slot. */
  public static final int HERO_SLOT = 2;

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

  /**
   * How many cards the king has played: each play's units carry the count before it, which a
   * champion's slot follows.
   */
  @Getter private int deployCounter;

  /** The last card played that was not the Mirror, or null before any. */
  private MatchCard lastPlayed;

  /** The evolution field the last card was played with. */
  private int lastPlayedField;

  /** The option the last card was played as, for a variant card; -1 for any other card. */
  private int lastPlayedOption = -1;

  /** The copy of the last card the visit makes, which a Mirror repeats; null before any. */
  private MatchCard lastPlayedCopy;

  /** The evolution field of the copy of the last card. */
  private int lastPlayedCopyField;

  /** The option of the copy of the last card; -1 for none. */
  private int lastPlayedCopyOption = -1;

  /** Each deck card's slot flags, by deck index. */
  private final int[] slotFlags;

  /** The king's count per deck index, which an evolution slot's plays move. */
  private final int[] evolutionCounts;

  MatchSide(List<MatchCard> deck, int startingElixir) {
    this(deck, startingElixir, new int[deck.size()]);
  }

  /**
   * @param deck the deck, by index
   * @param startingElixir the elixir the king starts with
   * @param slotFlags each deck card's slot flags, by deck index
   */
  MatchSide(List<MatchCard> deck, int startingElixir, int[] slotFlags) {
    checkArgument(
        slotFlags.length == deck.size(), () -> "a deck's slot flags are one for each card");
    this.deck = List.copyOf(deck);
    this.elixir = startingElixir * SCALE;
    this.slotFlags = slotFlags.clone();
    this.evolutionCounts = new int[deck.size()];
  }

  /**
   * A deck card's slot flags: {@link #EVOLUTION_SLOT}, {@link #HERO_SLOT}, both or neither.
   *
   * @param index the card's deck index
   */
  public int slotFlags(int index) {
    return slotFlags[index];
  }

  /**
   * The king's count for a deck index: the plays of an evolution slot's card since its last evolved
   * play, up to its evolved row's DarkElixirCost.
   *
   * @param index the card's deck index
   */
  public int evolutionCount(int index) {
    return evolutionCounts[index];
  }

  /**
   * The item a play of a deck card carries: evolved when the card's evolved row has a
   * DarkElixirCost of at least 1 and the count has reached it, else the hero form for a hero slot's
   * card, else neither; the row it is cast as, the card's first in that form, and that row's cost.
   *
   * @param index the card's deck index
   */
  public EvolutionItem item(int index) {
    MatchCard card = deck.get(index);
    int field;
    if (evolved(index)) {
      field = EvolutionItem.EVOLVED;
    } else {
      field = (slotFlags[index] & HERO_SLOT) != 0 ? EvolutionItem.HERO : 0;
    }
    MatchCard spell = card.formRow(field);
    return new EvolutionItem(index, field, spell, spell.cost(), evolutionCounts[index]);
  }

  /**
   * Whether a play of the card at a deck index is evolved: the card has an evolved row whose
   * DarkElixirCost is at least 1, and the count has reached it. The slot flags are not asked: only
   * an evolution slot's card counts.
   */
  private boolean evolved(int index) {
    MatchCard card = deck.get(index);
    MatchCard evolved = card.formRow(MatchCard.EVO_FORM);
    if (evolved == card) {
      return false;
    }
    int need = evolved.darkElixirCost();
    return need > 0 && evolutionCounts[index] >= need;
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

  /** The field the copy of the last card was played with, which decides what a Mirror repeats. */
  public int lastPlayedCopyField() {
    return lastPlayedCopyField;
  }

  /**
   * The option the copy of the last card was played as, for a variant card, which a Mirror repeats
   * it as; -1 for any other card.
   */
  public int lastPlayedCopyOption() {
    return lastPlayedCopyOption;
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
    lastPlayedCopyField = lastPlayedField;
    lastPlayedCopyOption = lastPlayedOption;
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
   * A play of a card from the hand, with the item it carries now.
   *
   * @param index the card's deck index
   * @see #play(EvolutionItem)
   */
  void play(int index) {
    play(item(index));
  }

  /**
   * A play of a card from the hand: the cost of the row it is cast as taken, that row's production
   * stop started, the card moved from its slot to the back of the queue, the count of an evolution
   * slot's card moved, and the card kept as the last played, with the item's field.
   *
   * @param item the item the play carries
   */
  void play(EvolutionItem item) {
    int index = item.index();
    play(index, item.cost(), item.spell().elixirProductionStopTimeMs());
    if ((slotFlags[index] & EVOLUTION_SLOT) != 0) {
      if (item.field() != 0) {
        evolutionCounts[index] = 0;
      } else if (evolutionCounts[index]
          < deck.get(index).formRow(MatchCard.EVO_FORM).darkElixirCost()) {
        evolutionCounts[index]++;
      }
    }
    lastPlayed = deck.get(index);
    lastPlayedField = item.field();
    lastPlayedOption = -1;
  }

  /**
   * A play of a variant card: the picked option's cost taken and its production stop started, the
   * variant card moved from its slot to the back of the queue and kept as the last played, with the
   * option it was played as.
   *
   * @param item the variant card's item
   */
  void playVariant(VariantItem item) {
    play(item.index(), item.cost(), item.elixirProductionStopTimeMs());
    lastPlayed = deck.get(item.index());
    lastPlayedField = 0;
    lastPlayedOption = item.option();
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
    deployCounter++;
  }

  /**
   * Takes a cost from the elixir, never more than there is, and counts it as spent: a champion's
   * ability paid for.
   *
   * @param amount the cost, in ten-thousandths
   */
  void spend(int amount) {
    int spend = Math.min(amount, elixir);
    spent += spend;
    elixir -= spend;
  }
}
