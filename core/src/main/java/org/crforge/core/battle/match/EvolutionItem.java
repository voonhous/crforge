package org.crforge.core.battle.match;

/**
 * The card item a play of a deck card carries, as the issuing player's client builds it: the card's
 * deck index, its evolution field, the row the play is cast as and what it costs.
 *
 * <p>The field is 1 - evolved - when the card has an evolved row whose DarkElixirCost is at least 1
 * and its side's count for the deck index has reached it; else 2 - the hero form - when the card is
 * in its deck's hero slot; else 0. The row cast is the card's first row in that form, else the card
 * itself, and the item costs that row's cost.
 *
 * @param index the card's deck index, which the hand cycle takes
 * @param field the evolution field: 0, 1 for evolved, 2 for the hero form
 * @param spell the row the play is placed and cast as
 * @param cost the row's cost, which the elixir gate reads and the play spends
 * @param count the side's count for the deck index the item was built from
 */
public record EvolutionItem(int index, int field, MatchCard spell, int cost, int count) {

  /** The field of an evolved play. */
  public static final int EVOLVED = 1;

  /** The field of a hero form's play. */
  public static final int HERO = 2;
}
