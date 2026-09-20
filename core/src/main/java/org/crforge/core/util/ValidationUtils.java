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
 * <p>Convention: pass a constant message as a plain {@link String}, and use the {@link Supplier}
 * overloads when the message concatenates or formats. Java evaluates arguments eagerly, so an
 * inline concatenation runs on every call including the passing ones, whereas a supplier keeps that
 * work on the failure path. This is a consistency rule, not a measured optimization: at this
 * codebase's handful of call sites neither form is measurably cheaper, so the point is that the
 * choice is not re-argued per site. A constant message has nothing to defer and gains nothing from
 * a lambda.
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
