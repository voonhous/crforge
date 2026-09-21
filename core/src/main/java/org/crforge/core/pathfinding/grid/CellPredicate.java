package org.crforge.core.pathfinding.grid;

/** A yes/no question about one cell, answered as 1 or 0 by column and row. */
@FunctionalInterface
public interface CellPredicate {

  /**
   * 1 when the cell at the given column and row answers yes, 0 otherwise.
   *
   * @param col cell column
   * @param row cell row
   */
  int test(int col, int row);
}
