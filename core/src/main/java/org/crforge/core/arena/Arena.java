package org.crforge.core.arena;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;

/**
 * Standard arena layout.
 *
 * <p>The tile grid ({@link #WIDTH}, {@link #HEIGHT}, river/bridge/pocket constants) is expressed in
 * tile indices and tile counts. Every method that takes or returns a coordinate uses integer game
 * units ({@link GameUnits}), so the arena spans {@link #WIDTH_UNITS} by {@link #HEIGHT_UNITS}.
 */
@Getter
public class Arena {

  // Grid size in tiles
  public static final int WIDTH = 18;
  public static final int HEIGHT = 32;

  // Arena extent in game units (18,000 by 32,000)
  public static final int WIDTH_UNITS = WIDTH * GameUnits.UNITS_PER_TILE;
  public static final int HEIGHT_UNITS = HEIGHT * GameUnits.UNITS_PER_TILE;

  // River is at the center of the arena
  public static final int RIVER_Y = HEIGHT / 2;

  // Bridge positions (left and right) - each bridge is 3 tiles wide
  public static final int BRIDGE_WIDTH = 3;
  // Changed from 3 to 2 to ensure 2-tile horizontal gap between King and Princess towers
  public static final int LEFT_BRIDGE_X = 2;
  public static final int RIGHT_BRIDGE_X =
      WIDTH - LEFT_BRIDGE_X - BRIDGE_WIDTH; // symmetric with left

  // Pocket depth in tiles past the river when a princess tower is destroyed
  public static final int POCKET_DEPTH = 4;
  // Each lane is half the arena width
  public static final int LANE_WIDTH = WIDTH / 2; // 9

  private final Tile[][] tiles;
  private final String name;
  private final Map<Team, List<int[]>> pocketZones = new EnumMap<>(Team.class);

  public Arena(String name) {
    this.name = name;
    this.tiles = new Tile[WIDTH][HEIGHT];
    initializeTiles();
  }

  public static Arena standard() {
    return new Arena("Standard");
  }

  private void initializeTiles() {
    for (int x = 0; x < WIDTH; x++) {
      for (int y = 0; y < HEIGHT; y++) {
        TileType type = determineTileType(x, y);
        tiles[x][y] = new Tile(x, y, type);
      }
    }
  }

  private TileType determineTileType(int x, int y) {
    // Check for towers first
    if (isTower(x, y)) {
      return TileType.TOWER;
    }

    // Check for banned tiles (edges behind king towers)
    // Based on original logic: rows 0 and 31, columns < 6 or > 11 are banned
    if (y == 0 || y == HEIGHT - 1) {
      if (x < 6 || x > 11) {
        return TileType.BANNED;
      }
    }

    // River tiles (center row)
    if (y == RIVER_Y || y == RIVER_Y - 1) {
      // Check if this is a bridge position
      if (isBridgePosition(x)) {
        return TileType.BRIDGE;
      }
      return TileType.RIVER;
    }

    // Blue zone (bottom half)
    if (y < RIVER_Y - 1) {
      return TileType.BLUE_ZONE;
    }

    // Red zone (top half)
    if (y > RIVER_Y) {
      return TileType.RED_ZONE;
    }

    return TileType.GROUND;
  }

  private boolean isTower(int x, int y) {
    // Crown Towers (4x4)
    // Blue: x[7-10], y[1-4]
    // Red: x[7-10], y[27-30]
    if (x >= 7 && x <= 10) {
      if (y >= 1 && y <= 4) return true;
      if (y >= 27 && y <= 30) return true;
    }

    // Princess Towers (3x3)
    // Blue: y[5-7]
    // Red: y[24-26]
    boolean isBluePrincessY = (y >= 5 && y <= 7);
    boolean isRedPrincessY = (y >= 24 && y <= 26);

    if (isBluePrincessY || isRedPrincessY) {
      // Left: x[2-4]
      if (x >= 2 && x <= 4) return true;
      // Right: x[13-15]
      if (x >= 13 && x <= 15) return true;
    }

    return false;
  }

  private boolean isBridgePosition(int x) {
    return (x >= LEFT_BRIDGE_X && x < LEFT_BRIDGE_X + BRIDGE_WIDTH)
        || (x >= RIGHT_BRIDGE_X && x < RIGHT_BRIDGE_X + BRIDGE_WIDTH);
  }

  /**
   * Converts a destroyed princess tower's 3x3 TOWER tiles back to the owning team's zone type,
   * enabling troop placement on the former tower footprint.
   *
   * @param towerX tower center X in game units
   * @param towerY tower center Y in game units
   */
  public void freePrincessTowerTiles(int towerX, int towerY, Team team) {
    TileType zoneType = (team == Team.BLUE) ? TileType.BLUE_ZONE : TileType.RED_ZONE;
    int centerTileX = GameUnits.tileIndex(towerX);
    int centerTileY = GameUnits.tileIndex(towerY);
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        int tx = centerTileX + dx;
        int ty = centerTileY + dy;
        Tile tile = getTile(tx, ty);
        if (tile != null && tile.type() == TileType.TOWER) {
          tiles[tx][ty] = new Tile(tx, ty, zoneType);
        }
      }
    }
  }

  /**
   * Opens a 9x4 pocket deploy zone for the attacking team on the enemy's side of the river, in the
   * lane of the destroyed princess tower.
   */
  public void openPocketZone(Team attackingTeam, boolean leftLane) {
    int startX = leftLane ? 0 : LANE_WIDTH;
    int endX = leftLane ? LANE_WIDTH - 1 : WIDTH - 1;
    int startY;
    int endY;
    if (attackingTeam == Team.BLUE) {
      // Blue expands into Red territory (first 4 rows past river)
      startY = RIVER_Y + 1; // 17
      endY = RIVER_Y + POCKET_DEPTH; // 20
    } else {
      // Red expands into Blue territory (last 4 rows before river)
      startY = RIVER_Y - 1 - POCKET_DEPTH; // 11
      endY = RIVER_Y - 2; // 14
    }
    pocketZones
        .computeIfAbsent(attackingTeam, k -> new ArrayList<>())
        .add(new int[] {startX, startY, endX, endY});
  }

  /** Checks if a tile position falls within any opened pocket zone for the given team. */
  public boolean isInPocket(int tileX, int tileY, Team team) {
    List<int[]> pockets = pocketZones.get(team);
    if (pockets == null) {
      return false;
    }
    for (int[] p : pockets) {
      if (tileX >= p[0] && tileX <= p[2] && tileY >= p[1] && tileY <= p[3]) {
        return true;
      }
    }
    return false;
  }

  public Tile getTile(int x, int y) {
    if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) {
      return null;
    }
    return tiles[x][y];
  }

  /**
   * Returns the tile containing a game-unit coordinate, or null outside the grid. Uses floor
   * division, so small negative coordinates are outside the arena rather than truncated into tile
   * 0.
   */
  public Tile getTileAt(int x, int y) {
    return getTile(GameUnits.tileIndex(x), GameUnits.tileIndex(y));
  }

  /** Returns true if the game-unit coordinate lies inside the arena (upper edges exclusive). */
  public boolean isInBounds(int x, int y) {
    return x >= 0 && x < WIDTH_UNITS && y >= 0 && y < HEIGHT_UNITS;
  }

  /** Checks deploy-zone validity of a game-unit coordinate for the given team. */
  public boolean isValidPlacement(int x, int y, Team team) {
    Tile tile = getTileAt(x, y);
    if (tile == null) {
      return false;
    }

    // Cannot place on banned or tower tiles
    if (tile.type() == TileType.BANNED || tile.type() == TileType.TOWER) {
      return false;
    }

    TileType required = (team == Team.BLUE) ? TileType.BLUE_ZONE : TileType.RED_ZONE;
    if (tile.type() == required) {
      return true;
    }

    // Check pocket zones (allows deploying on enemy zone tiles in opened pockets)
    return isInPocket(GameUnits.tileIndex(x), GameUnits.tileIndex(y), team);
  }

  /**
   * Validates if a building with a given radius can be placed at (x,y). Ensures the entire
   * footprint is within valid tiles for the team. All arguments are game units.
   */
  public boolean isValidBuildingPlacement(int x, int y, int radius, Team team) {
    int minX = x - radius;
    int maxX = x + radius;
    int minY = y - radius;
    int maxY = y + radius;

    // Check if the bounding box is within arena bounds
    if (!isInBounds(minX, minY) || !isInBounds(maxX, maxY)) {
      return false;
    }

    // Iterate over all tiles covered by this bounding box.
    // A footprint edge that lies exactly on a tile boundary does not cover the neighboring tile,
    // so the bounds are pulled inward by one game unit (the previous 0.001-tile epsilon).
    // Example: range [1000, 2000] covers only tile 1: floor(1001/1000)=1, floor(1999/1000)=1.
    int tMinX = GameUnits.tileIndex(minX + 1);
    int tMaxX = GameUnits.tileIndex(maxX - 1);
    int tMinY = GameUnits.tileIndex(minY + 1);
    int tMaxY = GameUnits.tileIndex(maxY - 1);

    TileType required = (team == Team.BLUE) ? TileType.BLUE_ZONE : TileType.RED_ZONE;
    for (int tx = tMinX; tx <= tMaxX; tx++) {
      for (int ty = tMinY; ty <= tMaxY; ty++) {
        Tile tile = getTile(tx, ty);
        if (tile == null) {
          return false;
        }

        // Must be in the team's valid zone or an opened pocket zone
        if (tile.type() != required && !isInPocket(tx, ty, team)) {
          return false;
        }
      }
    }

    return true;
  }

  // -- Coordinates below are integer game units --

  /** Arena center X (9,000 game units). */
  public int getCenterX() {
    return WIDTH_UNITS / 2;
  }

  /** Arena center Y (16,000 game units). */
  public int getCenterY() {
    return HEIGHT_UNITS / 2;
  }

  // Crown tower center: 3 tiles from the back edge
  public static final int CROWN_TOWER_Y_OFFSET = 3 * GameUnits.UNITS_PER_TILE;
  // Princess tower center: 6.5 tiles from the back edge
  public static final int PRINCESS_TOWER_Y_OFFSET = 6500;
  // Bridge centers, which are also the princess tower X coordinates (3,500 and 14,500)
  public static final int LEFT_BRIDGE_CENTER_X =
      LEFT_BRIDGE_X * GameUnits.UNITS_PER_TILE + BRIDGE_WIDTH * GameUnits.UNITS_PER_TILE / 2;
  public static final int RIGHT_BRIDGE_CENTER_X =
      RIGHT_BRIDGE_X * GameUnits.UNITS_PER_TILE + BRIDGE_WIDTH * GameUnits.UNITS_PER_TILE / 2;

  // Tower positions
  public int getBlueCrownTowerX() {
    return getCenterX();
  }

  public int getBlueCrownTowerY() {
    return CROWN_TOWER_Y_OFFSET;
  }

  public int getRedCrownTowerX() {
    return getCenterX();
  }

  public int getRedCrownTowerY() {
    return HEIGHT_UNITS - CROWN_TOWER_Y_OFFSET;
  }

  public int getBlueLeftPrincessTowerX() {
    return LEFT_BRIDGE_CENTER_X;
  }

  public int getBlueLeftPrincessTowerY() {
    return PRINCESS_TOWER_Y_OFFSET;
  }

  public int getBlueRightPrincessTowerX() {
    return RIGHT_BRIDGE_CENTER_X;
  }

  public int getBlueRightPrincessTowerY() {
    return PRINCESS_TOWER_Y_OFFSET;
  }

  public int getRedLeftPrincessTowerX() {
    return LEFT_BRIDGE_CENTER_X; // center of bridge
  }

  public int getRedLeftPrincessTowerY() {
    return HEIGHT_UNITS - PRINCESS_TOWER_Y_OFFSET;
  }

  public int getRedRightPrincessTowerX() {
    return RIGHT_BRIDGE_CENTER_X; // center of bridge
  }

  public int getRedRightPrincessTowerY() {
    return HEIGHT_UNITS - PRINCESS_TOWER_Y_OFFSET;
  }
}
