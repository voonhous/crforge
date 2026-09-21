package org.crforge.core.pathfinding.grid;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;

/**
 * The six crown towers of a standard 1v1 match, as the routing passes see them.
 *
 * <p>They are created in the order the match places them: for side 0 the king tower and then the
 * two princess towers, then the same three for side 1 with their y mirrored about the arena's
 * length. That order fixes the entity ids, which is what the overlay's per-side hash folds in, so
 * tests that assert a hash depend on it.
 *
 * <p>Positions and radii are integer game units.
 */
final class StandardTowers {

  /** Arena length in game units, used to mirror side 1's towers. */
  private static final int ARENA_LENGTH_UNITS = 64 * TileMap.CELL_UNITS;

  private StandardTowers() {
    // Test fixture holder
  }

  /** The six towers, side 0 first, king before princesses, with ids 1 to 6 in that order. */
  static List<GridEntity> entities() {
    List<GridEntity> towers = new ArrayList<>();
    for (int side = 0; side < 2; side++) {
      towers.add(tower(towers.size() + 1, side, "KingTower", 9000, 3000, 1400, true));
      towers.add(tower(towers.size() + 1, side, "PrincessTowerLeft", 3500, 6500, 1000, false));
      towers.add(tower(towers.size() + 1, side, "PrincessTowerRight", 14500, 6500, 1000, false));
    }
    return towers;
  }

  private static GridEntity tower(
      int id, int side, String name, int x, int y, int radius, boolean king) {
    GridEntity tower = new GridEntity();
    tower.setId(id);
    tower.setName(name + "_" + side);
    tower.setSide(side);
    tower.setX(x);
    tower.setY(side == 0 ? y : ARENA_LENGTH_UNITS - y);
    tower.setCollisionRadius(radius);
    tower.setKing(king);
    tower.setBuilding(true);
    tower.setOccludes(true);
    return tower;
  }
}
