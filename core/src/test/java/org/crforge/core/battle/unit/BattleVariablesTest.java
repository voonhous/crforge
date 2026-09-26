package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.SetVariable;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.expression.ExpressionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A variable an action writes is the one an expression naming it reads: the battle's environment
 * resolves a name its function table does not know to one of the battle's variables, and reads it
 * from the entity the expression runs from.
 */
class BattleVariablesTest {

  private static TowerEntity tower(Standard1v1Battle match, String name) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.name().equals(name)) {
        return tower;
      }
    }
    throw new IllegalStateException(name);
  }

  @Test
  @DisplayName("an expression reads back the variable an action wrote, per entity, 0 until written")
  void anExpressionReadsTheVariableAnActionWrote() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    BattleWorld world = match.getWorld();
    world.registerVariable("V1", 41);
    TowerEntity king = tower(match, "KingTower_0_0");
    TowerEntity other = tower(match, "PrincessTower_0_1");

    Expression read =
        ExpressionCompiler.compile("V1 * 2", new BattleExpressionEnvironment(king, world));
    assertThat(ExpressionEvaluator.evaluate(read, new BattleExpressionEnvironment(king, world)))
        .as("a variable never written reads as zero")
        .isZero();

    new ActionHolder(king).start(new SetVariable(ActionRow.named("set"), () -> 7, 41));
    assertThat(ExpressionEvaluator.evaluate(read, new BattleExpressionEnvironment(king, world)))
        .isEqualTo(14);
    assertThat(ExpressionEvaluator.evaluate(read, new BattleExpressionEnvironment(other, world)))
        .as("another entity's variables are its own")
        .isZero();

    assertThatThrownBy(
            () -> ExpressionCompiler.compile("V2", new BattleExpressionEnvironment(king, world)))
        .as("a name that is neither a function nor a variable is refused")
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  @DisplayName("a battle on the game's tables declares their variables and game tags")
  void theTablesAreDeclared() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    TowerEntity tower = (TowerEntity) match.getBattle().getHolder().entities().get(0);
    ActionBinding binding = match.getWorld().binding(tower);
    int key = binding.variableKey("MiniPekkaHero_levelStack");
    tower.setVariable(key, 3);
    assertThat(binding.expression("MiniPekkaHero_levelStack + 1").getAsInt()).isEqualTo(4);
    // The king's wait starts in tick 0; its tag word shows the tag from the next pre-hook on.
    match.getBattle().step();
    match.getBattle().step();
    assertThat(binding.expression("INACTIVE").getAsInt())
        .as("the king carries INACTIVE until it wakes")
        .isEqualTo(1);
    assertThat(match.getWorld().getActions()).isNotNull();
    assertThat(match.getWorld().getRecords()).isNotNull();
  }
}
