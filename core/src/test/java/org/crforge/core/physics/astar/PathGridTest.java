package org.crforge.core.physics.astar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.player.Team;
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

  @Test
  void lanePreference_matchingBridgeGetsMatchingRoadCost() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();

    // Left-lane preference: left bridge should get MATCHING_ROAD_COST
    grid.buildFromArena(arena, MovementType.GROUND, true, true);

    // Left bridge tile (x=3, y=15) -- matching lane
    assertThat(grid.getCost(3, 15)).isEqualTo(CostTable.MATCHING_ROAD_COST);
    // Right bridge tile (x=13, y=15) -- non-matching, stays ROAD_COST
    assertThat(grid.getCost(13, 15)).isEqualTo(CostTable.ROAD_COST);
  }

  @Test
  void lanePreference_rightLane() {
    Arena arena = Arena.standard();
    PathGrid grid = new PathGrid();

    // Right-lane preference: right bridge should get MATCHING_ROAD_COST
    grid.buildFromArena(arena, MovementType.GROUND, true, false);

    // Right bridge (x=13) -- matching
    assertThat(grid.getCost(13, 15)).isEqualTo(CostTable.MATCHING_ROAD_COST);
    // Left bridge (x=3) -- non-matching
    assertThat(grid.getCost(3, 15)).isEqualTo(CostTable.ROAD_COST);
  }

  @Test
  void applyBuildingOcclusions_blocksTilesUnderBuilding() {
    PathGrid grid = new PathGrid(18, 32);
    Arena arena = Arena.standard();
    grid.buildFromArena(arena, MovementType.GROUND);

    // Place a building at (9, 10) with collisionRadius 1.0
    Building building =
        Building.builder()
            .name("InfernoTower")
            .team(Team.BLUE)
            .position(new Position(9.5f, 10.5f))
            .health(new Health(1000))
            .movement(new Movement(0f, 0f, 1.0f, 1.0f, MovementType.BUILDING))
            .build();

    // Verify the building has the expected collision radius
    assertThat(building.getCollisionRadius()).as("Building collision radius").isEqualTo(1.0f);
    assertThat(building.isAlive()).as("Building is alive").isTrue();

    // Before: tiles around (9,10) are passable ground
    assertThat(grid.isPassable(9, 10)).isTrue();

    grid.applyBuildingOcclusions(List.of((Entity) building));

    // After: tiles within radius 1.0 of (9.5, 10.5) should be blocked
    // Tile (9,10) center is (9.5, 10.5) -- distance 0.0 from building center
    assertThat(grid.getCost(9, 10)).isEqualTo(CostTable.BUILDING_COST);
    // Tile (10,10) center is (10.5, 10.5) -- distance 1.0 from building center (on boundary)
    assertThat(grid.getCost(10, 10)).isEqualTo(CostTable.BUILDING_COST);
    // Tile (9,11) center is (9.5, 11.5) -- distance 1.0 from building center (on boundary)
    assertThat(grid.getCost(9, 11)).isEqualTo(CostTable.BUILDING_COST);

    // Tiles outside the radius should remain passable (>1.0 distance)
    // (7, 10) center is (7.5, 10.5) -- distance 2.0 from building center
    assertThat(grid.getCost(7, 10)).isEqualTo(CostTable.DEFAULT_COST);
  }

  @Test
  void applyBuildingOcclusions_ignoresDeadBuildings() {
    PathGrid grid = new PathGrid(18, 32);
    Arena arena = Arena.standard();
    grid.buildFromArena(arena, MovementType.GROUND);

    Building building =
        Building.builder()
            .name("Tesla")
            .team(Team.BLUE)
            .position(new Position(5.5f, 8.5f))
            .health(new Health(500))
            .movement(new Movement(0f, 0f, 0.8f, 0.8f, MovementType.BUILDING))
            .build();
    // Kill it
    building.getHealth().takeDamage(500);

    grid.applyBuildingOcclusions(List.of(building));

    // Dead building should not block tiles
    assertThat(grid.getCost(5, 8)).isEqualTo(CostTable.DEFAULT_COST);
  }

  @Test
  void applyFriendlyOcclusions_blocksFriendlyTroopTiles() {
    PathGrid grid = new PathGrid(10, 10);
    for (int y = 0; y < 10; y++)
      for (int x = 0; x < 10; x++) grid.setCost(x, y, CostTable.DEFAULT_COST);

    org.crforge.core.entity.unit.Troop friendly =
        org.crforge.core.entity.unit.Troop.builder()
            .name("Friendly")
            .team(Team.BLUE)
            .position(new Position(5.5f, 5.5f))
            .health(new Health(100))
            .build();
    friendly.onSpawn(); // set spawned=true

    grid.applyFriendlyOcclusions(List.of((Entity) friendly), Team.BLUE, 999);

    // Friendly troop at tile (5,5) should raise cost
    assertThat(grid.getCost(5, 5)).isEqualTo(CostTable.BUILDING_COST);
    // Adjacent tiles unchanged
    assertThat(grid.getCost(4, 5)).isEqualTo(CostTable.DEFAULT_COST);
  }

  @Test
  void applyFriendlyOcclusions_ignoresEnemies() {
    PathGrid grid = new PathGrid(10, 10);
    for (int y = 0; y < 10; y++)
      for (int x = 0; x < 10; x++) grid.setCost(x, y, CostTable.DEFAULT_COST);

    org.crforge.core.entity.unit.Troop enemy =
        org.crforge.core.entity.unit.Troop.builder()
            .name("Enemy")
            .team(Team.RED)
            .position(new Position(5.5f, 5.5f))
            .health(new Health(100))
            .build();
    enemy.onSpawn();

    // Pathfinding for BLUE team: RED troops should NOT occlude
    grid.applyFriendlyOcclusions(List.of((Entity) enemy), Team.BLUE, 999);

    assertThat(grid.getCost(5, 5)).isEqualTo(CostTable.DEFAULT_COST);
  }

  @Test
  void applyFriendlyOcclusions_excludesSelf() {
    PathGrid grid = new PathGrid(10, 10);
    for (int y = 0; y < 10; y++)
      for (int x = 0; x < 10; x++) grid.setCost(x, y, CostTable.DEFAULT_COST);

    org.crforge.core.entity.unit.Troop self =
        org.crforge.core.entity.unit.Troop.builder()
            .name("Self")
            .team(Team.BLUE)
            .position(new Position(5.5f, 5.5f))
            .health(new Health(100))
            .build();
    self.onSpawn();

    // The entity should not be occluded by itself
    grid.applyFriendlyOcclusions(List.of((Entity) self), Team.BLUE, self.getId());

    assertThat(grid.getCost(5, 5)).isEqualTo(CostTable.DEFAULT_COST);
  }

  @Test
  void applyBuildingOcclusions_ignoresNonBuildingEntities() {
    PathGrid grid = new PathGrid(18, 32);
    Arena arena = Arena.standard();
    grid.buildFromArena(arena, MovementType.GROUND);

    org.crforge.core.entity.unit.Troop troop =
        org.crforge.core.entity.unit.Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(5.5f, 8.5f))
            .health(new Health(500))
            .build();

    grid.applyBuildingOcclusions(List.<Entity>of(troop));

    // Troop should not block any tiles
    assertThat(grid.getCost(5, 8)).isEqualTo(CostTable.DEFAULT_COST);
  }
}
