package org.crforge.core.battle.expression;

import java.util.Arrays;

/**
 * One compiled expression: its opcode words and the deepest its value stack goes.
 *
 * <p>The words are the ones {@link ExpressionCompiler} emits and {@link ExpressionEvaluator} runs:
 * opcode 1 is followed by the value it pushes, opcodes 2 and 3 by a function's id and its argument
 * count, and every other opcode stands alone.
 *
 * @param code the opcode words
 * @param maxDepth the deepest the value stack goes while the code runs
 */
public record Expression(int[] code, int maxDepth) {

  /** Push the next word. */
  public static final int PUSH = 1;

  /** Call a builtin: the next two words are its id and its argument count. */
  public static final int BUILTIN = 2;

  /** Call the environment: the next two words are the function's id and its argument count. */
  public static final int CALL = 3;

  public static final int ADD = 4;
  public static final int SUBTRACT = 5;
  public static final int MULTIPLY = 6;
  public static final int DIVIDE = 7;
  public static final int MODULO = 8;
  public static final int LESS = 9;
  public static final int LESS_OR_EQUAL = 10;
  public static final int GREATER = 11;
  public static final int GREATER_OR_EQUAL = 12;
  public static final int EQUAL = 13;
  public static final int NOT_EQUAL = 14;
  public static final int NEGATE = 15;
  public static final int NOT = 16;
  public static final int AND = 17;
  public static final int OR = 18;

  public Expression {
    code = code.clone();
  }

  @Override
  public int[] code() {
    return code.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Expression e && Arrays.equals(code, e.code) && maxDepth == e.maxDepth;
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(code) + maxDepth;
  }

  @Override
  public String toString() {
    return "Expression" + Arrays.toString(code) + " depth " + maxDepth;
  }
}
