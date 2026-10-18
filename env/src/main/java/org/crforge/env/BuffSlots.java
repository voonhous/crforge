/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.List;
import java.util.function.ToIntFunction;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.BuffInstance;

/**
 * Which of an entity's buffs its row's {@link Schema#BUFF_SLOTS} slots list, in which order: the
 * buffs that matter most to what the unit does now first, so a fresh stun or slow is never pushed
 * off the slots by buffs a unit carries for its whole life (an evolution's or a hero's own). The
 * percents of the row ({@link Schema#E_SPEED_PERCENT}, {@link Schema#E_HIT_SPEED_PERCENT}) show the
 * buffs' sum whatever the slots list.
 */
final class BuffSlots {

  /** A buff that stops the unit moving or attacking: a stun, a freeze. */
  static final int STOPS = 0;

  /** A buff that slows its movement or its attacks. */
  static final int SLOWS = 1;

  /** Any other buff with a time left. */
  static final int TIMED = 2;

  /** Any other buff that never runs out. */
  static final int FOREVER = 3;

  private BuffSlots() {
    // Static rule
  }

  /**
   * A buff's rank: {@link #STOPS} for one that takes its movement or its attack pace down by 100
   * percent or more, {@link #SLOWS} for one that takes either down by less, else {@link #TIMED} or
   * {@link #FOREVER} by its time left.
   *
   * @param speedMultiplier the buff's movement change, in percent
   * @param hitSpeedMultiplier the buff's attack pace change, in percent
   * @param remainingMs its time left, {@link BuffInstance#FOREVER} for none
   */
  static int rank(int speedMultiplier, int hitSpeedMultiplier, int remainingMs) {
    if (speedMultiplier <= -100 || hitSpeedMultiplier <= -100) {
      return STOPS;
    }
    if (speedMultiplier < 0 || hitSpeedMultiplier < 0) {
      return SLOWS;
    }
    return remainingMs == BuffInstance.FOREVER ? FOREVER : TIMED;
  }

  /**
   * Fills the slots: the indices into {@code items} of the buffs listed, by rank, then oldest first
   * (the order the battle lists them in), at most {@code slots.length}.
   *
   * @param items the buffs, oldest first
   * @param slots where the indices go
   * @return how many slots were filled
   */
  static int fill(List<BuffInstance> items, int[] slots) {
    return fill(items, BuffSlots::rank, slots);
  }

  /**
   * Fills the slots with the indices of items by a rank, the lowest first, then in list order.
   *
   * @param items the items, oldest first
   * @param rank each item's rank, {@link #STOPS} to {@link #FOREVER}
   * @param slots where the indices go
   * @return how many slots were filled
   */
  static <T> int fill(List<T> items, ToIntFunction<T> rank, int[] slots) {
    int filled = 0;
    for (int r = STOPS; r <= FOREVER && filled < slots.length; r++) {
      for (int i = 0; i < items.size() && filled < slots.length; i++) {
        if (rank.applyAsInt(items.get(i)) == r) {
          slots[filled++] = i;
        }
      }
    }
    return filled;
  }

  private static int rank(BuffInstance item) {
    BuffData buff = item.getBuff();
    return rank(buff.speedMultiplier(), buff.hitSpeedMultiplier(), item.getRemaining());
  }
}
