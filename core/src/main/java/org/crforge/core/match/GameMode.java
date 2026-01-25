package org.crforge.core.match;

/**
 * Defines the available game modes.
 */
public enum GameMode {
  /**
   * Standard 1v1 match: 3 minutes + 2 minutes overtime.
   */
  STANDARD_1V1,

  /**
   * 2v2 match: Wider arena, 2 players per team.
   */
  MATCH_2V2,

  /**
   * Double elixir mode: Elixir regenerates at 2x speed.
   */
  DOUBLE_ELIXIR,

  /**
   * Triple elixir mode: Elixir regenerates at 3x speed.
   */
  TRIPLE_ELIXIR,

  /**
   * Sudden death: First crown wins.
   */
  SUDDEN_DEATH
}
