/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Royal Chef's cooking in a team battle: the game cooks from the Chef's own tower only, as a
 * battle of two does, but how it picks a pancake's target among the two players of its team is not
 * held by any recorded team battle, so the first throw is refused.
 */
class ChefCookingTeamBattleTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * A team battle with side 2 on the Royal Chef's towers, every other side on the princess towers.
   */
  private static Standard1v1Battle battle(GameTables tables) {
    List<Standard1v1Battle.Towers> sides = new ArrayList<>();
    for (int side = 0; side < BattleWorld.TEAM_BATTLE; side++) {
      String selection = side == 2 ? "King_ChefTowers" : ReplayScenario.TEAM_TOWER_SELECTION;
      String group =
          tables.table("support_cards").row(selection).string(ReplayScenario.TEAM_SPAWN_GROUP);
      sides.add(new Standard1v1Battle.Towers(group, LEVEL, LEVEL));
    }
    return new Standard1v1Battle(
        tables, sides, false, tables.tileMap(ReplayScenario.TEAM_TILE_MAP));
  }

  @Test
  @DisplayName(
      "a team battle refuses the Royal Chef's first pancake, whose target among the team's two"
          + " players' troops no recorded team battle holds")
  void refusesTheFirstPancake() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    // A Giant of the Chef's side behind its tower, for the pancake to go to.
    battle.play(200, records.card("Giant"), LEVEL, 2, 3500, 3000, "g");

    assertThatThrownBy(
            () -> {
              while (battle.getBattle().getTick() < 4000) {
                battle.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("pancake")
        .hasMessageContaining("team battle");
  }
}
