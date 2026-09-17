package org.crforge.core.pathfinding.grid;

/**
 * The static cell map of an arena: one flag word per 500-unit routing cell.
 *
 * <p>Cells are addressed by column (x, to the right) and row (y, counted from the top of the map as
 * the data lists it) and are stored row-major, so a cell's id is {@code row * width + column}. This
 * is the same id space routes use.
 *
 * <p>Only the low seven bits of each published value take part in routing, and {@link #bits(int,
 * int)} returns exactly those:
 *
 * <ul>
 *   <li>bits 0-1: road id (lane), 0 for none
 *   <li>bit 4: not placeable (arena corners and the king tower footprint)
 *   <li>bit 5: water
 *   <li>bit 6: blocked (unused on the standard map)
 * </ul>
 *
 * <p>Higher bits exist in the published data for a few cells but no routing rule reads them, so
 * they are masked away at construction and this class never exposes them.
 *
 * <p>Instances are immutable.
 */
public final class TileMap {

  /** Side length of one routing cell in game units. */
  public static final int CELL_UNITS = 500;

  /** Mask of the bits that take part in routing. */
  public static final int ROUTING_BITS_MASK = 0x7f;

  /** Bit mask of the road id (lane) carried by a cell. */
  public static final int ROAD_ID_MASK = 0x3;

  /** Bit set on cells where a card may not be deployed. */
  public static final int NOT_PLACEABLE_BIT = 1 << 4;

  /** Bit set on water cells. */
  public static final int WATER_BIT = 1 << 5;

  /** Bit set on cells that are blocked outright. */
  public static final int BLOCKED_BIT = 1 << 6;

  private static final String STANDARD_1V1_RESOURCE = "/arena/standard_1v1_cells.txt";

  private static volatile TileMap standard1v1;

  private final int width;
  private final int height;
  private final int[] bits;

  /**
   * Creates a tile map from raw published cell values. The values are copied and masked down to
   * {@link #ROUTING_BITS_MASK}, so the caller keeps ownership of its array and higher bits are
   * discarded here rather than at every read.
   *
   * @param width number of columns
   * @param height number of rows
   * @param rawValues {@code width * height} cell values, row-major, rows top to bottom
   */
  public TileMap(int width, int height, int[] rawValues) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException(
          "Tile map must be at least 1x1, got " + width + "x" + height);
    }
    if (rawValues.length != width * height) {
      throw new IllegalArgumentException(
          "Expected " + (width * height) + " cell values, got " + rawValues.length);
    }
    this.width = width;
    this.height = height;
    this.bits = new int[rawValues.length];
    for (int i = 0; i < rawValues.length; i++) {
      this.bits[i] = rawValues[i] & ROUTING_BITS_MASK;
    }
  }

  /**
   * The standard 1v1 arena map, 36 by 64 cells. Loaded once from the bundled resource and shared
   * afterwards; the instance is immutable so sharing it is safe.
   */
  public static TileMap standard1v1() {
    TileMap cached = standard1v1;
    if (cached == null) {
      synchronized (TileMap.class) {
        cached = standard1v1;
        if (cached == null) {
          cached = TileMapLoader.load(STANDARD_1V1_RESOURCE);
          standard1v1 = cached;
        }
      }
    }
    return cached;
  }

  /** Number of columns. */
  public int width() {
    return width;
  }

  /** Number of rows. */
  public int height() {
    return height;
  }

  /** Arena width in game units. */
  public int widthUnits() {
    return width * CELL_UNITS;
  }

  /** Arena height in game units. */
  public int heightUnits() {
    return height * CELL_UNITS;
  }

  /**
   * Returns the routing flag word of a cell: the published value masked to its low seven bits.
   *
   * @throws IndexOutOfBoundsException if the cell is outside the map
   */
  public int bits(int col, int row) {
    if (!contains(col, row)) {
      throw new IndexOutOfBoundsException("Cell (" + col + ", " + row + ") is outside the map");
    }
    return bits[row * width + col];
  }

  /** True when the given column and row address a cell of this map. */
  public boolean contains(int col, int row) {
    return col >= 0 && row >= 0 && col < width && row < height;
  }

  /** Row-major cell id of a column and row, without a bounds check. */
  public int index(int col, int row) {
    return row * width + col;
  }

  /** Column of a row-major cell id. */
  public int col(int index) {
    return index % width;
  }

  /** Row of a row-major cell id. */
  public int row(int index) {
    return index / width;
  }

  /** Road id (lane) of a cell, 0 when the cell carries no road. */
  public int roadId(int col, int row) {
    return bits(col, row) & ROAD_ID_MASK;
  }

  /** True when a cell is water. */
  public boolean isWater(int col, int row) {
    return (bits(col, row) & WATER_BIT) != 0;
  }

  /** True when a cell is marked not placeable. */
  public boolean isNotPlaceable(int col, int row) {
    return (bits(col, row) & NOT_PLACEABLE_BIT) != 0;
  }

  /** True when a cell is marked blocked. */
  public boolean isBlocked(int col, int row) {
    return (bits(col, row) & BLOCKED_BIT) != 0;
  }
}
