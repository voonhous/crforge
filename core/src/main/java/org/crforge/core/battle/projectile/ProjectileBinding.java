package org.crforge.core.battle.projectile;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEnvironment;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.expression.ExpressionException;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * What an action row built for a projectile reads from it: the battle's variable keys, an empty tag
 * word, and expressions whose context is the projectile itself.
 *
 * <p>get_ping_pong_projectile_distance answers how far a pingpong projectile stood from its start
 * before its last sweep step. Any other name is refused when the row is built: what it would read
 * of a projectile is not established.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the projectile as the context of its actions' expressions, and"
            + " get_ping_pong_projectile_distance as the distance its sweep stored, held by"
            + " ice_axe_barbarians. Not modelled: every other name, refused as the row is built.")
final class ProjectileBinding implements ActionBinding {

  private static final int PINGPONG_DISTANCE =
      BattleFunctions.id("get_ping_pong_projectile_distance");

  private final BattleWorld world;
  private final ProjectileEntity projectile;

  /** What the projectile's expressions see: the one function it answers, and no other name. */
  private final ExpressionEnvironment environment;

  /**
   * @param world the battle the projectile belongs to
   * @param projectile the projectile, the context of every expression
   */
  ProjectileBinding(BattleWorld world, ProjectileEntity projectile) {
    this.world = world;
    this.projectile = projectile;
    this.environment =
        new ExpressionEnvironment() {
          @Override
          public Function resolve(String symbol) {
            BattleFunctions.Entry entry = BattleFunctions.byName(symbol);
            return entry != null && entry.id() == PINGPONG_DISTANCE
                ? new Function(entry.id(), entry.minArguments(), entry.maxArguments())
                : null;
          }

          @Override
          public int call(int id, int[] arguments) {
            return ProjectileBinding.this.projectile.getPingpongDistance();
          }
        };
  }

  @Override
  public IntSupplier expression(String text) {
    Expression expression;
    try {
      expression = ExpressionCompiler.compile(text, environment);
    } catch (ExpressionException e) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " is a projectile, whose expressions answer only"
              + " get_ping_pong_projectile_distance, not the expression: "
              + text,
          e);
    }
    return () -> ExpressionEvaluator.evaluate(expression, environment);
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
