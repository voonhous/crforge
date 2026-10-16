/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.Locale;

/**
 * Why an environment's episode ended with no result: an exception inside the environment's fault
 * boundary, which ends that environment's episode alone, as truncated.
 *
 * @param kind what kind of fault it is
 * @param exception the exception's class name
 * @param message the exception's message, or the empty string
 * @param signature the fault's signature, which counts faults of one cause together: the kind, the
 *     exception's class, the message with its numbers replaced by {@code #}, and the frame that
 *     threw it
 * @param tick the battle's tick counter when it was thrown, or -1 when there was no battle
 */
public record EnvFault(Kind kind, String exception, String message, String signature, int tick) {

  /** What kind of fault it is. */
  public enum Kind {
    /**
     * The battle core refused a behaviour it does not model: an {@link
     * UnsupportedOperationException} from the core, outside a giving. A coverage gap, not a bug.
     */
    REFUSAL(Schema.FAULT_REFUSAL),
    /** Any other exception from the battle core: a broken invariant inside it. */
    BUG(Schema.FAULT_BUG),
    /** An exception inside the environment's own code. */
    ENV_BUG(Schema.FAULT_ENV_BUG);

    private final int code;

    Kind(int code) {
      this.code = code;
    }

    /** The kind's number in the info record's {@link Schema#I_FAULT}. */
    public int code() {
      return code;
    }
  }

  /** The longest message a signature keeps. */
  private static final int SIGNATURE_MESSAGE = 240;

  /**
   * The fault an exception caught at the boundary makes: a {@link CoreFailure} is the battle core's
   * (a refusal or a bug, by its cause), any other exception the environment's own.
   *
   * @param thrown the exception caught
   * @param tick the battle's tick counter, or -1
   */
  public static EnvFault of(Throwable thrown, int tick) {
    Kind kind;
    Throwable cause = thrown;
    if (thrown instanceof CoreFailure failure) {
      cause = failure.getCause();
      kind = cause instanceof UnsupportedOperationException ? Kind.REFUSAL : Kind.BUG;
    } else {
      kind = Kind.ENV_BUG;
    }
    String message = cause.getMessage() == null ? "" : cause.getMessage();
    String exception = cause.getClass().getName();
    return new EnvFault(kind, exception, message, signature(kind, cause, message), tick);
  }

  private static String signature(Kind kind, Throwable cause, String message) {
    String shape = message.replaceAll("-?\\d+", "#");
    if (shape.length() > SIGNATURE_MESSAGE) {
      shape = shape.substring(0, SIGNATURE_MESSAGE);
    }
    // The first frame of the project's own code that is not an argument check: the throwing site.
    String frame = "?";
    for (StackTraceElement element : cause.getStackTrace()) {
      if (frame.equals("?")) {
        frame = element.getClassName() + "." + element.getMethodName();
      }
      if (element.getClassName().startsWith("org.crforge.")
          && !element.getClassName().endsWith(".ValidationUtils")) {
        frame = element.getClassName() + "." + element.getMethodName();
        break;
      }
    }
    return kind.name().toLowerCase(Locale.ROOT)
        + " "
        + cause.getClass().getSimpleName()
        + ": "
        + shape
        + " at "
        + frame;
  }

  /**
   * An exception the battle core threw inside an environment, wrapped so the boundary tells it from
   * the environment's own.
   */
  public static final class CoreFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Wraps an exception of the battle core.
     *
     * @param cause the core's exception
     */
    public CoreFailure(Throwable cause) {
      super(cause);
    }
  }
}
