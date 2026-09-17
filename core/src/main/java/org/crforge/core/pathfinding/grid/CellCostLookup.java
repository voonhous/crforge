package org.crforge.core.pathfinding.grid;

/**
 * The routing cost of one cell, addressed by column and row.
 *
 * <p>A lookup answers -1 for a cell the caller must not enter at all, which is how the search
 * wrapper and the route search recognise a cell outside the map. Every other answer is a positive
 * weight.
 */
@FunctionalInterface
public interface CellCostLookup {

  /**
   * Cost of the cell at the given column and row, or -1 when the cell is rejected.
   *
   * @param col cell column
   * @param row cell row
   */
  int cost(int col, int row);
}
