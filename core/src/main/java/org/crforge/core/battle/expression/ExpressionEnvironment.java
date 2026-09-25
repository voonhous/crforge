package org.crforge.core.battle.expression;

/**
 * What an expression's symbols mean: the functions an environment offers, each with an id and the
 * number of arguments it takes, and the answer each gives when the evaluator calls it.
 *
 * <p>The compiler asks the environment about a symbol before it looks at the builtins, so an
 * environment name shadows a builtin of the same spelling.
 */
public interface ExpressionEnvironment {

  /**
   * A function the environment offers.
   *
   * @param id the id the compiled code calls it by
   * @param minArguments the fewest arguments it takes
   * @param maxArguments the most arguments it takes
   */
  record Function(int id, int minArguments, int maxArguments) {}

  /** The function a symbol names, matched as the environment matches names; null for none. */
  Function resolve(String name);

  /**
   * Calls a function.
   *
   * @param id the function's id
   * @param arguments its arguments, in the order they were written
   */
  int call(int id, int[] arguments);
}
