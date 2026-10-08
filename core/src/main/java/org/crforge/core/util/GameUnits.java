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
 * <p>The battle core works in game units throughout; the tile helpers here serve the visualizer,
 * which draws and picks the arena tile by tile.
 */
public final class GameUnits {

  /** Game units per arena tile. */
  public static final int UNITS_PER_TILE = 1000;

  /** Half a tile in game units (the offset from a tile's origin to its center). */
  public static final int HALF_TILE = UNITS_PER_TILE / 2;

  private GameUnits() {
    // Utility class
  }

  /** Returns the game-unit coordinate of a tile's center. */
  public static int tileCenter(int tileIndex) {
    return tileIndex * UNITS_PER_TILE + HALF_TILE;
  }
}
