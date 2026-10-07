package org.crforge.core.battle.replay;

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
 * @param abilities the ability commands, in the scenario's order; with the plays they run in the
 *     scenario's order within a tick
 */
public record ScenarioPlan(
    int seed,
    List<Standard1v1Battle.Towers> towers,
    List<List<String>> decks,
    List<int[]> deckLevels,
    List<int[]> slotFlags,
    List<int[]> accounts,
    List<Integer> playerDataChoices,
    List<Play> plays,
    List<Ability> abilities) {

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
   * @param repeats for a Mirror's play, the card row its item names as the card it repeats ({@code
   *     fs}), which the run checks against the card the simulator's Mirror repeats ({@link
   *     ReplayScenario#checkMirrorItem}); null for a play of any other card
   * @param option for a variant card's play, the option its item names (the option field) and that
   *     option's cost, which the run checks against the option the simulator's player picks ({@link
   *     ReplayScenario#checkVariantItem}); null for a play of any other card
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
      int item,
      Repeated repeats,
      Option option) {}

  /**
   * The card a Mirror play's item names as the one it repeats.
   *
   * @param id the row's data id, as given
   * @param name the row's name
   */
  public record Repeated(int id, String name) {}

  /**
   * The option a variant card's play item names, as the deck card's variant lists it.
   *
   * @param index the option's index in the variant's order: the item's option field less 1
   * @param spell the option's card row, which the play runs as
   * @param cost the option row's cost, which the item carries
   */
  public record Option(int index, String spell, int cost) {}

  /**
   * One ability command: the tap on a champion's button, naming one unit by its game object id. The
   * command names no row and no play, so only the live unit with that id answers it.
   *
   * @param index the command's index in the scenario
   * @param givenTick the tick the command was given on
   * @param runTick the tick the command runs on
   * @param side the commanding side
   * @param objectId the game object id of the unit it names
   */
  public record Ability(int index, int givenTick, int runTick, int side, int objectId) {}
}
