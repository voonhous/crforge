/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The views of its side's champions a champion slot hands its observers each step: built for an
 * observer that wants them, and an empty list, without the walk over every entity, when none does.
 * Nothing in the battle reads them.
 */
class BattleChampionViewsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String QUEEN = "ArcherQueen";

  private static final List<String> QUEEN_SKELETONS =
      List.of(
          QUEEN,
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** What an observer was handed in the champion slots' steps. */
  private static final class Views implements WorldObserver {
    final boolean wanted;
    final List<List<ChampionView>> handed = new ArrayList<>();

    Views(boolean wanted) {
      this.wanted = wanted;
    }

    @Override
    public boolean wantsChampionViews() {
      return wanted;
    }

    @Override
    public void championStepped(
        int tick, ChampionController slot, int elixir, List<ChampionView> views) {
      handed.add(views);
    }
  }

  /**
   * Plays the Archer Queen for side 0 as soon as it may and steps half a minute on, the observer
   * attached from the start.
   */
  private static void queenBattle(WorldObserver observer) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    // The first player's word whose shuffle deals her into the opening hand.
    for (int word = 0; match == null || !inHand(match); word++) {
      battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
      match = battle.startLadderMatch(QUEEN_SKELETONS, KNIGHTS, word, 0);
    }
    battle.getWorld().addObserver(observer);
    int cost = GameData.records().matchCard(QUEEN).cost();
    while (battle.getBattle().getTick() < match.getStartCooldownTicks()
        || match.side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();
    battle.play(tick, battle.getWorld().getRecords().card(QUEEN), LEVEL, 0, 3500, 4000, "q");
    for (int i = 0; i < 600; i++) {
      battle.getBattle().step();
    }
  }

  private static boolean inHand(LadderMatch match) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(QUEEN));
  }

  @Test
  @DisplayName("an observer that wants the champion views is handed the champion on its side")
  void anObserverThatWantsThemSeesTheChampion() {
    Views views = new Views(true);
    queenBattle(views);
    assertThat(views.handed).as("the slot steps once she is played").isNotEmpty();
    assertThat(views.handed)
        .anySatisfy(
            handed ->
                assertThat(handed)
                    .extracting(ChampionView::row)
                    .as("she is in her slot's views")
                    .contains(QUEEN));
  }

  @Test
  @DisplayName("an observer that does not want the champion views is handed empty lists")
  void anObserverThatDoesNotWantThemIsHandedNone() {
    Views views = new Views(false);
    queenBattle(views);
    assertThat(views.handed).as("the slot still steps and tells it").isNotEmpty();
    assertThat(views.handed).allSatisfy(handed -> assertThat(handed).isEmpty());
  }
}
