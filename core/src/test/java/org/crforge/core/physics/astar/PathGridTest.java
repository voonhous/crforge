package org.crforge.core.physics.astar;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.arena.Arena;
import org.crforge.core.entity.base.MovementType;
import org.junit.jupiter.api.Test;

class PathGridTest {

  @Test
  void buildFromArena_groundTilesGetDefaultCost() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, MovementType.GROUND);

    // Blue zone tile (e.g. 5, 5) should have DEFAULT_COST
    assertThat(grid.getCost(5, 5)).isEqualTo(CostTable.DEFAULT_COST);
    // Red zone tile
    assertThat(grid.getCost(5, 25)).isEqualTo(CostTable.DEFAULT_COST);
  }

  @Test
  void buildFromArena_bridgeTilesGetRoadCost() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, MovementType.GROUND);

    // Left bridge is at x=[2,4], y=15 and y=16
    assertThat(grid.getCost(3, 15)).isEqualTo(CostTable.ROAD_COST);
    assertThat(grid.getCost(3, 16)).isEqualTo(CostTable.ROAD_COST);
  }

  @Test
  void buildFromArena_riverBlockedForGround() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, MovementType.GROUND);

    // River at y=15 or y=16, outside bridge positions (e.g. x=8)
    assertThat(grid.getCost(8, 15)).isEqualTo(CostTable.BLOCKED_COST);
    assertThat(grid.getCost(8, 16)).isEqualTo(CostTable.BLOCKED_COST);
  }

  @Test
  void buildFromArena_riverPassableForAir() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, MovementType.AIR);

    // River tiles become WATER_COST for air units
    assertThat(grid.getCost(8, 15)).isEqualTo(CostTable.WATER_COST);
    assertThat(grid.getCost(8, 16)).isEqualTo(CostTable.WATER_COST);
  }

  @Test
  void buildFromArena_towerTilesBlocked() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, MovementType.GROUND);

    // Blue crown tower at x=[7-10], y=[1-4]
    assertThat(grid.getCost(8, 2)).isEqualTo(CostTable.BLOCKED_COST);
    // Red crown tower at x=[7-10], y=[27-30]
    assertThat(grid.getCost(8, 28)).isEqualTo(CostTable.BLOCKED_COST);
  }

  @Test
  void buildFromArena_bannedTilesBlocked() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, MovementType.GROUND);

    // Banned: y=0, x<6
    assertThat(grid.getCost(0, 0)).isEqualTo(CostTable.BLOCKED_COST);
    assertThat(grid.getCost(5, 0)).isEqualTo(CostTable.BLOCKED_COST);
  }

  @Test
  void outOfBounds_returnsBlockedCost() {
    PathGrid grid = new PathGrid();
    assertThat(grid.getCost(-1, 0)).isEqualTo(CostTable.BLOCKED_COST);
    assertThat(grid.getCost(18, 0)).isEqualTo(CostTable.BLOCKED_COST);
    assertThat(grid.getCost(0, 32)).isEqualTo(CostTable.BLOCKED_COST);
  }

  @Test
  void isPassable_reflectsCost() {
    PathGrid grid = new PathGrid(5, 5);
    grid.setCost(2, 2, CostTable.DEFAULT_COST);
    grid.setCost(3, 3, CostTable.BLOCKED_COST);

    assertThat(grid.isPassable(2, 2)).isTrue();
    assertThat(grid.isPassable(3, 3)).isFalse();
  }
}
