/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.Color;
import org.crforge.core.pathfinding.grid.TileMap;

/** Presentation colors only; the tile map remains the authority for geometry. */
final class ArenaPalette {
  static final Color GRASS = Color.valueOf("394e46");
  static final Color WATER = Color.valueOf("285267");
  static final Color BANK = Color.valueOf("667465");
  static final Color RIPPLE = Color.valueOf("39677a");
  static final Color BRIDGE = Color.valueOf("917854");
  static final Color PLANK_SEAM = Color.valueOf("65563f");
  static final Color RAIL = Color.valueOf("b09b76");
  static final Color BLOCKED = Color.valueOf("273b36");
  static final Color BLUE = Color.valueOf("70aae0");
  static final Color RED = Color.valueOf("df8582");
  static final Color HEALTH = Color.valueOf("7fc994");

  static Color ground(int bits, boolean riverRow) {
    if ((bits & TileMap.WATER_BIT) != 0) return WATER;
    if ((bits & TileMap.BLOCKED_BIT) != 0) return BLOCKED;
    return riverRow ? BRIDGE : GRASS;
  }

  private ArenaPalette() {}
}
