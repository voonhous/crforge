package org.crforge.core.util;

/**
 * Integer game-unit coordinate system used by the simulation.
 *
 * <p>All simulation positions and spatial distances (radii, ranges, offsets, pushback distances)
 * are stored as integer game units. One arena tile is {@link #UNITS_PER_TILE} game units, so the
 * standard 18 by 32 tile arena spans 18,000 by 32,000 game units.
 *
 * <p>The 1,000 units per tile scale is the convention used by the community-decoded game data (e.g.
 * a raw {@code CollisionRadius} of 500 is half a tile). It is a data convention, not a verified
 * claim that every native calculation reproduces bit-exactly once coordinates are integers.
 *
 * <p>Values that are not spatial stay in their natural units: grid indices and tile counts are
 * tiles, time is seconds, damage is hit points, angles are radians, and percentages and multipliers
 * are dimensionless.
 *
 * <p>Conversion policy:
 *
 * <ul>
 *   <li>Tiles to game units rounds to the nearest unit with {@link Math#round(double)} (ties toward
 *       positive infinity). This matches the three-decimal tile rounding the simulator used before
 *       the migration, so tile values with at most three decimals convert exactly.
 *   <li>Game units to a tile index uses floor division, so negative coordinates map to negative
 *       tile indices instead of being truncated toward zero.
 *   <li>Squared distances are computed with {@code long} intermediates. The arena diagonal is about
 *       36,700 units, whose square does not fit exactly in a {@code float}.
 * </ul>
 */
public final class GameUnits {

  /** Game units per arena tile. */
  public static final int UNITS_PER_TILE = 1000;

  /** Half a tile in game units (the offset from a tile's origin to its center). */
  public static final int HALF_TILE = UNITS_PER_TILE / 2;

  /**
   * Raw data speed value that corresponds to one tile per second. The data parser's convention is
   * that a raw speed of 60 (the "medium" movement speed) moves one tile per second.
   */
  public static final float RAW_SPEED_PER_TILE_PER_SECOND = 60.0f;

  private GameUnits() {
    // Utility class
  }

  /**
   * Converts a distance or coordinate in tiles to integer game units, rounding to the nearest unit.
   * Intended for boundaries (data loading, user input, readable test setup) and for spatial
   * constants documented in tiles.
   */
  public static int tiles(double tiles) {
    return round(tiles * UNITS_PER_TILE);
  }

  /** Converts integer game units to tiles for display and external interfaces. */
  public static float toTiles(int units) {
    return units / (float) UNITS_PER_TILE;
  }

  /** Converts a (possibly fractional) game-unit quantity to tiles. */
  public static float toTiles(double units) {
    return (float) (units / UNITS_PER_TILE);
  }

  /** Returns the tile index containing the given game-unit coordinate (floor division). */
  public static int tileIndex(int units) {
    return Math.floorDiv(units, UNITS_PER_TILE);
  }

  /** Returns the game-unit coordinate of a tile's lower edge. */
  public static int tileStart(int tileIndex) {
    return tileIndex * UNITS_PER_TILE;
  }

  /** Returns the game-unit coordinate of a tile's center. */
  public static int tileCenter(int tileIndex) {
    return tileIndex * UNITS_PER_TILE + HALF_TILE;
  }

  /**
   * Rounds a fractional game-unit value to the nearest integer unit (ties toward positive
   * infinity). Used where float geometry (trigonometry, normalization) produces a coordinate.
   */
  public static int round(double units) {
    return Math.toIntExact(Math.round(units));
  }

  /**
   * Converts a raw data speed (60 = one tile per second) to game units per second. The result is
   * fractional for most speeds, e.g. raw 45 = 750 units/s but raw 650 = 10,833.3 units/s, so speeds
   * stay floating point and movement integrates through {@code Position}'s fixed-point carry.
   */
  public static float rawSpeedToUnitsPerSecond(float rawSpeed) {
    return rawSpeed * UNITS_PER_TILE / RAW_SPEED_PER_TILE_PER_SECOND;
  }

  /**
   * Squared Euclidean distance between two game-unit points using long arithmetic. Exact whenever
   * each per-axis separation is at most {@link Integer#MAX_VALUE}, which covers every pair of
   * points within a few million tiles of each other (the arena is 18,000 by 32,000 units).
   */
  public static long distanceSquared(int x1, int y1, int x2, int y2) {
    long dx = (long) x2 - x1;
    long dy = (long) y2 - y1;
    return dx * dx + dy * dy;
  }

  /** Euclidean distance between two game-unit points, as fractional game units. */
  public static double distance(int x1, int y1, int x2, int y2) {
    return Math.sqrt(distanceSquared(x1, y1, x2, y2));
  }

  /**
   * Returns true if a squared distance is within (less than or equal to) the given radius. The
   * comparison is exact integer arithmetic; the boundary itself counts as inside.
   */
  public static boolean withinRadius(long distanceSquared, long radius) {
    return distanceSquared <= radius * radius;
  }

  /** Returns true if a squared distance is strictly inside the given radius. */
  public static boolean insideRadius(long distanceSquared, long radius) {
    return distanceSquared < radius * radius;
  }
}
