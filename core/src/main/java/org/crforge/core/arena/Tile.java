package org.crforge.core.arena;

public record Tile(int x, int y, TileType type) {

  public boolean isWalkable() {
    return type != TileType.RIVER && type != TileType.BANNED;
  }

  public boolean isPlaceable() {
    return type == TileType.GROUND || type == TileType.BLUE_ZONE || type == TileType.RED_ZONE;
  }

  public boolean isBridge() {
    return type == TileType.BRIDGE;
  }
}
