package org.crforge.desktop.render;

import org.crforge.core.arena.Arena;

/** Pixel bounds shared by arena rendering and input, excluding the surrounding controls. */
public record WorkspaceViewport(int x, int y, int width, int height) {
  public static final float WORLD_WIDTH = Arena.WIDTH * RenderConstants.TILE_PIXELS;
  public static final float WORLD_HEIGHT = Arena.HEIGHT * RenderConstants.TILE_PIXELS;

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
