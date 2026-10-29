/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * A replay scenario translated into the production simulator's own inputs: nothing here is read
 * from a reference run's observations.
 *
 * <p>A replay's card level minimum ({@link #cardLevelMinimum}) raises each deck card and each
 * side's princess towers to it as the battle is set up: {@link #raisedDeckLevels} and {@link
 * #raisedTowers} give the levels the battle stands them at, while {@link #deckLevels} and {@link
 * #towers} keep the levels as the replay gives them, so a written replay reads back the same.
 *
 * @param seed the battle stream's seed
 * @param towers each side's towers as the replay gives them: the spawn group of its tower
 *     selection, with the level of its king row and of its other rows, counted from 1; the battle
 *     stands them at {@link #raisedTowers}
 * @param decks each side's deck, by card row name, in the scenario's order
 * @param deckLevels each side's card levels by deck index as the replay gives them, counted from 1
 *     across all rarities; the battle's deck holds them at {@link #raisedDeckLevels}
 * @param slotFlags each side's slot flags by deck index: bit 0 the deck's evolution slot, bit 1 its
 *     hero slot
 * @param accounts each side's account id, high word then low word
 * @param arenas each side's arena, by row name of the arenas table: the arena its player's avatar
 *     names in a replay. The battle does not read it; a replay carries it, and a written replay
 *     gives each avatar the row's data id
 * @param playerDataChoices how many choices each player's data lists, in the scenario's order
 * @param plays the card plays, in the scenario's order
 * @param abilities the ability commands, in the scenario's order; with the plays they run in the
 *     scenario's order within a tick
 * @param cardLevelMinimum the replay's card level minimum ({@value
 *     ReplayFormat#CARD_LEVEL_MIN_FIELD}), counted from 1 across all rarities; 0 for none
 */
public record ScenarioPlan(
    int seed,
    List<Standard1v1Battle.Towers> towers,
    List<List<String>> decks,
    List<int[]> deckLevels,
    List<int[]> slotFlags,
    List<int[]> accounts,
    List<String> arenas,
    List<Integer> playerDataChoices,
    List<Play> plays,
    List<Ability> abilities,
    int cardLevelMinimum) {

  /**
   * A scenario with no card level minimum: every deck card and tower stands at its own level.
   *
   * @see #ScenarioPlan(int, List, List, List, List, List, List, List, List, List, int)
   */
  public ScenarioPlan(
      int seed,
      List<Standard1v1Battle.Towers> towers,
      List<List<String>> decks,
      List<int[]> deckLevels,
      List<int[]> slotFlags,
      List<int[]> accounts,
      List<String> arenas,
      List<Integer> playerDataChoices,
      List<Play> plays,
      List<Ability> abilities) {
    this(
        seed,
        towers,
        decks,
        deckLevels,
        slotFlags,
        accounts,
        arenas,
        playerDataChoices,
        plays,
        abilities,
        0);
  }

  /**
   * A level raised by the card level minimum: the minimum when the level stands below it, else the
   * level itself. The game raises a level index to the minimum less its rarity's RelativeLevel and
   * 1, which counted from 1 across all rarities is the minimum; it never lowers one.
   *
   * @param level a level counted from 1 across all rarities
   * @return the level the battle stands it at
   */
  public int raised(int level) {
    return cardLevelMinimum >= 1 ? Math.max(level, cardLevelMinimum) : level;
  }

  /**
   * Each side's card levels by deck index as the battle's deck holds them: each raised by the card
   * level minimum ({@link #raised}). A play runs at the level its item carries, which the scenario
   * checks against these and the levels as given.
   *
   * @return the raised levels, counted from 1 across all rarities
   */
  public List<int[]> raisedDeckLevels() {
    List<int[]> raised = new ArrayList<>();
    for (int[] levels : deckLevels) {
      int[] side = new int[levels.length];
      for (int index = 0; index < levels.length; index++) {
        side[index] = raised(levels[index]);
      }
      raised.add(side);
    }
    return raised;
  }

  /**
   * Each side's towers as the battle stands them: the princess rows (the tower selection's level)
   * raised by the card level minimum ({@link #raised}), the king row at its own level, which the
   * minimum does not raise under the Ladder row.
   *
   * @return the towers the battle is built with
   */
  public List<Standard1v1Battle.Towers> raisedTowers() {
    List<Standard1v1Battle.Towers> raised = new ArrayList<>();
    for (Standard1v1Battle.Towers side : towers) {
      raised.add(
          new Standard1v1Battle.Towers(side.spawnGroup(), side.kingLevel(), raised(side.level())));
    }
    return raised;
  }

  /**
   * One place-card command.
   *
   * @param index the command's index in the scenario
   * @param givenTick the tick the command was given on
   * @param runTick the tick the command runs on
   * @param side the playing side
   * @param card the card row's name
   * @param level the level it is played at, counted from 1 across all rarities: its item's level
   *     field plus 1, which is its deck card's level as given or, below the card level minimum, as
   *     raised
   * @param x the requested point, in game units
   * @param y the requested point, in game units
   * @param item the packed item the play carries, as given; the parts that depend on the battle are
   *     checked against the item the simulator builds as the play runs ({@link
   *     ReplayScenario#checkItem})
   * @param repeats for a Mirror's play, the card row its item names as the card it repeats ({@code
   *     fs}), which the run checks against the card the simulator's Mirror repeats ({@link
   *     ReplayScenario#checkMirrorItem}); null for a play of any other card
   * @param option for a variant card's play, the option its item names (the option field) and that
   *     option's cost, which the play runs as, its item checked against the one the simulator
   *     builds ({@link ReplayScenario#checkVariantItem}); null for a play of any other card
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
