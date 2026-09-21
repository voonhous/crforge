package org.crforge.core.pathfinding.math;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The two lookup tables the standard game's integer trigonometry reads.
 *
 * <p>Both tables hold rounded samples of ordinary trigonometric functions and are therefore
 * computed here instead of being shipped as data. Each entry is {@link Math#round(double)} (round
 * half up) of the corresponding real value; rounding half up, rather than truncating, is what
 * reproduces the published tables entry for entry.
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
        "The sine and arctangent tables are the published ones, entry for entry; every"
            + " facing and avoidance angle of the five reference walks reads them.")
public final class TrigTables {

  /** Number of entries in the sine table: one per whole degree of a quarter turn, inclusive. */
  public static final int SINE_ENTRIES = 91;

  /** Number of entries in the arc-tangent table: one per ratio step of 1/128, inclusive. */
  public static final int ATAN_ENTRIES = 129;

  /** Scale of a full-amplitude sine value: {@code sine(90) == 1024}. */
  public static final int SINE_SCALE = 1024;

  /** Denominator of the ratio the arc-tangent table is indexed by. */
  public static final int ATAN_RATIO_SCALE = 128;

  private static final int[] SINE = new int[SINE_ENTRIES];
  private static final int[] ATAN = new int[ATAN_ENTRIES];

  static {
    for (int degrees = 0; degrees < SINE_ENTRIES; degrees++) {
      SINE[degrees] = (int) Math.round(SINE_SCALE * Math.sin(Math.toRadians(degrees)));
    }
    for (int ratio = 0; ratio < ATAN_ENTRIES; ratio++) {
      ATAN[ratio] = (int) Math.round(Math.toDegrees(Math.atan(ratio / (double) ATAN_RATIO_SCALE)));
    }
  }

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
