package org.crforge.core.physics.astar;

/**
 * A* search node representing one tile in the pathfinding grid.
 *
 * <p>Nodes are pre-allocated in a pool (one per tile) and reset between searches to avoid GC
 * pressure. All costs use integer arithmetic per the game's cost formula.
 */
public class PathNode implements Comparable<PathNode> {

  final int tileX;
  final int tileY;

  /** Actual cost from start to this node. */
  int gCost;

  /** Total estimated cost: gCost + heuristic. */
  int fCost;

  /** Parent node for path reconstruction. */
  PathNode parent;

  /** Whether this node has been finalized (closed set). */
  boolean closed;

  /** Whether this node is currently in the open set. */
  boolean open;

  PathNode(int tileX, int tileY) {
    this.tileX = tileX;
    this.tileY = tileY;
    reset();
  }

  void reset() {
    gCost = Integer.MAX_VALUE;
    fCost = Integer.MAX_VALUE;
    parent = null;
    closed = false;
    open = false;
  }

  @Override
  public int compareTo(PathNode other) {
    return Integer.compare(this.fCost, other.fCost);
  }
}
