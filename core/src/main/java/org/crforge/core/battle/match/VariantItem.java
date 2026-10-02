package org.crforge.core.battle.match;

/**
 * The card item a variant card's play carries, as the issuing player's client builds it: the option
 * it was picked as, that option's cost, and the card's own deck index.
 *
 * @param index the variant card's deck index, which the hand cycle takes
 * @param option the index of the option picked
 * @param spell the option's card row, which the play is placed and cast as
 * @param cost the option's cost, which the elixir gate reads and the play spends
 * @param elixirProductionStopTimeMs the option's production stop, which the play starts; 0 for none
 */
public record VariantItem(
    int index, int option, String spell, int cost, int elixirProductionStopTimeMs) {}
