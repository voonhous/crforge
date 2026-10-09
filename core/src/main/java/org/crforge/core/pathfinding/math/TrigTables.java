package org.crforge.core.pathfinding.math;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The two lookup tables the standard game's integer trigonometry reads.
 *
 * <p>Both tables hold rounded samples of ordinary trigonometric functions, written out here as
 * integer literals so that no floating-point work runs in the simulation, not even to build them.
 * Each entry is the corresponding real value rounded half up; rounding half up, rather than
 * truncating, is what reproduces the published tables entry for entry. {@code TrigTablesTest}
 * recomputes both tables from those definitions and holds the literals to them.
 *
 * <ul>
 *   <li>{@code SINE} - 91 entries, {@code round(1024 * sin(d degrees))} for {@code d} in 0..90, so
 *       a quarter turn is scaled to 1024.
 *   <li>{@code ATAN} - 129 entries, {@code round(atan(k / 128) in degrees)} for {@code k} in
 *       0..128, so the index is the minor/major ratio of a vector scaled by 128 and the value is a
 *       whole number of degrees between 0 and 45.
 * </ul>
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "The sine and arctangent tables are the published ones, entry for entry; every facing and"
            + " avoidance angle of the walks of the reference battles reads them.")
public final class TrigTables {

  /** Number of entries in the sine table: one per whole degree of a quarter turn, inclusive. */
  public static final int SINE_ENTRIES = 91;

  /** Number of entries in the arc-tangent table: one per ratio step of 1/128, inclusive. */
  public static final int ATAN_ENTRIES = 129;

  /** Scale of a full-amplitude sine value: {@code sine(90) == 1024}. */
  public static final int SINE_SCALE = 1024;

  /** Denominator of the ratio the arc-tangent table is indexed by. */
  public static final int ATAN_RATIO_SCALE = 128;

  /** {@code round(1024 * sin(d degrees))} for {@code d} in 0..90. */
  private static final int[] SINE = {
    0, 18, 36, 54, 71, 89, 107, 125, 143, 160, 178, 195, 213, 230, 248, 265, 282, 299, 316, 333,
    350, 367, 384, 400, 416, 433, 449, 465, 481, 496, 512, 527, 543, 558, 573, 587, 602, 616, 630,
    644, 658, 672, 685, 698, 711, 724, 737, 749, 761, 773, 784, 796, 807, 818, 828, 839, 849, 859,
    868, 878, 887, 896, 904, 912, 920, 928, 935, 943, 949, 956, 962, 968, 974, 979, 984, 989, 994,
    998, 1002, 1005, 1008, 1011, 1014, 1016, 1018, 1020, 1022, 1023, 1023, 1024, 1024
  };

  /** {@code round(atan(k / 128) in degrees)} for {@code k} in 0..128. */
  private static final int[] ATAN = {
    0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 9, 9, 10, 10, 11, 11, 11, 12, 12,
    13, 13, 14, 14, 14, 15, 15, 16, 16, 17, 17, 17, 18, 18, 19, 19, 19, 20, 20, 21, 21, 21, 22, 22,
    22, 23, 23, 24, 24, 24, 25, 25, 25, 26, 26, 27, 27, 27, 28, 28, 28, 29, 29, 29, 30, 30, 30, 31,
    31, 31, 32, 32, 32, 33, 33, 33, 34, 34, 34, 35, 35, 35, 35, 36, 36, 36, 37, 37, 37, 37, 38, 38,
    38, 39, 39, 39, 39, 40, 40, 40, 40, 41, 41, 41, 41, 42, 42, 42, 42, 43, 43, 43, 43, 44, 44, 44,
    44, 45, 45, 45
  };

  private TrigTables() {
    // Utility class
  }

  /**
   * Returns {@code round(1024 * sin(degrees))} for a whole number of degrees between 0 and 90
   * inclusive. Callers outside that quarter turn go through {@link FixedMath#sine1024(int)}.
   */
  public static int sine(int degrees) {
    return SINE[degrees];
  }

  /**
   * Returns the whole number of degrees whose tangent is {@code ratio / 128}, for a ratio index
   * between 0 and 128 inclusive. The result is between 0 and 45.
   */
  public static int atan(int ratio) {
    return ATAN[ratio];
  }

  /** Returns a copy of the sine table, for tests and diagnostics. */
  public static int[] sineTable() {
    return SINE.clone();
  }

  /** Returns a copy of the arc-tangent table, for tests and diagnostics. */
  public static int[] atanTable() {
    return ATAN.clone();
  }
}
