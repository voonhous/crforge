/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import org.crforge.core.battle.match.MatchSide;

/**
 * One side's elixir as the other side's player counts it, from what that player sees: the match's
 * starting elixir, then each tick the regeneration the clock's elixir rate gives (the same for both
 * sides, held to the maximum), less the cost of each of the side's plays placed and of each of its
 * champion abilities that ran, a played row's production stop holding the regeneration as the
 * king's own count does ({@link MatchSide}). It reads nothing of the side's hidden state, so it
 * misses what the side's elixir gains otherwise (a collector's production, a death that pays
 * elixir) and drifts from the truth there.
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
   * One tick's regeneration at the clock's rate: none while a production stop runs, which loses the
   * tick instead.
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
   * A cost taken, never more than is counted: a champion's ability that ran.
   *
   * @param amount the cost, in ten-thousandths
   */
  void spend(int amount) {
    elixir -= Math.min(amount, elixir);
  }
}
