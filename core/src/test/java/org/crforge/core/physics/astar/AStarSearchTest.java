package org.crforge.core.physics.astar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AStarSearchTest {

  private AStarSearch search;

  @BeforeEach
  void setUp() {
    search = new AStarSearch(10, 10);
  }

  private PathGrid openGrid(int w, int h) {
    PathGrid grid = new PathGrid(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        grid.setCost(x, y, CostTable.DEFAULT_COST);
      }
    }
    return grid;
  }

  @Test
  void straightLine_openGrid() {
    PathGrid grid = openGrid(10, 10);
    List<int[]> path = search.findPath(grid, 0, 0, 5, 0);

    assertThat(path).isNotEmpty();
    assertThat(path.get(0)).containsExactly(0, 0);
    assertThat(path.get(path.size() - 1)).containsExactly(5, 0);
  }

  @Test
  void diagonal_openGrid() {
    PathGrid grid = openGrid(10, 10);
    List<int[]> path = search.findPath(grid, 0, 0, 3, 3);

    assertThat(path).isNotEmpty();
    assertThat(path.get(0)).containsExactly(0, 0);
    assertThat(path.get(path.size() - 1)).containsExactly(3, 3);
    // Diagonal path should be ~3 steps (direct diagonal)
    assertThat(path).hasSizeLessThanOrEqualTo(5);
  }

  @Test
  void pathAroundWall() {
    PathGrid grid = openGrid(10, 10);
    // Wall from (3,0) to (3,8) -- only gap at (3,9)
    for (int y = 0; y <= 8; y++) {
      grid.setCost(3, y, CostTable.BLOCKED_COST);
    }

    List<int[]> path = search.findPath(grid, 0, 0, 5, 0);

    assertThat(path).isNotEmpty();
    // Path must go around the wall (via y=9 gap)
    assertThat(path.get(path.size() - 1)).containsExactly(5, 0);
    // Verify path doesn't cross the wall
    for (int[] tile : path) {
      if (tile[0] == 3) {
        assertThat(tile[1]).as("Path should not cross wall at x=3, y<9").isGreaterThanOrEqualTo(9);
      }
    }
  }

  @Test
  void unreachableGoal_returnsEmptyPath() {
    PathGrid grid = openGrid(10, 10);
    // Complete wall at x=5
    for (int y = 0; y < 10; y++) {
      grid.setCost(5, y, CostTable.BLOCKED_COST);
    }

    List<int[]> path = search.findPath(grid, 0, 0, 8, 0);
    assertThat(path).isEmpty();
  }

  @Test
  void startEqualsGoal_returnsSingleNode() {
    PathGrid grid = openGrid(10, 10);
    List<int[]> path = search.findPath(grid, 3, 3, 3, 3);

    assertThat(path).hasSize(1);
    assertThat(path.get(0)).containsExactly(3, 3);
  }

  @Test
  void prefersLowerCostTiles() {
    PathGrid grid = openGrid(10, 10);
    // Create a "road" strip at y=5 with lower cost
    for (int x = 0; x < 10; x++) {
      grid.setCost(x, 5, CostTable.ROAD_COST);
    }

    // Path from (0,5) to (9,5) should stay on the road
    List<int[]> path = search.findPath(grid, 0, 5, 9, 5);
    assertThat(path).isNotEmpty();
    // All tiles should be on y=5 (the cheaper road)
    for (int[] tile : path) {
      assertThat(tile[1]).as("Path should prefer low-cost road at y=5").isEqualTo(5);
    }
  }

  @Test
  void diagonalCornerCuttingBlocked() {
    PathGrid grid = openGrid(10, 10);
    // Block (4,5) so diagonal (3,4)->(4,5) requires both (4,4) and (3,5) to be passable.
    // Block (4,4) to prevent the diagonal shortcut; (3,5) stays open so there's a cardinal route.
    grid.setCost(4, 4, CostTable.BLOCKED_COST);

    List<int[]> path = search.findPath(grid, 3, 4, 5, 5);

    assertThat(path).isNotEmpty();
    assertThat(path.get(path.size() - 1)).containsExactly(5, 5);

    // Verify the path does NOT go (3,4) -> (4,5) diagonally (corner-cutting past blocked (4,4))
    for (int i = 0; i < path.size() - 1; i++) {
      int[] cur = path.get(i);
      int[] next = path.get(i + 1);
      if (cur[0] == 3 && cur[1] == 4 && next[0] == 4 && next[1] == 5) {
        org.assertj.core.api.Assertions.fail(
            "Path should not cut diagonal from (3,4) to (4,5) past blocked (4,4)");
      }
    }
  }

  @Test
  void blockedGoal_snapsToNearestPassable() {
    PathGrid grid = openGrid(10, 10);
    grid.setCost(5, 5, CostTable.BLOCKED_COST);

    List<int[]> path = search.findPath(grid, 0, 0, 5, 5);

    // Should find a path to an adjacent passable tile
    assertThat(path).isNotEmpty();
    int[] last = path.get(path.size() - 1);
    // Goal should be an immediate neighbor of (5,5) -- Chebyshev distance 1
    int chebyshev = Math.max(Math.abs(last[0] - 5), Math.abs(last[1] - 5));
    assertThat(chebyshev).as("Snapped goal should be adjacent to original").isEqualTo(1);
  }

  @Test
  void arenaGrid_groundUnitCrossesViaBridge() {
    // Use a real Arena grid
    AStarSearch arenaSearch = new AStarSearch(18, 32);
    org.crforge.core.arena.Arena arena = org.crforge.core.arena.Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, org.crforge.core.entity.base.MovementType.GROUND);

    // Path from blue side (9, 10) to red side (9, 22)
    List<int[]> path = arenaSearch.findPath(grid, 9, 10, 9, 22);

    assertThat(path).isNotEmpty();
    assertThat(path.get(path.size() - 1)).containsExactly(9, 22);

    // Verify path crosses via a bridge (some tile on the path should be a bridge tile)
    boolean crossesBridge =
        path.stream()
            .anyMatch(t -> (t[1] == 15 || t[1] == 16) && arena.getTile(t[0], t[1]).isBridge());
    assertThat(crossesBridge).as("Ground unit should cross river via bridge").isTrue();
  }

  @Test
  void arenaGrid_pathDoesNotCrossRiver() {
    AStarSearch arenaSearch = new AStarSearch(18, 32);
    org.crforge.core.arena.Arena arena = org.crforge.core.arena.Arena.standard();
    PathGrid grid = new PathGrid();
    grid.buildFromArena(arena, org.crforge.core.entity.base.MovementType.GROUND);

    List<int[]> path = arenaSearch.findPath(grid, 9, 10, 9, 22);

    // No tile on the path should be a non-bridge river tile
    boolean walksOnRiver =
        path.stream()
            .anyMatch(t -> (t[1] == 15 || t[1] == 16) && !arena.getTile(t[0], t[1]).isBridge());
    assertThat(walksOnRiver).as("Ground unit should never walk on river").isFalse();
  }
}
