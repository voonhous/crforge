/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The kings of a generated case: the case leaves each player's king level out, so the game creates
 * each king one level below its rarity's first level. Its hit points and hits are the first level's
 * all the same, so only a change of the king's level shows where it stands.
 */
class GeneratedKingLevelTest {

  /** The Royal Chef's level-up, the action its pancake runs on what it lands on. */
  private static final String CHEF_LEVEL_UP = "ChefTower_increase_level_action";

  @Test
  @DisplayName(
      "a generated case's king stands one level below the first: a level-up brings it to the first"
          + " level and its hit points stay the first level's")
  void aGeneratedCasesKingStandsOneLevelBelowTheFirst() {
    GameTables tables = GameData.tables();
    assumeTrue(
        ReplayFormat.generated(tables.version()).isPresent(),
        "no recorded battle has established the generated cases' fields of data version "
            + tables.version());
    ScenarioPlan plan =
        new ReplayScenario(tables, ScenarioShape.GENERATED)
            .translate(ScenarioItems.fitted(Scenarios.generatedKnight(), tables));
    Standard1v1Battle match = ReplayBattle.build(tables, plan);
    match.getBattle().step();
    TowerEntity king = BattleTowers.towerNamed(match.getBattle(), "KingTower_1_0");
    // The king row's hit points at its rarity's first level are the row's own.
    int firstLevelHitPoints = king.getData().hitpoints();

    assertThat(PackedLevel.steps(king.getPackedLevel()))
        .as("one step below the rarity's first level")
        .isEqualTo(-1);
    assertThat(king.level()).isZero();
    assertThat(king.getHitPoints().getMaximum()).isEqualTo(firstLevelHitPoints);

    king.actionHolder()
        .start(
            match.getWorld().getActions().build(CHEF_LEVEL_UP, match.getWorld().binding(king)),
            king.actionHolder());

    assertThat(king.level()).as("raised to the first level").isEqualTo(1);
    assertThat(PackedLevel.steps(king.getPackedLevel())).isZero();
    assertThat(king.getHitPoints().getMaximum())
        .as("the first level's maximum, as one level below it had")
        .isEqualTo(firstLevelHitPoints);
    assertThat(king.getHitPoints().getHitPoints()).isEqualTo(firstLevelHitPoints);
  }
}
