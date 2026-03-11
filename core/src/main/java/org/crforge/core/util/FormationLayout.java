package org.crforge.core.util;

/**
 * Calculates circular formation offsets for multi-unit deployments. Ported from the Python parser's
 * _calculate_offsets algorithm.
 *
 * <p>Given N units to place around a center point: - N == 1: offset is (0, 0) - N > 1: units are
 * placed in a circle with radius = spawnRadius. Even N starts at angle 0, odd N starts at pi/2
 * (first unit at top).
 */
public final class FormationLayout {

  /** Scale factor to convert raw CSV summonRadius values to tile units. */
  public static final float TILE_SCALE = 355.0f;

  private FormationLayout() {
    // Utility class
  }

  /**
   * Calculates the offset for unit at the given index in a circular formation.
   *
   * @param index zero-based index of the unit being placed
   * @param total total number of units in the formation
   * @param spawnRadius radius from center at which units are placed (in tile units)
   * @param collisionRadius collision radius of the unit being placed (unused, reserved)
   * @return offset vector (relative to deploy center), rounded to 3 decimal places
   */
  public static Vector2 calculateOffset(
      int index, int total, float spawnRadius, float collisionRadius) {
    if (total <= 1) {
      return new Vector2(0, 0);
    }

    float r = spawnRadius;
    float startAngle = (total % 2 == 0) ? 0f : (float) (Math.PI / 2.0);
    float step = (float) (2.0 * Math.PI / total);
    float angle = startAngle + index * step;

    float x = round3(r * (float) Math.cos(angle));
    float y = round3(r * (float) Math.sin(angle));

    return new Vector2(x, y);
  }

  /**
   * Convenience method for troop deploy offsets. Divides the raw summonRadius by TILE_SCALE to
   * convert from CSV units to tile units before calculating.
   *
   * @param index zero-based index of the unit being placed
   * @param total total number of units in the formation
   * @param summonRadius raw CSV summonRadius value (not yet in tile units)
   * @param collisionRadius collision radius of the unit being placed
   * @return offset vector (relative to deploy center), rounded to 3 decimal places
   */
  public static Vector2 calculateDeployOffset(
      int index, int total, float summonRadius, float collisionRadius) {
    return calculateOffset(index, total, summonRadius / TILE_SCALE, collisionRadius);
  }

  /** Rounds a float to 3 decimal places. */
  private static float round3(float value) {
    return Math.round(value * 1000f) / 1000f;
  }
}
