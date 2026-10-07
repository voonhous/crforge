package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.InitialDelay;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Angry Barbarians (AngryBarbarians_EV1 of data version 16.402.18) as a match plays
 * them: the evolved row lists its two characters, AngryBarbarian_EV1 and AngryBarbarian_EV1_2, but
 * sets no SummonDeployDelay of its own. The stagger between a play's units is read from the deck's
 * card, AngryBarbarians, whose SummonDeployDelay is 100: the second unit waits its turn before it
 * deploys, as the plain play's second unit does.
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
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    int[] slots = new int[8];
    slots[0] = MatchSide.EVOLUTION_SLOT;
    LadderMatch match = battle.startLadderMatch(DECK, KNIGHTS, 0, 0, slots, new int[8]);
    MatchSide side = match.side(0);
    List<Standard1v1Battle.Play> barbarians = new ArrayList<>();
    // Each play's units' states on the tick the play ran.
    List<List<Integer>> states = new ArrayList<>();
    for (int tick = 20; barbarians.size() < 2; tick += 200) {
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
    }

    // The first play is plain, the second evolved.
    assertThat(barbarians)
        .extracting(play -> play.evolution().spell().name())
        .containsExactly("AngryBarbarians", "AngryBarbarians_EV1");
    Standard1v1Battle.Play evolved = barbarians.get(1);
    assertThat(evolved.units())
        .extracting(unit -> unit.getData().name())
        .containsExactly("AngryBarbarian_EV1", "AngryBarbarian_EV1_2");
    // Both plays: the first unit deploys at once, the second waits its turn.
    assertThat(states)
        .containsExactly(
            List.of(InitialDelay.DEPLOYING, InitialDelay.WAITING),
            List.of(InitialDelay.DEPLOYING, InitialDelay.WAITING));
  }

  /** Steps the battle through the given tick. */
  private static void run(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() <= tick) {
      battle.getBattle().step();
    }
  }
}
