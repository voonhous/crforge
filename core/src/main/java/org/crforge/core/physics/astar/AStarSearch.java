package org.crforge.core.physics.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Core A* search algorithm operating on a {@link PathGrid}.
 *
 * <p>Uses a pre-allocated node pool (one node per tile) to avoid GC pressure. All cost arithmetic
 * uses integers per the game's cost formula: {@code (distance * tileCost + 50) / 100}.
 *
 * <p>Spec compliance:
 *
 * <ul>
 *   <li>PATHFINDING_REOPEN_CLOSEDNODES = FALSE (closed nodes are never revisited)
 *   <li>PATHFINDING_REFRESH_OPENNODES = TRUE (open nodes get updated g-costs)
 *   <li>PATHFINDING_HEURISTIC_METHOD = 1 (Manhattan distance)
 *   <li>PATHFINDING_DEFAULTHEURISTIC_COST = 5 (heuristic weight)
 *   <li>Max steps = 1000
 * </ul>
 */
public class AStarSearch {

  // 8-directional neighbors: {dx, dy, isDiagonal}
  private static final int[][] NEIGHBORS = {
    {0, 1, 0}, {1, 0, 0}, {0, -1, 0}, {-1, 0, 0}, // cardinal
    {1, 1, 1}, {1, -1, 1}, {-1, 1, 1}, {-1, -1, 1} // diagonal
  };

  private PathNode[] nodePool;
  private int poolWidth;
  private int poolHeight;

  /** Creates a search instance with a pre-allocated node pool for the given grid dimensions. */
  public AStarSearch(int gridWidth, int gridHeight) {
    this.poolWidth = gridWidth;
    this.poolHeight = gridHeight;
    this.nodePool = new PathNode[gridWidth * gridHeight];
    for (int y = 0; y < gridHeight; y++) {
      for (int x = 0; x < gridWidth; x++) {
        nodePool[y * gridWidth + x] = new PathNode(x, y);
      }
    }
  }

  /** Creates a search instance for the standard 18x32 arena. */
  public AStarSearch() {
    this(18, 32);
  }

  /**
   * Finds a path from (startX, startY) to (goalX, goalY) on the given cost grid.
   *
   * @return ordered list of tile coordinates [x, y] from start to goal (inclusive), or empty list
   *     if no path found within {@link CostTable#MAX_STEPS} iterations.
   */
  public List<int[]> findPath(PathGrid grid, int startX, int startY, int goalX, int goalY) {
    // Clamp to grid bounds
    startX = clamp(startX, 0, grid.getWidth() - 1);
    startY = clamp(startY, 0, grid.getHeight() - 1);
    goalX = clamp(goalX, 0, grid.getWidth() - 1);
    goalY = clamp(goalY, 0, grid.getHeight() - 1);

    // Trivial case: already at goal
    if (startX == goalX && startY == goalY) {
      return List.of(new int[] {goalX, goalY});
    }

    // If goal tile is blocked, snap to nearest passable neighbor
    if (!grid.isPassable(goalX, goalY)) {
      int[] adjusted = findNearestPassable(grid, goalX, goalY);
      if (adjusted == null) {
        return Collections.emptyList();
      }
      goalX = adjusted[0];
      goalY = adjusted[1];
    }

    resetPool();

    PriorityQueue<PathNode> openList = new PriorityQueue<>();

    PathNode startNode = getNode(startX, startY);
    startNode.gCost = 0;
    startNode.fCost = heuristic(startX, startY, goalX, goalY);
    startNode.open = true;
    openList.add(startNode);

    int steps = 0;
    final int gx = goalX;
    final int gy = goalY;

    while (!openList.isEmpty() && steps < CostTable.MAX_STEPS) {
      steps++;
      PathNode current = openList.poll();

      // Skip stale entries (node was re-added with a better cost)
      if (current.closed) {
        continue;
      }
      current.closed = true;
      current.open = false;

      if (current.tileX == gx && current.tileY == gy) {
        return reconstructPath(current);
      }

      for (int[] dir : NEIGHBORS) {
        int nx = current.tileX + dir[0];
        int ny = current.tileY + dir[1];
        boolean isDiagonal = dir[2] == 1;

        if (!grid.isInBounds(nx, ny) || !grid.isPassable(nx, ny)) {
          continue;
        }

        // Diagonal corner-cutting guard: both adjacent cardinal tiles must be passable
        if (isDiagonal) {
          if (!grid.isPassable(current.tileX + dir[0], current.tileY)
              || !grid.isPassable(current.tileX, current.tileY + dir[1])) {
            continue;
          }
        }

        PathNode neighbor = getNode(nx, ny);
        if (neighbor.closed) {
          continue; // REOPEN_CLOSEDNODES = FALSE
        }

        int distance = isDiagonal ? CostTable.DIAGONAL_DISTANCE : CostTable.CARDINAL_DISTANCE;
        int moveCost = (distance * grid.getCost(nx, ny) + 50) / 100;
        int tentativeG = current.gCost + moveCost;

        if (tentativeG < neighbor.gCost) {
          neighbor.parent = current;
          neighbor.gCost = tentativeG;
          neighbor.fCost = tentativeG + heuristic(nx, ny, gx, gy);

          // REFRESH_OPENNODES = TRUE: re-add with updated cost
          // (stale entries are skipped via the closed check above)
          neighbor.open = true;
          openList.add(neighbor);
        }
      }
    }

    return Collections.emptyList(); // no path found
  }

  private static int heuristic(int x, int y, int goalX, int goalY) {
    // Manhattan distance * heuristic weight
    return (Math.abs(x - goalX) + Math.abs(y - goalY)) * CostTable.HEURISTIC_WEIGHT;
  }

  private List<int[]> reconstructPath(PathNode goalNode) {
    List<int[]> path = new ArrayList<>();
    PathNode current = goalNode;
    while (current != null) {
      path.add(new int[] {current.tileX, current.tileY});
      current = current.parent;
    }
    Collections.reverse(path);
    return path;
  }

  /**
   * Finds the nearest passable tile to (x, y) by scanning a small radius. Returns null if none
   * found within 3 tiles.
   */
  private int[] findNearestPassable(PathGrid grid, int x, int y) {
    for (int r = 1; r <= 3; r++) {
      for (int dy = -r; dy <= r; dy++) {
        for (int dx = -r; dx <= r; dx++) {
          if (Math.abs(dx) != r && Math.abs(dy) != r) {
            continue; // only check the ring at distance r
          }
          int nx = x + dx;
          int ny = y + dy;
          if (grid.isInBounds(nx, ny) && grid.isPassable(nx, ny)) {
            return new int[] {nx, ny};
          }
        }
      }
    }
    return null;
  }

  private PathNode getNode(int x, int y) {
    return nodePool[y * poolWidth + x];
  }

  private void resetPool() {
    for (PathNode node : nodePool) {
      node.reset();
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
