package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.GameTags;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The battle's entities as filters and expressions see them: a filter asked about the towers, and a
 * tag an action sets on the king read back through an expression once the tag word is recomputed.
 */
class BattleFiltersAndTagsTest {

  private static TowerEntity tower(Standard1v1Battle match, String name) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.name().equals(name)) {
        return tower;
      }
    }
    throw new IllegalStateException(name);
  }

  @Test
  @DisplayName("an enemy-towers filter passes the other side's towers and nothing of its own side")
  void aFilterAsksAboutTheTowers() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    GameObjectFilter enemyTowers =
        GameObjectFilter.builder().matchTeamEnemy(true).matchTowers(true).filterDead(false).build();
    GameObjectFilter enemyPrincesses = enemyTowers.toBuilder().filterSummoner(true).build();

    TowerEntity ownKing = tower(match, "KingTower_0_0");
    TowerEntity enemyKing = tower(match, "KingTower_1_0");
    TowerEntity enemyPrincess = tower(match, "PrincessTower_1_1");
    assertThat(enemyTowers.matches(enemyKing.filterSubject(), 0, "")).isTrue();
    assertThat(enemyTowers.matches(enemyPrincess.filterSubject(), 0, "")).isTrue();
    assertThat(enemyTowers.matches(ownKing.filterSubject(), 0, "")).isFalse();
    assertThat(enemyPrincesses.matches(enemyKing.filterSubject(), 0, ""))
        .as("the king is the summoner")
        .isFalse();
    assertThat(enemyPrincesses.matches(enemyPrincess.filterSubject(), 0, "")).isTrue();
  }

  @Test
  @DisplayName(
      "the king's sleeping tag, set by its wait, is in its tag word from the next pre-hook")
  void anExpressionReadsATagAnActionSet() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    BattleWorld world = match.getWorld();
    world.registerGameTag("INACTIVE", GameTags.INACTIVE);
    TowerEntity king = tower(match, "KingTower_0_0");
    TowerEntity princess = tower(match, "PrincessTower_0_1");
    Expression inactive =
        ExpressionCompiler.compile("INACTIVE", new BattleExpressionEnvironment(king, world));

    battle.step();
    assertThat(ExpressionEvaluator.evaluate(inactive, new BattleExpressionEnvironment(king, world)))
        .as("the wait starts in tick 0's first pending pass, after that tick's recompute")
        .isZero();
    battle.step();
    assertThat(ExpressionEvaluator.evaluate(inactive, new BattleExpressionEnvironment(king, world)))
        .as("tick 1's recompute folds its tag in")
        .isEqualTo(1);
    assertThat(
            ExpressionEvaluator.evaluate(
                inactive, new BattleExpressionEnvironment(princess, world)))
        .isZero();
    assertThat(king.getView().getFlags() & GameTags.INACTIVE).isEqualTo(GameTags.INACTIVE);
  }
}
