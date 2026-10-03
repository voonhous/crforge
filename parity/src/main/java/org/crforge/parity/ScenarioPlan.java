package org.crforge.parity;

import java.util.List;

/**
 * A replay scenario translated into the production simulator's own inputs: nothing here is read
 * from a reference run's observations.
 *
 * @param seed the battle stream's seed
 * @param towerLevel the level the six towers are created at, counted from 1
 * @param decks each side's deck, by card row name, in the scenario's order
 * @param deckLevels each side's card levels by deck index, counted from 1 across all rarities
 * @param accounts each side's account id, high word then low word
 * @param playerDataChoices how many choices each player's data lists, in the scenario's order
 * @param plays the card plays, in the scenario's order
 */
public record ScenarioPlan(
    int seed,
    int towerLevel,
    List<List<String>> decks,
    List<int[]> deckLevels,
    List<int[]> accounts,
    List<Integer> playerDataChoices,
    List<Play> plays) {

  /**
   * One place-card command.
   *
   * @param index the command's index in the scenario
   * @param givenTick the tick the command was given on
   * @param runTick the tick the command runs on
   * @param side the playing side
   * @param card the card row's name
   * @param level the level it is played at, counted from 1 across all rarities
   * @param x the requested point, in game units
   * @param y the requested point, in game units
   */
  public record Play(
      int index, int givenTick, int runTick, int side, String card, int level, int x, int y) {}
}
