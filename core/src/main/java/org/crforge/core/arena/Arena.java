package org.crforge.core.arena;

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
  public static final int LEFT_BRIDGE_X = 3;
  public static final int RIGHT_BRIDGE_X = WIDTH - LEFT_BRIDGE_X - BRIDGE_WIDTH; // symmetric with left

  private final Tile[][] tiles;
  private final String name;

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

  private boolean isBridgePosition(int x) {
    return (x >= LEFT_BRIDGE_X && x < LEFT_BRIDGE_X + BRIDGE_WIDTH)
        || (x >= RIGHT_BRIDGE_X && x < RIGHT_BRIDGE_X + BRIDGE_WIDTH);
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

    // Can only place in your own zone
    if (team == Team.BLUE) {
      return tile.type() == TileType.BLUE_ZONE;
    } else {
      return tile.type() == TileType.RED_ZONE;
    }
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
    return 3f;
  }

  public float getRedCrownTowerX() {
    return getCenterX();
  }

  public float getRedCrownTowerY() {
    return HEIGHT - 3f;
  }

  public float getBlueLeftPrincessTowerX() {
    return LEFT_BRIDGE_X + BRIDGE_WIDTH / 2f; // center of bridge
  }

  public float getBlueLeftPrincessTowerY() {
    return 6f;
  }

  public float getBlueRightPrincessTowerX() {
    return RIGHT_BRIDGE_X + BRIDGE_WIDTH / 2f; // center of bridge
  }

  public float getBlueRightPrincessTowerY() {
    return 6f;
  }

  public float getRedLeftPrincessTowerX() {
    return LEFT_BRIDGE_X + BRIDGE_WIDTH / 2f; // center of bridge
  }

  public float getRedLeftPrincessTowerY() {
    return HEIGHT - 6f;
  }

  public float getRedRightPrincessTowerX() {
    return RIGHT_BRIDGE_X + BRIDGE_WIDTH / 2f; // center of bridge
  }

  public float getRedRightPrincessTowerY() {
    return HEIGHT - 6f;
  }
}