/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

/**
 * The kings' elixir in a match, which the battle's units pay into and its abilities draw on: an
 * elixir collector's payout, the elixir a unit's death gives the side that killed it and the elixir
 * it gives its own side, the cost of a champion's ability and its refund. Each king is found by its
 * side, as the tower slot of that side's player.
 */
public interface KingElixir {

  /** One whole elixir, in the ten-thousandths the kings count in. */
  int SCALE = 10000;

  /**
   * The whole elixir of a side's king, truncated: what a card play is checked against.
   *
   * @param side 0 or 1
   */
  int wholeElixir(int side);

  /**
   * A side's king's elixir, in ten-thousandths.
   *
   * @param side 0 or 1
   */
  int elixir(int side);

  /**
   * Takes a cost from a side's king's elixir, never more than it holds, and counts it as spent.
   *
   * @param side 0 or 1
   * @param amount the cost, in ten-thousandths
   */
  void spend(int side, int amount);

  /**
   * Adds to a side's king's elixir, up to the cap, counting what goes above it as wasted.
   *
   * @param side 0 or 1
   * @param amount the elixir, in ten-thousandths
   */
  void add(int side, int amount);

  /** The most elixir a king holds, in whole elixir: the published MAX_MANA. */
  int maxMana();
}
