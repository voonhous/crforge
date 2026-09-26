package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The battle as an expression sees it from a king tower. */
class BattleExpressionEnvironmentTest {

  @Test
  @DisplayName("every one of the 47 names resolves, so every expression of the data compiles")
  void everyNameResolves() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());

    for (BattleFunctions.Entry entry : BattleFunctions.ALL) {
      assertThat(environment.resolve(entry.name())).as(entry.name()).isNotNull();
      assertThat(environment.resolve(entry.name()).id()).isEqualTo(entry.id());
    }
    assertThat(environment.resolve("not_a_function")).isNull();
  }

  @Test
  @DisplayName("the king's condition follows its side's king and princess towers")
  void theKingsCondition() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    Battle battle = match.getBattle();
    battle.step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    TowerEntity otherKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_0_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());
    Expression condition =
        ExpressionCompiler.compile(TowerEntity.ACTIVATION_CONDITION, environment);

    assertThat(ExpressionEvaluator.evaluate(condition, environment)).isZero();

    // The other side's king losing hit points leaves this king asleep.
    match.getWorld().dealDamage(otherKing.getTargetView(), 1, 0, 1);
    assertThat(ExpressionEvaluator.evaluate(condition, environment)).isZero();

    // Its own side's princess tower destroyed: the side keeps only one.
    TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, "PrincessTower_1_1");
    match.getWorld().dealDamage(princess.getTargetView(), 100000, 0, 1);
    assertThat(ExpressionEvaluator.evaluate(condition, environment))
        .as("a destroyed tower counts until the cleanup that removes it")
        .isZero();
    battle.step();
    assertThat(ExpressionEvaluator.evaluate(condition, environment)).isEqualTo(1);
  }

  @Test
  @DisplayName("the king's own hit points below the maximum wake it too")
  void theKingDamaged() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());
    int damaged = BattleFunctions.id("king_tower_damaged");

    assertThat(environment.call(damaged, new int[0])).isZero();
    match.getWorld().dealDamage(king.getTargetView(), 1, 0, 1);
    assertThat(environment.call(damaged, new int[0])).isEqualTo(1);
  }

  @Test
  @DisplayName("a function the battle does not answer yet fails rather than guess")
  void anUnportedFunctionFails() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());

    assertThatThrownBy(() -> environment.call(BattleFunctions.id("rand"), new int[] {10}))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("rand");
  }
}
