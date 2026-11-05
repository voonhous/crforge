/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

/**
 * The card item a Mirror play carries, as the issuing player's client builds it: the card the
 * Mirror repeats, the level it is played at and what it costs, and the Mirror's own evolution field
 * and count.
 *
 * <p>The levels are the item's level field, the level less 1. A Mirror with something to repeat
 * plays it one level above its own, for its own cost plus the card's, never more than the most
 * elixir there can be. With nothing to repeat the item is the Mirror's own: its level and its cost.
 *
 * <p>A Mirror in its deck's evolution slot carries the evolution field and the count its side's
 * count gives it, as any evolution slot card's play does. An evolved Mirror repeats the last card
 * in its evolved form when the card has one, at the level the global MIRROR_EVO_LEVEL_OFFSET gives,
 * and otherwise as a Mirror that is not evolved repeats it.
 *
 * @param index the Mirror's deck index, which the hand cycle takes
 * @param repeats the card the Mirror repeats: its side's last card, or null for none
 * @param mirrorLevelField the Mirror's own level field
 * @param levelField the item's level field, which the repeated card is played at
 * @param cost the item's cost, which the elixir gate reads and the play spends
 * @param field the Mirror's own evolution field: 1 for an evolved Mirror, else 0
 * @param count the side's count for the Mirror's deck index the item was built from
 */
public record MirrorItem(
    int index,
    MatchCard repeats,
    int mirrorLevelField,
    int levelField,
    int cost,
    int field,
    int count) {

  /** The level the repeated card is played at, counted from 1. */
  public int level() {
    return levelField + 1;
  }
}
