/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit turns toward its reference on every tick of an attack: its facing becomes the line from
 * where it stands to where its reference stands, scaled to 256, as its targeting visit sets it
 * before anything moves that tick.
 */
class BattleAttackFacingTest {

  @Test
  @DisplayName(
      "a Musketeer shooting a Giant that walks past keeps its facing on the Giant, tick by tick")
  void anAttackerFacesItsReferenceEveryTick() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer = match.deploy(0, GameData.unit("Musketeer"), 11, 0, 3500, 13000);
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), 11, 1, 6500, 19000);
    GridEntity shooter = musketeer.getView();
    GridEntity walker = giant.getView();

    int attackingTicks = 0;
    int distinctFacings = 0;
    int lastX = Integer.MIN_VALUE;
    for (int tick = 0; tick < 200; tick++) {
      // The targeting visit runs before anything moves: it sees both where the last step left them.
      int[] expected = {walker.getX() - shooter.getX(), walker.getY() - shooter.getY()};
      FixedMath.normalize(expected, 256);
      battle.step();
      TargetView reference = musketeer.getUnit().targeting().getReference();
      if (shooter.getState() != GridEntityState.ATTACKING || reference == null) {
        continue;
      }
      attackingTicks++;
      assertThat(new int[] {shooter.getDirX(), shooter.getDirY()})
          .as("tick %d facing", tick)
          .containsExactly(expected);
      if (shooter.getDirX() != lastX) {
        distinctFacings++;
        lastX = shooter.getDirX();
      }
    }
    // The Giant walked while it was shot at, so the facing followed it through several headings.
    assertThat(attackingTicks).isGreaterThan(40);
    assertThat(distinctFacings).isGreaterThan(5);
  }
}
