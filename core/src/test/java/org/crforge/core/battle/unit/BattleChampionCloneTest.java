/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Clone that reaches a champion, in a match: the clone is made as any unit's is, of the
 * champion's row at 1 hit point of 1, and the champion slot of its side leaves it out. A tap naming
 * the clone is refused and pays nothing; a tap naming the champion is paid and only the champion
 * casts, the clone walking on.
 */
class BattleChampionCloneTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String QUEEN = "ArcherQueen";

  /** An Archer Queen and seven Skeletons. */
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

  /** Her point, on side 0's half, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 4000;

  private final Standard1v1Battle battle;

  private final LadderMatch match;

  BattleChampionCloneTest() {
    Standard1v1Battle made = null;
    LadderMatch started = null;
    // The first player's word whose shuffle deals her into the opening hand.
    for (int word = 0; started == null || !inHand(started, QUEEN); word++) {
      made = new Standard1v1Battle(GameData.tables(), LEVEL, false);
      started = made.startLadderMatch(QUEEN_SKELETONS, KNIGHTS, word, 0);
    }
    battle = made;
    match = started;
  }

  @Test
  @DisplayName(
      "a Clone reaching a champion makes its clone of the champion's row at 1 hit point of 1, which"
          + " the champion slot leaves out; a tap naming the clone is refused, a tap naming the"
          + " champion is paid and only the champion casts")
  void aClonedChampion() {
    CharacterEntity queen = playQueen();
    int cost = battle.getWorld().getRecords().unit(QUEEN).ability().manaCost();
    stepPastDeployUntil(cost);

    // The Clone on her point: its area reaches her, she is set up as a clone and moves apart.
    int cast = battle.getBattle().getTick();
    battle.placeAreaEffect(cast, "Clone", LEVEL, 0, X, Y, "C");
    run(cast + 1);
    CharacterEntity clone = named(queen.name() + "_clone0");
    assertThat(clone.isClone()).isTrue();
    assertThat(queen.isClone()).isFalse();
    assertThat(clone.getData().name()).isEqualTo(QUEEN);
    assertThat(clone.getData().champion()).isTrue();
    assertThat(clone.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);

    // Past the move apart, with her cost in hand.
    stepUntil(cost);
    step(20);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.champions()).containsExactly(queen);

    // A tap naming the clone finds no slot following it: refused, nothing paid.
    int tick = battle.getBattle().getTick();
    battle.useAbility(tick, 0, clone.getId(), "clone tap");
    run(tick);
    AbilityCommand.Outcome refused = lastOutcome();
    assertThat(refused.code()).isEqualTo(AbilityCommand.NO_CHAMPION);
    assertThat(refused.elixirAfter()).isEqualTo(refused.elixirBefore());

    // A tap naming her is paid; she casts and the clone does not.
    tick = battle.getBattle().getTick();
    battle.useAbility(tick, 0, queen.name(), "queen tap");
    run(tick);
    AbilityCommand.Outcome paid = lastOutcome();
    assertThat(paid.code()).isEqualTo(AbilityCommand.OK);
    assertThat(paid.elixirAfter()).isLessThan(paid.elixirBefore());
    boolean queenCast = false;
    for (int i = 0; i < 10; i++) {
      step(1);
      queenCast |= queen.getView().getState() == GridEntityState.CASTING;
      assertThat(clone.getView().getState()).isNotEqualTo(GridEntityState.CASTING);
    }
    assertThat(queenCast).as("she casts").isTrue();
  }

  /** Plays her on the next tick side 0 holds her cost, kept where she stands. */
  private CharacterEntity playQueen() {
    int cost = GameData.records().matchCard(QUEEN).cost();
    stepUntil(cost);
    int tick = battle.getBattle().getTick();
    battle.play(tick, battle.getWorld().getRecords().card(QUEEN), LEVEL, 0, X, Y, "q");
    run(tick);
    Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
    assertThat(play.matchCode()).as("her play passes the match's gates").isZero();
    CharacterEntity queen = play.units().get(0);
    queen.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return queen;
  }

  /** Steps past her deploy, until side 0 holds the given whole elixir. */
  private void stepPastDeployUntil(int elixir) {
    int deployed =
        battle.getBattle().getTick() + Shipped.number(Shipped.unitRow(QUEEN), "DeployTime") / 50;
    while (battle.getBattle().getTick() <= deployed) {
      battle.getBattle().step();
    }
    stepUntil(elixir);
  }

  private void stepUntil(int elixir) {
    while (match.side(0).wholeElixir() < elixir) {
      battle.getBattle().step();
    }
  }

  private void step(int ticks) {
    for (int i = 0; i < ticks; i++) {
      battle.getBattle().step();
    }
  }

  /** Steps the battle until it has run the given tick. */
  private void run(int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }

  private AbilityCommand.Outcome lastOutcome() {
    List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
    return uses.get(uses.size() - 1).outcome();
  }

  private CharacterEntity named(String name) {
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      if (entity instanceof CharacterEntity c && c.name().equals(name)) {
        return c;
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }
}
