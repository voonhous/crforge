package org.crforge.parity;

import java.util.List;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * A replay scenario translated into the production simulator's own inputs: nothing here is read
 * from a reference run's observations.
 *
 * @param seed the battle stream's seed
 * @param towers each side's towers: the spawn group of its tower selection, with the level of its
 *     king row and of its other rows, counted from 1
 * @param decks each side's deck, by card row name, in the scenario's order
 * @param deckLevels each side's card levels by deck index, counted from 1 across all rarities
 * @param slotFlags each side's slot flags by deck index: bit 0 the deck's evolution slot, bit 1 its
 *     hero slot
 * @param accounts each side's account id, high word then low word
 * @param playerDataChoices how many choices each player's data lists, in the scenario's order
 * @param plays the card plays, in the scenario's order
 */
public record ScenarioPlan(
    int seed,
    List<Standard1v1Battle.Towers> towers,
    List<List<String>> decks,
    List<int[]> deckLevels,
    List<int[]> slotFlags,
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
   * @param item the packed item the play carries, as given; the parts that depend on the battle are
   *     checked against the item the simulator builds as the play runs ({@link
   *     ReplayScenario#checkItem})
   */
  public record Play(
      int index,
      int givenTick,
      int runTick,
      int side,
      String card,
      int level,
      int x,
      int y,
      int item) {}
}
