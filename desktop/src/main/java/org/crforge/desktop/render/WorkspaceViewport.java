/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;

/** Pixel bounds shared by arena rendering and input, excluding the surrounding controls. */
public record WorkspaceViewport(int x, int y, int width, int height) {
  /** The standard arena's width in pixels: 18 tiles. */
  public static final float WORLD_WIDTH =
      TileMap.standard1v1().widthUnits() / GameUnits.UNITS_PER_TILE * RenderConstants.TILE_PIXELS;

  /** The standard arena's length in pixels: 32 tiles. */
  public static final float WORLD_HEIGHT =
      TileMap.standard1v1().heightUnits() / GameUnits.UNITS_PER_TILE * RenderConstants.TILE_PIXELS;

  public static WorkspaceViewport fit(int x, int y, int width, int height) {
    float scale = Math.min(width / WORLD_WIDTH, height / WORLD_HEIGHT);
    int fittedWidth = Math.max(1, Math.round(WORLD_WIDTH * scale));
    int fittedHeight = Math.max(1, Math.round(WORLD_HEIGHT * scale));
    return new WorkspaceViewport(
        x + (width - fittedWidth) / 2, y + (height - fittedHeight) / 2, fittedWidth, fittedHeight);
  }

  public boolean contains(int screenX, int screenY, int screenHeight) {
    int bottomY = screenHeight - 1 - screenY;
    return screenX >= x && screenX < x + width && bottomY >= y && bottomY < y + height;
  }
}
