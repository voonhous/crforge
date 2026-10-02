package org.crforge.core.battle.match;

/**
 * What the match reads of a card in a player's deck.
 *
 * @param name the card row's name
 * @param cost its elixir cost
 * @param forceToStartingHand true for a card that must be in the opening hand
 * @param omitFromStartingHand true for a card kept out of the opening hand while four others remain
 * @param elixirProductionStopTimeMs how long a play of it stops the player's elixir; 0 for none
 * @param mirror true for the Mirror, which plays the last card again
 * @param variant the options a variant card is played as, or null for any other card
 */
public record MatchCard(
    String name,
    int cost,
    boolean forceToStartingHand,
    boolean omitFromStartingHand,
    int elixirProductionStopTimeMs,
    boolean mirror,
    SpellVariant variant)
    implements DeckShuffle.Card {}
