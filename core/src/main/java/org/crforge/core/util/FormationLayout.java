package org.crforge.core.util;

import static org.crforge.core.fidelity.FidelityStatus.GUESS;

import org.crforge.core.fidelity.Fidelity;

/**
 * Calculates circular formation offsets for multi-unit deployments. Ported from the Python parser's
 * _calculate_offsets algorithm.
 *
 * <p>Given N units to place around a center point: - N == 1: offset is (0, 0) - N > 1: units are
 * placed in a circle with radius = spawnRadius. Even N starts at angle 0, odd N starts at pi/2
 * (first unit at top).
 *
 * <p>This is the simulator's legacy layout, not a port of the native formation helper. Offsets are
 * integer game units rounded to the nearest unit, which reproduces the previous tile offsets that
 * were rounded to three decimal places (one thousandth of a tile is one game unit).
 */
@Fidelity(
    status = GUESS,
    note =
        "legacy circular layout carried over from the data parser, not the game's own"
            + " algorithm; the 355 summonRadius divisor and the resulting spacing are"
            + " inferred")
public final class FormationLayout {

  /**
   * Empirical divisor that the legacy circular deploy fallback applies to a card's raw summonRadius
   * to obtain a radius in tiles (e.g. Barbarians' raw 700 becomes about 1.97 tiles).
   *
   * <p>This is NOT the coordinate scale: raw spatial data uses 1,000 game units per tile (see
   * {@link GameUnits#UNITS_PER_TILE}), and replacing this divisor with 1,000 is not known to
   * reproduce the native formation algorithm. It is kept only so the fallback's physical layout is
   * unchanged by the integer-unit migration. Cards with explicit formation offsets do not use it.
   */
  public static final float LEGACY_SUMMON_RADIUS_DIVISOR = 355.0f;

  /** A formation offset in integer game units. */
  public record Offset(int x, int y) {

    public static final Offset ZERO = new Offset(0, 0);
  }

  private FormationLayout() {
    // Utility class
  }

  /**
   * Calculates the offset for unit at the given index in a circular formation.
   *
   * @param index zero-based index of the unit being placed
   * @param total total number of units in the formation
   * @param spawnRadius radius from center at which units are placed (in game units)
   * @param collisionRadius collision radius of the unit being placed (unused, reserved)
   * @return offset relative to deploy center, rounded to the nearest game unit
   */
  public static Offset calculateOffset(int index, int total, int spawnRadius, int collisionRadius) {
    return offsetForRadius(index, total, spawnRadius);
  }

  /**
   * Convenience method for troop deploy offsets. Converts the raw summonRadius with the legacy
   * {@link #LEGACY_SUMMON_RADIUS_DIVISOR} (raw / 355 tiles, i.e. raw * 1000 / 355 game units)
   * before calculating.
   *
   * @param index zero-based index of the unit being placed
   * @param total total number of units in the formation
   * @param summonRadius raw summonRadius data value (not game units)
   * @param collisionRadius collision radius of the unit being placed, in game units
   * @return offset relative to deploy center, rounded to the nearest game unit
   */
  public static Offset calculateDeployOffset(
      int index, int total, float summonRadius, int collisionRadius) {
    double radiusUnits =
        (double) summonRadius / LEGACY_SUMMON_RADIUS_DIVISOR * GameUnits.UNITS_PER_TILE;
    return offsetForRadius(index, total, radiusUnits);
  }

  private static Offset offsetForRadius(int index, int total, double radius) {
    if (total <= 1) {
      return Offset.ZERO;
    }

    double startAngle = (total % 2 == 0) ? 0.0 : Math.PI / 2.0;
    double step = 2.0 * Math.PI / total;
    double angle = startAngle + index * step;

    return new Offset(
        GameUnits.round(radius * Math.cos(angle)), GameUnits.round(radius * Math.sin(angle)));
  }
}
