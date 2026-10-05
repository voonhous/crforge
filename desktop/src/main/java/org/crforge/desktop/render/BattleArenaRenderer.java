package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.pathfinding.grid.TileMap;

/** Arena decoration projected through the same tile map and orientation as gameplay. */
final class BattleArenaRenderer {
  private final RenderContext ctx;

  BattleArenaRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * The arena's cells from the battle's tile map, a tile's checkerboard and the tile grid, each
   * cell where the view draws it.
   */
  void render(TileMap tileMap, ViewOrientation view, boolean drawGrid) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Filled);
    for (int row = 0; row < tileMap.height(); row++) {
      boolean riverRow = riverRow(tileMap, row);
      for (int col = 0; col < tileMap.width(); col++) {
        Color color = ArenaPalette.ground(tileMap.bits(col, row), riverRow);
        boolean water = (tileMap.bits(col, row) & TileMap.WATER_BIT) != 0;
        float shade = !water && ((col / 2) + (row / 2)) % 2 == 0 ? 0.97f : 1f;
        shapes.setColor(color.r * shade, color.g * shade, color.b * shade, 1f);
        shapes.rect(
            view.left(col * TileMap.CELL_UNITS, TileMap.CELL_UNITS),
            view.bottom(row * TileMap.CELL_UNITS, TileMap.CELL_UNITS),
            CELL_PIXELS,
            CELL_PIXELS);
      }
    }
    // Detail stays inside the same tile-map cells; decoration never changes hit testing.
    for (int row = 0; row < tileMap.height(); row++) {
      if (!riverRow(tileMap, row)) continue;
      for (int col = 0; col < tileMap.width(); col++) {
        int bits = tileMap.bits(col, row);
        float x = view.left(col * TileMap.CELL_UNITS, TileMap.CELL_UNITS);
        float y = view.bottom(row * TileMap.CELL_UNITS, TileMap.CELL_UNITS);
        if ((bits & TileMap.WATER_BIT) != 0) {
          shapes.setColor(ArenaPalette.BANK);
          if (row > 0 && !riverRow(tileMap, row - 1))
            shapes.rect(
                x, view.bottom(row * TileMap.CELL_UNITS, 100), CELL_PIXELS, unitsToPixels(100));
          if (row + 1 < tileMap.height() && !riverRow(tileMap, row + 1))
            shapes.rect(
                x,
                view.bottom((row + 1) * TileMap.CELL_UNITS - 100, 100),
                CELL_PIXELS,
                unitsToPixels(100));
          shapes.setColor(ArenaPalette.RIPPLE);
          if ((col + row) % 3 == 0) shapes.rect(x + 2, y + 5, CELL_PIXELS - 5, 1);
        } else if ((bits & TileMap.BLOCKED_BIT) == 0) {
          shapes.setColor(ArenaPalette.PLANK_SEAM);
          shapes.rect(x, y, CELL_PIXELS, 1);
          shapes.rect(x, y + CELL_PIXELS / 2, CELL_PIXELS, 1);
          if (col > 0 && (tileMap.bits(col - 1, row) & TileMap.WATER_BIT) != 0) {
            shapes.setColor(ArenaPalette.RAIL);
            float edge = view.left(col * TileMap.CELL_UNITS, 100);
            shapes.rect(edge, y, unitsToPixels(100), CELL_PIXELS);
          }
          if (col + 1 < tileMap.width() && (tileMap.bits(col + 1, row) & TileMap.WATER_BIT) != 0) {
            shapes.setColor(ArenaPalette.RAIL);
            float edge = view.left((col + 1) * TileMap.CELL_UNITS - 100, 100);
            shapes.rect(edge, y, unitsToPixels(100), CELL_PIXELS);
          }
        }
      }
    }
    // Team identity is an edge accent, leaving the ground quiet behind units and overlays.
    shapes.setColor(ArenaPalette.BLUE);
    shapes.rect(0, BOTTOM_UI_HEIGHT, unitsToPixels(tileMap.widthUnits()), 3);
    shapes.setColor(ArenaPalette.RED);
    shapes.rect(
        0,
        BOTTOM_UI_HEIGHT + unitsToPixels(tileMap.heightUnits()) - 3,
        unitsToPixels(tileMap.widthUnits()),
        3);
    shapes.end();

    if (!drawGrid) return;
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Line);
    shapes.setColor(0.65f, 0.75f, 0.71f, 0.14f);
    float width = unitsToPixels(tileMap.widthUnits());
    float height = unitsToPixels(tileMap.heightUnits());
    for (int x = 0; x <= tileMap.width() / 2; x++) {
      shapes.line(x * TILE_PIXELS, BOTTOM_UI_HEIGHT, x * TILE_PIXELS, BOTTOM_UI_HEIGHT + height);
    }
    for (int y = 0; y <= tileMap.height() / 2; y++) {
      shapes.line(0, BOTTOM_UI_HEIGHT + y * TILE_PIXELS, width, BOTTOM_UI_HEIGHT + y * TILE_PIXELS);
    }
    shapes.end();
  }

  /** Whether a row of cells crosses the river: some cell of it is water. */
  private static boolean riverRow(TileMap tileMap, int row) {
    for (int col = 0; col < tileMap.width(); col++) {
      if ((tileMap.bits(col, row) & TileMap.WATER_BIT) != 0) {
        return true;
      }
    }
    return false;
  }
}
