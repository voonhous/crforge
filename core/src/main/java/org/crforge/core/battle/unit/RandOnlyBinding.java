package org.crforge.core.battle.unit;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEnvironment;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.expression.ExpressionException;

/**
 * What an action row built for an object that is not an arena entity reads from it: the battle's
 * variable keys, an empty tag word, and expressions that name nothing but {@code rand}, which reads
 * only the battle's random source and so answers alike on whatever object it runs. Any other name
 * is refused when the row is built: what it would read of such an object is not modelled.
 */
final class RandOnlyBinding implements ActionBinding {

  /** The one function such an object's expressions may name. */
  private static final BattleFunctions.Entry RAND = BattleFunctions.byName("rand");

  private final BattleWorld world;
  private final String name;

  /**
   * What the object's expressions see: rand, as the battle's table has it, drawing from the
   * battle's source as the expression is evaluated, and no other name.
   */
  private final ExpressionEnvironment randOnly;

  /**
   * @param world the battle the object belongs to
   * @param name the object's name, for a refusal
   */
  RandOnlyBinding(BattleWorld world, String name) {
    this.world = world;
    this.name = name;
    this.randOnly =
        new ExpressionEnvironment() {
          @Override
          public Function resolve(String symbol) {
            return RAND.name().equalsIgnoreCase(symbol)
                ? new Function(RAND.id(), RAND.minArguments(), RAND.maxArguments())
                : null;
          }

          @Override
          public int call(int id, int[] arguments) {
            return world.getRandom().next(arguments[0]);
          }
        };
  }

  @Override
  public IntSupplier expression(String text) {
    Expression expression;
    try {
      expression = ExpressionCompiler.compile(text, randOnly);
    } catch (ExpressionException e) {
      throw new UnsupportedOperationException(
          name + " stands in for an object and answers only rand, not the expression: " + text, e);
    }
    return () -> ExpressionEvaluator.evaluate(expression, randOnly);
  }

  @Override
  public int variableKey(String variable) {
    return world.declaredVariable(variable);
  }

  @Override
  public LongSupplier tags() {
    return () -> 0;
  }
}
