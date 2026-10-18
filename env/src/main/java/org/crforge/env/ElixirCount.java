/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;

/**
 * One side's elixir as the other side's player counts it, from what that player sees: the match's
 * starting elixir, then each tick that steps the entities the regeneration the clock's elixir rate
 * gives (the same for both sides, held to the maximum; the king regenerates in its step of the
 * entity tick, so the tick that stops the battle after the end and the tiebreaker's ticks that run
 * no update give none), less the cost of each of the side's plays placed and of each of its
 * champion abilities that ran, plus each gain the game shows the other player as it is paid (see
 * {@link Gains}), a played row's production stop holding the regeneration as the king's own count
 * does ({@link MatchSide}). It reads nothing of the side's hidden state. The one gain the game does
 * not show, a champion ability's refund, is left out, so the count drifts from the truth there
 * alone.
 */
final class ElixirCount {

  /** The elixir counted, in ten-thousandths. */
  private int elixir;

  /** The production stop left, in milliseconds. */
  private int stopMs;

  /** Starts the count at the match's starting elixir, in ten-thousandths. */
  void start(int elixir) {
    this.elixir = elixir;
    stopMs = 0;
  }

  /** The elixir counted, in ten-thousandths. */
  int elixir() {
    return elixir;
  }

  /**
   * One tick's regeneration at the clock's rate, for a tick that stepped the entities: none while a
   * production stop runs, which loses the tick instead.
   *
   * @param fullBarMs the time the current rate fills one bar in
   * @param maxElixir the most elixir a side holds, whole
   */
  void regenerate(int fullBarMs, int maxElixir) {
    if (fullBarMs == 0) {
      return;
    }
    if (stopMs >= 1) {
      stopMs = Math.max(stopMs, 50) - 50;
      return;
    }
    int step = maxElixir * 500000 / fullBarMs;
    if (step >= 1) {
      elixir = Math.min(elixir + step, maxElixir * MatchSide.SCALE);
    }
  }

  /**
   * A play placed: its cost taken, never more than is counted, and its row's production stop
   * started.
   *
   * @param cost the cost of the row the play was cast as, whole
   * @param productionStopMs the row's production stop, 0 for none
   */
  void play(int cost, int productionStopMs) {
    spend(cost > 0 ? cost * MatchSide.SCALE : 0);
    if (productionStopMs >= 1) {
      stopMs = productionStopMs;
    }
  }

  /**
   * Gains the game showed the other player, added up to the maximum as the king adds them.
   *
   * @param amount the elixir gained, in ten-thousandths
   * @param maxElixir the most elixir a side holds, whole
   */
  void gain(int amount, int maxElixir) {
    if (amount >= 1) {
      elixir = Math.min(elixir + amount, maxElixir * MatchSide.SCALE);
    }
  }

  /**
   * A cost taken, never more than is counted: a champion's ability that ran.
   *
   * @param amount the cost, in ten-thousandths
   */
  void spend(int amount) {
    elixir -= Math.min(amount, elixir);
  }

  /**
   * The elixir gains of a tick the game shows both players, by the side that gains, as the battle
   * pays them: each pops an elixir float text with its amount at the paying unit on every player's
   * screen, the opponent's included. They are an elixir collector's production (the moment it pays;
   * a production held while its side is at the maximum pays nothing and shows nothing), the elixir
   * a unit's death pays its own side (a row's {@code ManaOnDeath}, an elixir collector's) and the
   * elixir a unit's death pays the side that killed it (a row's {@code ManaOnDeathForOpponent}, the
   * Elixir Golem's pieces). A champion ability's refund shows nothing and is not listed. It reads
   * no side's elixir.
   */
  static final class Gains implements WorldObserver {

    /** Per side: the elixir shown gained since the last {@link #take}, in ten-thousandths. */
    private final int[] shown = new int[2];

    @Override
    public void elixirCollected(int tick, WorldEntity collector, int side, int amount) {
      shown[side & 1] += amount * MatchSide.SCALE;
    }

    @Override
    public void deathManaPaid(int tick, WorldEntity dying, int side, int amount) {
      shown[side & 1] += amount;
    }

    @Override
    public void deathElixirPaid(int tick, WorldEntity dying, int side, int amount) {
      shown[side & 1] += amount;
    }

    @Override
    public void championRefunded(
        int tick, ChampionController slot, int mana, int elixirBefore, int elixirAfter) {
      // Not shown to the other player: no float text, and the opponent has no ability button or
      // elixir bar on the screen. Left out on purpose.
    }

    /**
     * The elixir a side was shown gaining since the last call, in ten-thousandths, and starts the
     * next sum.
     *
     * @param side 0 or 1
     */
    int take(int side) {
      int amount = shown[side];
      shown[side] = 0;
      return amount;
    }

    /** Forgets every gain not yet taken. */
    void clear() {
      shown[0] = 0;
      shown[1] = 0;
    }
  }
}
