package org.crforge.core.arena;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.player.Team;

@Getter
public class Arena {

  public static final int WIDTH = 18;
  public static final int HEIGHT = 32;
  public static final float TILE_SIZE = 1.0f;

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
   */
  public void freePrincessTowerTiles(float towerX, float towerY, Team team) {
    TileType zoneType = (team == Team.BLUE) ? TileType.BLUE_ZONE : TileType.RED_ZONE;
    int centerTileX = (int) towerX;
    int centerTileY = (int) towerY;
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

  public Tile getTileAt(float worldX, float worldY) {
    int tileX = (int) (worldX / TILE_SIZE);
    int tileY = (int) (worldY / TILE_SIZE);
    return getTile(tileX, tileY);
  }

  public boolean isInBounds(float x, float y) {
    return x >= 0 && x < WIDTH * TILE_SIZE && y >= 0 && y < HEIGHT * TILE_SIZE;
  }

  public boolean isValidPlacement(float x, float y, Team team) {
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
    int tileX = (int) (x / TILE_SIZE);
    int tileY = (int) (y / TILE_SIZE);
    return isInPocket(tileX, tileY, team);
  }

  /**
   * Validates if a building with a given radius can be placed at (x,y). Ensures the entire
   * footprint is within valid tiles for the team.
   */
  public boolean isValidBuildingPlacement(float x, float y, float radius, Team team) {
    float minX = x - radius;
    float maxX = x + radius;
    float minY = y - radius;
    float maxY = y + radius;

    // Check if the bounding box is within arena bounds
    if (!isInBounds(minX, minY) || !isInBounds(maxX, maxY)) {
      return false;
    }

    // Iterate over all tiles covered by this bounding box
    // Using a small epsilon to handle float boundaries safely
    // Example: Range [1.0, 2.0] should cover tile 1. floor(1.0)=1, floor(1.999)=1.
    int tMinX = (int) Math.floor(minX + 0.001f);
    int tMaxX = (int) Math.floor(maxX - 0.001f);
    int tMinY = (int) Math.floor(minY + 0.001f);
    int tMaxY = (int) Math.floor(maxY - 0.001f);

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

  public float getCenterX() {
    return WIDTH * TILE_SIZE / 2f;
  }

  public float getCenterY() {
    return HEIGHT * TILE_SIZE / 2f;
  }

  // Tower positions
  public float getBlueCrownTowerX() {
    return getCenterX();
  }

  public float getBlueCrownTowerY() {
    return 3.0f;
  }

  public float getRedCrownTowerX() {
    return getCenterX();
  }

  public float getRedCrownTowerY() {
    return HEIGHT - 3.0f;
  }

  public float getBlueLeftPrincessTowerX() {
    return LEFT_BRIDGE_X + BRIDGE_WIDTH / 2f;
  }

  public float getBlueLeftPrincessTowerY() {
    return 6.5f;
  }

  public float getBlueRightPrincessTowerX() {
    return RIGHT_BRIDGE_X + BRIDGE_WIDTH / 2f;
  }

  public float getBlueRightPrincessTowerY() {
    return 6.5f;
  }

  public float getRedLeftPrincessTowerX() {
    return LEFT_BRIDGE_X + BRIDGE_WIDTH / 2f; // center of bridge
  }

  public float getRedLeftPrincessTowerY() {
    return HEIGHT - 6.5f;
  }

  public float getRedRightPrincessTowerX() {
    return RIGHT_BRIDGE_X + BRIDGE_WIDTH / 2f; // center of bridge
  }

  public float getRedRightPrincessTowerY() {
    return HEIGHT - 6.5f;
  }
}
