/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEnvironment;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.expression.ExpressionException;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * What an action row built for an area effect reads from it: the battle's variable keys, an empty
 * tag word, and expressions whose context is the area effect itself.
 *
 * <p>An area effect's action holder has the area effect as its owner, and an expression evaluated
 * for one of its actions starts from that owner: x and y are its point, team_index its side's low
 * bit, and team_y_direction, map_width and rand answer as they do for any context. Any other name
 * is refused when the row is built: what it would read of an area effect is not established.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the area effect as the context of its actions' expressions; x and y as its"
            + " point, team_index as its side's low bit, team_y_direction as -1 for 0 and 1 for"
            + " anything else, map_width as the arena's cells times 500, held by the reference"
            + " battles card_Graveyard and spell_graveyard_into_push; rand as one draw from the"
            + " battle's random source. Not modelled: every other name, refused as the row is"
            + " built.")
final class AreaEffectBinding implements ActionBinding {

  private static final int X = BattleFunctions.id("x");
  private static final int Y = BattleFunctions.id("y");
  private static final int TEAM_INDEX = BattleFunctions.id("team_index");
  private static final int TEAM_Y_DIRECTION = BattleFunctions.id("team_y_direction");
  private static final int MAP_WIDTH = BattleFunctions.id("map_width");
  private static final int RAND = BattleFunctions.id("rand");

  /** The functions an area effect's expressions may name, by their ids. */
  private static final Set<Integer> ANSWERED =
      Set.of(X, Y, TEAM_INDEX, TEAM_Y_DIRECTION, MAP_WIDTH, RAND);

  private final BattleWorld world;
  private final AreaEffectEntity area;

  /** What the area effect's expressions see: the functions it answers, and no other name. */
  private final ExpressionEnvironment environment;

  /**
   * @param world the battle the area effect belongs to
   * @param area the area effect, the context of every expression
   */
  AreaEffectBinding(BattleWorld world, AreaEffectEntity area) {
    this.world = world;
    this.area = area;
    this.environment =
        new ExpressionEnvironment() {
          @Override
          public Function resolve(String symbol) {
            BattleFunctions.Entry entry = BattleFunctions.byName(symbol);
            return entry != null && ANSWERED.contains(entry.id())
                ? new Function(entry.id(), entry.minArguments(), entry.maxArguments())
                : null;
          }

          @Override
          public int call(int id, int[] arguments) {
            return answer(id, arguments);
          }
        };
  }

  private int answer(int id, int[] arguments) {
    if (id == X) {
      return area.getX();
    }
    if (id == Y) {
      return area.getY();
    }
    if (id == TEAM_INDEX) {
      return area.side() & 1;
    }
    if (id == TEAM_Y_DIRECTION) {
      // It reads only its argument: -1 for team 0, 1 for any other.
      return arguments[0] == 0 ? -1 : 1;
    }
    if (id == MAP_WIDTH) {
      return world.getTileMap().width() * TileMap.CELL_UNITS;
    }
    if (id == RAND) {
      // One draw from the battle's source, taken as the expression is evaluated.
      return world.getRandom().next(arguments[0]);
    }
    throw new IllegalStateException("an area effect does not answer " + id);
  }

  @Override
  public IntSupplier expression(String text) {
    Expression expression;
    try {
      expression = ExpressionCompiler.compile(text, environment);
    } catch (ExpressionException e) {
      throw new UnsupportedOperationException(
          area.name()
              + " is an area effect, whose expressions answer only x, y, team_index,"
              + " team_y_direction, map_width and rand, not the expression: "
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
