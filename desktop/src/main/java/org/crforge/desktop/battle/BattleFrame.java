/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.List;

/**
 * What the visualizer draws of a battle core session at one moment, read by {@link BattleAdapter}
 * between steps.
 *
 * @param tick the battle's count of completed steps
 * @param timeMs the battle time: the match clock in a match, else the battle's own clock
 * @param overtime whether the match is in overtime
 * @param elixirRate the elixir rate as a multiple of the opening rate (1, 2 or 3), 0 outside a
 *     match
 * @param entities every entity of the holder's live list in its own order, mapped to its kind;
 *     objects that stand nowhere on the arena are left out
 * @param sides side 0 then side 1 in a match; empty outside one
 * @param ended whether the match has ended
 * @param winner the winning side, or -1 for a draw, once the match has ended
 * @param over whether the battle has stopped stepping by its own rule
 * @param halted why the session stopped stepping on a refusal, or null
 * @param messages the session's latest messages, oldest first
 */
public record BattleFrame(
    int tick,
    int timeMs,
    boolean overtime,
    int elixirRate,
    List<EntityView> entities,
    List<SideView> sides,
    boolean ended,
    int winner,
    boolean over,
    String halted,
    List<String> messages) {

  /**
   * One side of a match.
   *
   * @param side 0 or 1
   * @param elixir the elixir in ten-thousandths, as the match holds it
   * @param wholeElixir the whole elixir a play is checked against
   * @param crowns the crowns the side has taken
   * @param hand the four hand slots, null for an empty slot
   * @param next the card that refills the hand next, or null
   */
  public record SideView(
      int side, int elixir, int wholeElixir, int crowns, List<CardView> hand, CardView next) {}

  /**
   * One card of a hand.
   *
   * @param name the card row name
   * @param cost its cost in whole elixir
   * @param level the level its side plays it at, counted from 1 across all rarities
   * @param unavailableReason why selection is disabled, or null if available
   * @param pending whether it has been played and waits for its play to run
   */
  public record CardView(
      String name, int cost, int level, boolean pending, String unavailableReason) {}
}
