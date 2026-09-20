package org.crforge.core.util;

import java.util.function.Supplier;

/**
 * Precondition checks that read as the condition that must hold rather than the failure that must
 * be avoided.
 *
 * <p>An inline {@code if (bad) throw ...} states the negation of the rule, so a guard with several
 * clauses forces the reader to invert each one to recover the intent. These helpers take the
 * positive form instead: {@code checkArgument(level >= 1, ...)} is the rule itself. Composite
 * guards are best split into one call per rule so a failure names the single clause that broke.
 *
 * <p>Use {@link #checkArgument} for constraints on method arguments ({@link
 * IllegalArgumentException}) and {@link #checkState} for constraints on the receiver's state, such
 * as an initialisation step the caller skipped ({@link IllegalStateException}).
 *
 * <p>Java evaluates arguments eagerly, so passing a concatenated message directly builds that
 * string on every call, including the passing ones -- work the equivalent {@code if} block never
 * did, because the concatenation sat on the failure path. Prefer the {@link Supplier} overloads
 * whenever the message does any work (concatenation or formatting); they defer it to the failure
 * path. A message that is a compile-time constant has nothing to defer, so pass it as a plain
 * {@link String} rather than wrapping it in a lambda that only adds a capture.
 */
public final class ValidationUtils {

  private ValidationUtils() {
    // Utility class
  }

  /**
   * Checks an argument constraint.
   *
   * @param expression the condition that must hold
   * @throws IllegalArgumentException if {@code expression} is false
   */
  public static void checkArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /**
   * Checks an argument constraint.
   *
   * @param expression the condition that must hold
   * @param errorMessage message for the thrown exception
   * @throws IllegalArgumentException if {@code expression} is false
   */
  public static void checkArgument(boolean expression, String errorMessage) {
    if (!expression) {
      throw new IllegalArgumentException(errorMessage);
    }
  }

  /**
   * Checks an argument constraint, building the message only on failure.
   *
   * @param expression the condition that must hold
   * @param errorMessageSupplier supplies the message, invoked only when the check fails
   * @throws IllegalArgumentException if {@code expression} is false
   */
  public static void checkArgument(boolean expression, Supplier<String> errorMessageSupplier) {
    if (!expression) {
      throw new IllegalArgumentException(errorMessageSupplier.get());
    }
  }

  /**
   * Checks a constraint on the state of the calling instance rather than on its arguments.
   *
   * @param expression the condition that must hold
   * @throws IllegalStateException if {@code expression} is false
   */
  public static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /**
   * Checks a constraint on the state of the calling instance rather than on its arguments.
   *
   * @param expression the condition that must hold
   * @param errorMessage message for the thrown exception
   * @throws IllegalStateException if {@code expression} is false
   */
  public static void checkState(boolean expression, String errorMessage) {
    if (!expression) {
      throw new IllegalStateException(errorMessage);
    }
  }

  /**
   * Checks a constraint on the state of the calling instance, building the message only on failure.
   *
   * @param expression the condition that must hold
   * @param errorMessageSupplier supplies the message, invoked only when the check fails
   * @throws IllegalStateException if {@code expression} is false
   */
  public static void checkState(boolean expression, Supplier<String> errorMessageSupplier) {
    if (!expression) {
      throw new IllegalStateException(errorMessageSupplier.get());
    }
  }
}
