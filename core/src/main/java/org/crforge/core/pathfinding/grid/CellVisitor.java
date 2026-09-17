package org.crforge.core.pathfinding.grid;

/**
 * Told about each cell a scan looks at, in the order the scan looks at it.
 *
 * <p>It exists so a test or a diagnostic can watch a traversal without the scan itself having to
 * build a list; nothing on the routing path needs one.
 */
@FunctionalInterface
public interface CellVisitor {

  /**
   * Called once for each cell the scan reaches.
   *
   * @param col cell column
   * @param row cell row
   */
  void visit(int col, int row);
}
