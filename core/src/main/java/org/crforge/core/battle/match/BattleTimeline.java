/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import java.util.List;

/**
 * A battle timeline row as the match reads it: the sections a battle runs through, and, over the
 * battle time, the elixir rate and the cooldown before a played card's slot is refilled.
 *
 * <p>Lengths are whole seconds, as the row gives them; times are milliseconds.
 *
 * @param name the row's name
 * @param startingElixir the elixir each player starts with, whole
 * @param sectionLengths each section's length in seconds; 0 or less for an open-ended one
 * @param sectionTypes each section's type: {@link #NORMAL}, {@link #OVERTIME} or {@link
 *     #BONUS_TIME}
 * @param sectionFlags each section's flags; a row that leaves them out has 0
 * @param rateLengths each elixir rate's length in seconds
 * @param fullBarMs each elixir rate's time to fill one bar, in milliseconds
 * @param rateVisible each elixir rate's shown rate, which only the view reads
 * @param rateNotify whether each elixir rate's start is told to the view
 * @param cooldownLengths each next-card cooldown's length in seconds
 * @param cooldownMs each next-card cooldown, in milliseconds
 * @param eventTimes the times of the row's events, in seconds
 */
public record BattleTimeline(
    String name,
    int startingElixir,
    List<Integer> sectionLengths,
    List<Integer> sectionTypes,
    List<Integer> sectionFlags,
    List<Integer> rateLengths,
    List<Integer> fullBarMs,
    List<Integer> rateVisible,
    List<Boolean> rateNotify,
    List<Integer> cooldownLengths,
    List<Integer> cooldownMs,
    List<Integer> eventTimes) {

  /** An ordinary section. */
  public static final int NORMAL = 0;

  /** Overtime: entered only while the crowns are equal, and over at the first crown. */
  public static final int OVERTIME = 1;

  /** Bonus time: entered only when something asks for it. */
  public static final int BONUS_TIME = 2;

  public BattleTimeline {
    sectionLengths = List.copyOf(sectionLengths);
    sectionTypes = List.copyOf(sectionTypes);
    sectionFlags = List.copyOf(sectionFlags);
    rateLengths = List.copyOf(rateLengths);
    fullBarMs = List.copyOf(fullBarMs);
    rateVisible = List.copyOf(rateVisible);
    rateNotify = List.copyOf(rateNotify);
    cooldownLengths = List.copyOf(cooldownLengths);
    cooldownMs = List.copyOf(cooldownMs);
    eventTimes = List.copyOf(eventTimes);
  }

  /** The flags of a section; a row that leaves the flags out has 0. */
  int sectionFlag(int section) {
    return section < sectionFlags.size() ? sectionFlags.get(section) : 0;
  }
}
