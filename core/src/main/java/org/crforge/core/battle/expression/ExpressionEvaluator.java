package org.crforge.core.battle.expression;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Runs a compiled expression: a stack machine over signed 32-bit integers.
 *
 * <p>Arithmetic wraps, division truncates toward zero, and a division or modulo by zero yields 0. A
 * comparison yields 0 or 1 and any value other than 0 is true. Neither {@code &&} nor {@code ||}
 * skips its right side: both have been evaluated, calls included, before the operator runs. The
 * answer is the value at the bottom of the stack when the code ends; a code of fewer than one word
 * answers 0, and a code that only pushes one value answers it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the eighteen opcodes, 32-bit wrapping, truncating division, zero for a division"
            + " or modulo by zero, no short circuit, the answer at the bottom of the stack and the"
            + " two early answers. Not modelled: the logged message on a division by zero, and a"
            + " corrupted opcode word.")
public final class ExpressionEvaluator {

  private ExpressionEvaluator() {
    // Utility class
  }

  /**
   * Evaluates an expression.
   *
   * @param expression the compiled code
   * @param environment answers the calls the code makes
   */
  public static int evaluate(Expression expression, ExpressionEnvironment environment) {
    int[] code = expression.code();
    if (code.length < 1) {
      return 0;
    }
    if (code.length == 2 && code[0] == Expression.PUSH) {
      return code[1];
    }
    int[] stack = new int[Math.max(expression.maxDepth(), 1)];
    int top = 0;
    int i = 0;
    while (i < code.length) {
      int op = code[i];
      switch (op) {
        case Expression.PUSH -> {
          stack[top++] = code[i + 1];
          i += 2;
        }
        case Expression.BUILTIN, Expression.CALL -> {
          int id = code[i + 1];
          int count = code[i + 2];
          int[] arguments = new int[count];
          System.arraycopy(stack, top - count, arguments, 0, count);
          top -= count;
          stack[top++] =
              op == Expression.BUILTIN
                  ? Builtins.call(id, arguments)
                  : environment.call(id, arguments);
          i += 3;
        }
        case Expression.NEGATE -> {
          stack[top - 1] = -stack[top - 1];
          i++;
        }
        case Expression.NOT -> {
          stack[top - 1] = stack[top - 1] == 0 ? 1 : 0;
          i++;
        }
        default -> {
          int right = stack[--top];
          stack[top - 1] = apply(op, stack[top - 1], right);
          i++;
        }
      }
    }
    return stack[0];
  }

  /** One binary opcode on two values. */
  static int apply(int op, int a, int b) {
    return switch (op) {
      case Expression.ADD -> a + b;
      case Expression.SUBTRACT -> a - b;
      case Expression.MULTIPLY -> a * b;
      case Expression.DIVIDE -> b == 0 ? 0 : a / b;
      case Expression.MODULO -> b == 0 ? 0 : a % b;
      case Expression.LESS -> a < b ? 1 : 0;
      case Expression.LESS_OR_EQUAL -> a <= b ? 1 : 0;
      case Expression.GREATER -> a > b ? 1 : 0;
      case Expression.GREATER_OR_EQUAL -> a >= b ? 1 : 0;
      case Expression.EQUAL -> a == b ? 1 : 0;
      case Expression.NOT_EQUAL -> a != b ? 1 : 0;
      case Expression.AND -> a != 0 && b != 0 ? 1 : 0;
      case Expression.OR -> a != 0 || b != 0 ? 1 : 0;
      default -> throw new IllegalArgumentException("not a binary opcode: " + op);
    };
  }
}
