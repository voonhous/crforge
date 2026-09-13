package org.crforge.core.util;

/**
 * Integer radial formation helper for multi-unit deploys.
 *
 * <p>Computes one unit's deploy offset in game units from the unit index, group counts, a caller
 * selected radius, an optional line width and an angle shift. All arithmetic is 32-bit integer
 * math: divisions truncate toward zero (Java {@code /} and {@code %} semantics) and trigonometry
 * uses {@link #sine1024(int)}, an integer sine lookup at whole degrees. Floating-point sin/cos are
 * not substitutes; they round differently.
 *
 * <p>Layout summary for a single unit type (no secondary units, width 0):
 *
 * <ul>
 *   <li>1 unit: center.
 *   <li>2 to 6 units: one ring, with a count-specific starting angle.
 *   <li>7 units: a center unit plus a ring of six.
 *   <li>8 or more units: a spiral-like layout whose per-unit radius cycles through {@code (3 *
 *       index) % 7} sixths of an expanded base radius, so some indices (e.g. 0, 7 and 14 of 15)
 *       land on the center.
 * </ul>
 *
 * <p>Mixed primary/secondary groups interleave the two types around the ring; a nonzero width
 * replaces the ring with a two-row line.
 *
 * <p>This helper returns the offset for the blue side only. It deliberately has no side or lane
 * reflection inputs: how those map to teams is unverified, so callers mirror offsets for the red
 * team using the simulator's existing convention (negate both axes). The result is a pre-collision
 * spawn offset; physics separates overlapping units afterwards.
 */
public final class FormationHelper {

  /** Fixed-point scale of {@link #sine1024(int)}: 1024 represents 1.0. */
  public static final int SINE_SCALE = 1024;

  /**
   * sin(degrees) * 1024 rounded to the nearest integer for 0..90 degrees; other angles are folded
   * into this quadrant.
   */
  private static final int[] SINE_TABLE = buildSineTable();

  private FormationHelper() {
    // Utility class
  }

  /**
   * Calculates the deploy offset for one unit.
   *
   * @param index zero-based unit index, primary units first ({@code 0 <= index < primary +
   *     secondary})
   * @param primaryCount number of primary units (at least 1)
   * @param secondaryCount number of secondary units (0 for single-type cards)
   * @param radius caller-selected radius in game units (see {@code DeployFormation} for how cards
   *     choose it); this is an input to the layout, not necessarily the final ring radius
   * @param width line width in game units; nonzero selects the line layouts
   * @param angleShift extra rotation in whole degrees
   * @return blue-side offset in game units
   */
  public static FormationLayout.Offset offset(
      int index, int primaryCount, int secondaryCount, int radius, int width, int angleShift) {
    if (primaryCount < 1
        || secondaryCount < 0
        || index < 0
        || index >= primaryCount + secondaryCount) {
      throw new IllegalArgumentException(
          "Invalid formation index "
              + index
              + " for "
              + primaryCount
              + " primary and "
              + secondaryCount
              + " secondary units");
    }

    // Effective number of angular slots: mixed groups alternate around a ring twice the larger size
    int slots = secondaryCount > 0 ? 2 * Math.max(primaryCount, secondaryCount) : primaryCount;
    boolean pairLayout = width != 0 && secondaryCount == 1;

    // Count-specific starting rotations and the single center unit of the seven-slot layout
    if (slots == 1) {
      return FormationLayout.Offset.ZERO;
    }
    if (slots == 7) {
      if (index == 0) {
        return FormationLayout.Offset.ZERO;
      }
      index -= 1;
      slots = 6;
    } else if (slots == 2) {
      angleShift += 90;
    } else if (slots == 3 || slots == 5) {
      angleShift += 180;
    } else if (slots == 4) {
      angleShift += 45;
    }

    // Expand the radius for rings of three or more (the two-slot layout keeps the input radius).
    // The primary count, not the adjusted slot count, drives the expansion.
    if (slots >= 3 && width == 0) {
      int denominator = sine1024(90 / primaryCount) * 1000 / SINE_SCALE;
      radius = divide(radius * 577, denominator);
    }

    // Large groups: per-unit radius cycles through (3 * index) % 7 sixths of the base radius
    if (slots >= 7) {
      radius = radius * ((3 * index) % 7) / 6;
    }

    // Nonzero width with exactly one secondary unit: primary at the center, secondary offset
    if (pairLayout) {
      if (index == 0) {
        return FormationLayout.Offset.ZERO;
      }
      return new FormationLayout.Offset(width, radius);
    }

    int xAngle;
    int yAngle;
    int yDivisor;
    if (secondaryCount == 0) {
      int angle = 360 * index / slots + angleShift;
      xAngle = angle + 180;
      yAngle = angle + 90;
      yDivisor = SINE_SCALE;
    } else {
      int secondaryPhase = 360 / slots / 2;
      int primaryPhase = secondaryPhase;
      if (primaryCount < secondaryCount) {
        primaryPhase += 180 / secondaryCount / 2;
      } else if (secondaryCount < primaryCount) {
        secondaryPhase += 180 / primaryCount / 2;
      }

      // A singleton group gets a fixed phase and ignores the angle shift
      int shift = primaryCount == 1 ? 0 : angleShift;
      if (primaryCount == 1 && index == 0) {
        primaryPhase = 90;
      }
      if (primaryCount >= 2 && secondaryCount == 1) {
        if (index < primaryCount) {
          primaryPhase = 90 / primaryCount;
        }
        shift = 0;
      }

      if (index >= primaryCount) {
        int secondaryAngle = secondaryPhase + shift + 360 * (index - primaryCount) / slots;
        xAngle = secondaryAngle + 270;
        yAngle = secondaryAngle + 180;
      } else {
        int primaryAngle = primaryPhase + shift + 360 * index / slots;
        xAngle = primaryAngle + 90;
        yAngle = primaryAngle;
      }
      yDivisor = -SINE_SCALE;
    }

    int x = sine1024(xAngle) * radius / SINE_SCALE;
    int y = sine1024(yAngle) * radius / yDivisor;

    // Line layout overwrites the ring: evenly spaced X across the width, alternating Y rows
    if (width != 0) {
      x = divide(index * width, slots - 1) - width / 2;
      y = radius * (index % 2) - radius / 2;
    }

    return new FormationLayout.Offset(x, y);
  }

  /**
   * Integer sine of a whole-degree angle scaled by {@link #SINE_SCALE}. Any integer angle is
   * accepted, including negative angles and angles beyond one turn.
   */
  public static int sine1024(int degrees) {
    int angle = Math.floorMod(degrees, 360);
    int halfTurnAngle = angle >= 180 ? angle - 180 : angle;
    int value = SINE_TABLE[Math.min(halfTurnAngle, 180 - halfTurnAngle)];
    return angle >= 180 ? -value : value;
  }

  /** Signed division truncating toward zero that returns 0 for a zero divisor. */
  private static int divide(int dividend, int divisor) {
    return divisor == 0 ? 0 : dividend / divisor;
  }

  private static int[] buildSineTable() {
    int[] table = new int[91];
    for (int degrees = 0; degrees <= 90; degrees++) {
      table[degrees] = (int) Math.round(SINE_SCALE * Math.sin(Math.toRadians(degrees)));
    }
    return table;
  }
}
