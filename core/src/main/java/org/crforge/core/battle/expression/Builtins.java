package org.crforge.core.battle.expression;

import java.util.Locale;
import java.util.Map;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The functions every expression may call whatever its environment: {@code min}, {@code max},
 * {@code abs}, {@code clamp}, {@code select} and the two logarithms times 10000. Each takes a fixed
 * number of arguments, and their names are matched without regard to case.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the seven names, ids and argument counts, case-insensitive matching, and each"
            + " function's answer on the recorded cases. Not settled: clamp with its bounds the"
            + " wrong way round, and a logarithm of zero or less.")
final class Builtins {

  /** One builtin: its id and the number of arguments it takes. */
  record Builtin(int id, int arguments) {}

  private static final Map<String, Builtin> BY_NAME =
      Map.of(
          "min", new Builtin(1, 2),
          "max", new Builtin(2, 2),
          "abs", new Builtin(3, 1),
          "clamp", new Builtin(4, 3),
          "select", new Builtin(5, 3),
          "logx10000", new Builtin(6, 1),
          "log10x10000", new Builtin(7, 1));

  private Builtins() {
    // Utility class
  }

  /** The builtin a symbol names, or null. */
  static Builtin resolve(String name) {
    return BY_NAME.get(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Calls a builtin.
   *
   * @param id the builtin's id
   * @param a its arguments, in the order they were written
   */
  static int call(int id, int[] a) {
    return switch (id) {
      case 1 -> Math.min(a[0], a[1]);
      case 2 -> Math.max(a[0], a[1]);
      // The magnitude of the lowest integer is itself, as a 32-bit negate leaves it.
      case 3 -> a[0] < 0 ? -a[0] : a[0];
      case 4 -> Math.min(Math.max(a[0], a[1]), a[2]);
      case 5 -> a[0] != 0 ? a[1] : a[2];
      case 6 -> (int) Math.round(Math.log(a[0]) * 10000);
      case 7 -> (int) Math.round(Math.log10(a[0]) * 10000);
      default -> throw new IllegalArgumentException("no builtin " + id);
    };
  }
}
