/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

/**
 * The static routing flag word of one cell, addressed by column and row.
 *
 * <p>This is what {@link TileMap#bits(int, int)} answers; taking it as a parameter lets the rules
 * that read tile flags be exercised on a synthetic map as well as on an arena's own.
 */
@FunctionalInterface
public interface TileLookup {

  /**
   * Routing flag word of the cell at the given column and row.
   *
   * @param col cell column
   * @param row cell row
   */
  int bits(int col, int row);
}
