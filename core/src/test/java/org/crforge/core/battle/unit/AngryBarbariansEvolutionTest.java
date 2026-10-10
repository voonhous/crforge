/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.InitialDelay;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Angry Barbarians (AngryBarbarians_EV1) as a match plays them: the evolved row lists
 * its two characters, AngryBarbarian_EV1 and AngryBarbarian_EV1_2, but sets no SummonDeployDelay of
 * its own. The stagger between a play's units is read from the deck's card, AngryBarbarians, whose
 * SummonDeployDelay is set: the second unit waits its turn before it deploys, as the plain play's
 * second unit does.
 */
class AngryBarbariansEvolutionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final List<String> DECK =
      List.of(
          "AngryBarbarians",
          "Knight",
          "Archer",
          "Skeletons",
          "IceSpirits",
          "Goblins",
          "Bats",
          "Zap");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "an evolved play's second unit waits the deck card's stagger before it deploys, as the plain"
          + " play's does")
  void theEvolvedPlayStaggersAsTheDeckCard() {
    // The case this test is about: the deck card staggers its units, the evolved row does not.
    assertThat(
            Shipped.number(
                Shipped.row("spells_characters", "AngryBarbarians"), "SummonDeployDelay"))
        .isPositive();
    assertThat(
            Shipped.column(
                Shipped.row("spells_evolved", "AngryBarbarians_EV1"), "SummonDeployDelay"))
        .isNull();
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    int[] slots = new int[8];
    slots[0] = MatchSide.EVOLUTION_SLOT;
    LadderMatch match = battle.startLadderMatch(DECK, KNIGHTS, 0, 0, slots, new int[8]);
    MatchSide side = match.side(0);
    List<Standard1v1Battle.Play> barbarians = new ArrayList<>();
    // Each play's units' states on the tick the play ran.
    List<List<Integer>> states = new ArrayList<>();
    // The plays before the evolved one: the evolved row's DarkElixirCost.
    int plain =
        Shipped.number(Shipped.row("spells_evolved", "AngryBarbarians_EV1"), "DarkElixirCost");
    List<CharacterEntity> previous = List.of();
    for (int tick = 20; barbarians.size() < plain + 1; tick += 200) {
      assertThat(tick).isLessThan(4000);
      run(battle, tick - 1);
      int pick = -1;
      for (int index : side.getHand().slots()) {
        if (index == 0 || pick < 0) {
          pick = index;
        }
      }
      DeployCard card = battle.getWorld().getRecords().card(side.deck().get(pick).name());
      boolean spell = card.spell();
      battle.play(
          tick, card, LEVEL, 0, spell ? 9000 : 3500, spell ? 9000 : 10000, card.name() + tick);
      run(battle, tick);
      Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
      if (pick == 0 && play.matchCode() == 0) {
        barbarians.add(play);
        states.add(play.units().stream().map(unit -> unit.getView().getState()).toList());
      }
      // Each play's units are removed before the next play, so no tower falls and the match runs
      // on for as many plays as the evolved row's cost asks.
      previous.forEach(unit -> battle.getWorld().kill(unit, null));
      previous = play.units();
    }

    // The plays before the count reaches the cost are plain, the last evolved.
    List<String> spells = new ArrayList<>(Collections.nCopies(plain, "AngryBarbarians"));
    spells.add("AngryBarbarians_EV1");
    assertThat(barbarians)
        .extracting(play -> play.evolution().spell().name())
        .containsExactlyElementsOf(spells);
    Standard1v1Battle.Play evolved = barbarians.get(plain);
    assertThat(evolved.units())
        .extracting(unit -> unit.getData().name())
        .containsExactlyElementsOf(
            Shipped.texts(
                Shipped.row("spells_evolved", "AngryBarbarians_EV1"), "SummonCharactersList"));
    // Every play: the first unit deploys at once, each later one waits its turn. A plain play has
    // the deck card's SummonNumber units (at least one), the evolved play one per listed character.
    int plainUnits =
        Math.max(
            Shipped.number(Shipped.row("spells_characters", "AngryBarbarians"), "SummonNumber"), 1);
    int evolvedUnits =
        Shipped.texts(Shipped.row("spells_evolved", "AngryBarbarians_EV1"), "SummonCharactersList")
            .size();
    List<List<Integer>> expected =
        new ArrayList<>(Collections.nCopies(plain, staggered(plainUnits)));
    expected.add(staggered(evolvedUnits));
    assertThat(states).containsExactlyElementsOf(expected);
  }

  /**
   * The states of a staggered play's units on the tick it ran: the first deploys, the rest wait.
   */
  private static List<Integer> staggered(int units) {
    List<Integer> states = new ArrayList<>();
    states.add(InitialDelay.DEPLOYING);
    states.addAll(Collections.nCopies(units - 1, InitialDelay.WAITING));
    return states;
  }

  /** Steps the battle through the given tick. */
  private static void run(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() <= tick) {
      battle.getBattle().step();
    }
  }
}
