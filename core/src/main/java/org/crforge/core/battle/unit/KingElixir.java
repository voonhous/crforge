package org.crforge.core.battle.unit;

/**
 * The kings' elixir in a match, which the battle's units pay into: an elixir collector's payout and
 * the elixir a unit's death gives the side that killed it. Each king is found by its side, as the
 * tower slot of that side's player.
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
   * Adds to a side's king's elixir, up to the cap, counting what goes above it as wasted.
   *
   * @param side 0 or 1
   * @param amount the elixir, in ten-thousandths
   */
  void add(int side, int amount);

  /** The most elixir a king holds, in whole elixir: the published MAX_MANA. */
  int maxMana();
}
