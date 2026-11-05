/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
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
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * What an action row built for an area effect reads from it: the battle's variable keys, an empty
 * tag word, and expressions whose context is the area effect itself.
 *
 * <p>An area effect's action holder has the area effect as its owner, and an expression evaluated
 * for one of its actions starts from that owner: x and y are its point, team_index its side's low
 * bit, collision_radius its radius as it stands, is_contained_by_circle whether it lies whole
 * within a circle, self its id, and team_y_direction, map_width, rand and as_int with its context
 * keys answer as they do for any context. Any other name is refused when the row is built: what it
 * would read of an area effect is not established.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the area effect as the context of its actions' expressions; x and y as its"
            + " point, team_index as its side's low bit, team_y_direction as -1 for 0 and 1 for"
            + " anything else, map_width as the arena's cells times 500, held by the reference"
            + " battles card_Graveyard and spell_graveyard_into_push; rand as one draw from the"
            + " battle's random source; collision_radius as its radius as it stands,"
            + " is_contained_by_circle as its radius taken off the circle's and the squared"
            + " distance of the centres, self as its id and as_int as the battle's own environment"
            + " reads it, each read from the build's function and run by the evolved Giant's rage"
            + " areas, where nothing they decide is seen. Not modelled: every other name, refused"
            + " as the row is built.")
final class AreaEffectBinding implements ActionBinding {

  private static final int X = BattleFunctions.id("x");
  private static final int Y = BattleFunctions.id("y");
  private static final int TEAM_INDEX = BattleFunctions.id("team_index");
  private static final int TEAM_Y_DIRECTION = BattleFunctions.id("team_y_direction");
  private static final int MAP_WIDTH = BattleFunctions.id("map_width");
  private static final int RAND = BattleFunctions.id("rand");
  private static final int COLLISION_RADIUS = BattleFunctions.id("collision_radius");
  private static final int IS_CONTAINED_BY_CIRCLE = BattleFunctions.id("is_contained_by_circle");

  /** The functions an area effect's expressions may name, by their ids. */
  private static final Set<Integer> ANSWERED =
      Set.of(
          X,
          Y,
          TEAM_INDEX,
          TEAM_Y_DIRECTION,
          MAP_WIDTH,
          RAND,
          COLLISION_RADIUS,
          IS_CONTAINED_BY_CIRCLE);

  /** The id the binding calls as_int by, as the battle's own environment does. */
  private static final int AS_INT = BattleExpressionEnvironment.AS_INT;

  /** The id the binding calls self by, as the battle's own environment does. */
  private static final int SELF = BattleExpressionEnvironment.SELF;

  /** What as_int answers for a key no board holds when the expression gives no default. */
  private static final int AS_INT_NO_DEFAULT = -1;

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
            // as_int ahead of the table, as the battle's own environment takes it.
            if (symbol.equalsIgnoreCase("as_int")) {
              return new Function(AS_INT, 1, 2);
            }
            BattleFunctions.Entry entry = BattleFunctions.byName(symbol);
            if (entry != null) {
              return ANSWERED.contains(entry.id())
                  ? new Function(entry.id(), entry.minArguments(), entry.maxArguments())
                  : null;
            }
            // The build's symbol map takes self, by its exact name, after the functions.
            return symbol.equals("self") ? new Function(SELF, 0, 0) : null;
          }

          @Override
          public Integer constant(String name) {
            // A context key, #name: the hash of the name after the sigil.
            return name.startsWith("#") ? ActionContext.key(name.substring(1)) : null;
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
    if (id == COLLISION_RADIUS) {
      // An area effect's radius as it stands, the one it hits with.
      return area.radius();
    }
    if (id == IS_CONTAINED_BY_CIRCLE) {
      return containedByCircle(arguments[0], arguments[1], arguments[2]);
    }
    if (id == SELF) {
      // The area effect's own id, as the build reads its symbol.
      return area.getId();
    }
    if (id == AS_INT) {
      return asInt(arguments);
    }
    throw new IllegalStateException("an area effect does not answer " + id);
  }

  /**
   * is_contained_by_circle(radius, x, y): 1 when the whole of the area effect lies within the
   * circle of that radius about that point, its own radius as it stands taken off the circle's: 0
   * when the circle is smaller than the area, else whether the squared distance between the two
   * centres (at most the largest integer, which it reaches when either axis is 46341 or more apart)
   * is at most the square of what is left.
   */
  private int containedByCircle(int radius, int x, int y) {
    int left = radius - area.radius();
    if (left < 0) {
      return 0;
    }
    return FixedMath.guardedSumOfSquares(x - area.getX(), y - area.getY()) <= left * left ? 1 : 0;
  }

  /**
   * as_int(key, default): the value under the key in the context of what the area effect's holder
   * is doing now, or with none of what the holder running the action is doing, the main board first
   * and then the scratch board, else the default, -1 without one, as the battle's own environment
   * reads it. The game reads the context unchecked, so it never evaluates as_int without one; here
   * that is refused.
   */
  private int asInt(int[] arguments) {
    ActionContext context = area.actionHolder().currentContext();
    if (context == null) {
      context = ActionHolder.activeContext();
    }
    if (context == null) {
      throw new UnsupportedOperationException(
          "as_int on " + area.name() + " with no action context, which is not established");
    }
    Integer value = context.read(arguments[0]);
    if (value != null) {
      return value;
    }
    return arguments.length == 2 ? arguments[1] : AS_INT_NO_DEFAULT;
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
              + " team_y_direction, map_width, rand, collision_radius, is_contained_by_circle,"
              + " self and as_int, not the expression: "
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
