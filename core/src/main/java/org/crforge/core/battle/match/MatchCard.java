/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import java.util.List;

/**
 * What the match reads of a card in a player's deck.
 *
 * <p>A card row may list the rows it is played as in another form: its evolved row and its hero
 * row. Each row has a form - its card form column, else the evolved form for a row of the evolved
 * cards, the hero form for a row of the hero forms, else the basic form - and the row a card is
 * played as in a form is the first of its list in that form, else the card itself.
 *
 * @param name the card row's name
 * @param cost its elixir cost
 * @param forceToStartingHand true for a card that must be in the opening hand
 * @param omitFromStartingHand true for a card kept out of the opening hand while four others remain
 * @param elixirProductionStopTimeMs how long a play of it stops the player's elixir; 0 for none
 * @param mirror true for the Mirror, which plays the last card again
 * @param variant the options a variant card is played as, or null for any other card
 * @param darkElixirCost the plays of a card its evolved row waits for, 0 for none
 * @param form the row's form: {@link #BASIC_FORM}, {@link #EVO_FORM} or {@link #HERO_FORM}
 * @param evolvedSpells the rows the card is played as in other forms, in the row's order
 */
public record MatchCard(
    String name,
    int cost,
    boolean forceToStartingHand,
    boolean omitFromStartingHand,
    int elixirProductionStopTimeMs,
    boolean mirror,
    SpellVariant variant,
    int darkElixirCost,
    int form,
    List<MatchCard> evolvedSpells)
    implements DeckShuffle.Card {

  /** The basic form: the card as it is. */
  public static final int BASIC_FORM = 0;

  /** The evolved form, which an evolution slot's card is played as once its count is reached. */
  public static final int EVO_FORM = 1;

  /** The hero form, which a hero slot's card is played as. */
  public static final int HERO_FORM = 2;

  public MatchCard {
    evolvedSpells = List.copyOf(evolvedSpells);
  }

  /** A card in the basic form that lists no other form. */
  public MatchCard(
      String name,
      int cost,
      boolean forceToStartingHand,
      boolean omitFromStartingHand,
      int elixirProductionStopTimeMs,
      boolean mirror,
      SpellVariant variant) {
    this(
        name,
        cost,
        forceToStartingHand,
        omitFromStartingHand,
        elixirProductionStopTimeMs,
        mirror,
        variant,
        0,
        BASIC_FORM,
        List.of());
  }

  /**
   * The row the card is played as in a form: the first row of its list in that form, else the card
   * itself, which it is for the basic form too.
   *
   * @param kind the form
   */
  public MatchCard formRow(int kind) {
    if (kind == BASIC_FORM) {
      return this;
    }
    for (MatchCard row : evolvedSpells) {
      if (row.form() == kind) {
        return row;
      }
    }
    return this;
  }
}
