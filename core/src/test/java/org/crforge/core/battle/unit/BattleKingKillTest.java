/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit that kills the enemy king keeps it. The king stays in the holder with no hit points, and a
 * unit's targeting component keeps a reference whose hit points are gone until it leaves the holder
 * (the validator's alive check is bypassed for every unit). With every attack timer held from the
 * match's end, the killer stands where it struck, in the attacking state, for the whole end delay.
 */
class BattleKingKillTest {

  private static final List<String> DECK =
      List.of(
          "Knight",
          "Archer",
          "Giant",
          "MiniPekka",
          "Musketeer",
          "Valkyrie",
          "Barbarians",
          "Minions");

  @Test
  @DisplayName(
      "a Pekka that kills the king keeps the dead king and stands attacking it through the end"
          + " delay")
  void theKillerKeepsTheDeadKing() {
    Standard1v1Battle standard = new Standard1v1Battle(GameData.tables());
    LadderMatch match = standard.startLadderMatch(DECK, DECK, 0, 0);
    Battle battle = standard.getBattle();
    TowerEntity king = standard.getWorld().kingTower(1);
    CharacterEntity pekka = standard.deploy(0, GameData.unit("Pekka"), 11, 0, 9000, 25000);
    GridEntity killer = pekka.getView();

    // The king is left one hit from falling, so the Pekka's first hit on it ends the match.
    battle.step();
    king.takeDamage(king.getHitPoints().getHitPoints() - 1, 0, 0, 1);
    while (!match.isEnded() && battle.getTick() < 1000) {
      battle.step();
    }
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isZero();
    assertThat(king.getHitPoints().getHitPoints()).isZero();

    int x = killer.getX();
    int y = killer.getY();
    int steps = 0;
    while (!match.isOver()) {
      battle.step();
      steps++;
      TargetView reference = pekka.getUnit().targeting().getReference();
      assertThat(reference).as("step %d reference", steps).isNotNull();
      assertThat(reference.getEntity()).as("step %d reference", steps).isSameAs(king.getView());
      assertThat(killer.getState()).as("step %d state", steps).isEqualTo(GridEntityState.ATTACKING);
      assertThat(new int[] {killer.getX(), killer.getY()})
          .as("step %d position", steps)
          .containsExactly(x, y);
    }
    assertThat(steps).isGreaterThan(50);
  }
}
