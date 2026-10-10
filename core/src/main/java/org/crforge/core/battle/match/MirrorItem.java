/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

/**
 * The card item a Mirror play carries, as the issuing player's client builds it: the card the
 * Mirror repeats, the level it is played at and what it costs.
 *
 * <p>The levels are the item's level field, the level less 1. A Mirror with something to repeat
 * plays it one level above its own, for its own cost plus the card's, never more than the most
 * elixir there can be. With nothing to repeat the item is the Mirror's own: its level and its cost.
 *
 * @param index the Mirror's deck index, which the hand cycle takes
 * @param repeats the card the Mirror repeats: its side's last card, or null for none
 * @param mirrorLevelField the Mirror's own level field
 * @param levelField the item's level field, which the repeated card is played at
 * @param cost the item's cost, which the elixir gate reads and the play spends
 */
public record MirrorItem(
    int index, MatchCard repeats, int mirrorLevelField, int levelField, int cost) {

  /** The level the repeated card is played at, counted from 1. */
  public int level() {
    return levelField + 1;
  }
}
