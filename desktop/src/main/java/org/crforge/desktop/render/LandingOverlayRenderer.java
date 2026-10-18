/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.COLOR_LANDING_EDGE;
import static org.crforge.desktop.render.RenderConstants.COLOR_LANDING_NONE;
import static org.crforge.desktop.render.RenderConstants.COLOR_LANDING_OPEN;
import static org.crforge.desktop.render.RenderConstants.COLOR_LANDING_TARGET;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.battle.LandingTiles;

/**
 * The deploy overlay: shades the tiles the selected card lands on without being moved, outlines the
 * edge of that area, and for a hovered tile a drop is moved off, marks the tile the placement
 * search sends it to, with a line from the hovered tile, or crosses the hovered tile out when the
 * search finds no legal tile. The tiles and the landing come from {@link LandingTiles}; every tile
 * is drawn the way up the view has the arena.
 */
public class LandingOverlayRenderer {

  /** Radius in pixels of the dot on the point a moved drop is sent to. */
  private static final float POINT_RADIUS = 3.5f;

  private final RenderContext ctx;

  public LandingOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Draws the open tiles and, when given, the hovered tile's landing.
   *
   * @param tiles the selected card's tiles, in the battle's columns and rows
   * @param landing where a drop on the hovered tile goes, or null for no tile hovered
   * @param view which way up the arena is drawn
   */
  public void render(LandingTiles.Tiles tiles, LandingTiles.Landing landing, ViewOrientation view) {
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    shapes.setColor(COLOR_LANDING_OPEN);
    for (int row = 0; row < tiles.height(); row++) {
      for (int col = 0; col < tiles.width(); col++) {
        if (tiles.open(col, row)) {
          shapes.rect(left(view, col), bottom(view, row), TILE_PIXELS, TILE_PIXELS);
        }
      }
    }
    shapes.end();

    shapes.begin(ShapeType.Line);
    shapes.setColor(COLOR_LANDING_EDGE);
    for (int row = 0; row < tiles.height(); row++) {
      for (int col = 0; col < tiles.width(); col++) {
        if (!tiles.open(col, row)) {
          continue;
        }
        // Each side the open tile shares with a closed tile, or with the arena's edge.
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
          if (!tiles.open(col + d[0], row + d[1])) {
            edge(shapes, view, col, row, col + d[0], row + d[1]);
          }
        }
      }
    }
    if (landing != null && landing.moved()) {
      renderMove(shapes, view, landing);
    }
    shapes.end();
  }

  /**
   * A hovered tile the drop is moved off: the tile it is sent to outlined, with a line from the
   * hovered tile's centre to the point; or the hovered tile crossed out when no tile is found.
   */
  private static void renderMove(
      ShapeRenderer shapes, ViewOrientation view, LandingTiles.Landing landing) {
    float fromLeft = left(view, landing.tileX());
    float fromBottom = bottom(view, landing.tileY());
    int[] target = landing.targetTile();
    if (target == null) {
      shapes.setColor(COLOR_LANDING_NONE);
      shapes.rect(fromLeft, fromBottom, TILE_PIXELS, TILE_PIXELS);
      shapes.line(fromLeft, fromBottom, fromLeft + TILE_PIXELS, fromBottom + TILE_PIXELS);
      shapes.line(fromLeft, fromBottom + TILE_PIXELS, fromLeft + TILE_PIXELS, fromBottom);
      return;
    }
    shapes.setColor(COLOR_LANDING_TARGET);
    float left = left(view, target[0]);
    float bottom = bottom(view, target[1]);
    shapes.rect(left, bottom, TILE_PIXELS, TILE_PIXELS);
    shapes.rect(left + 1, bottom + 1, TILE_PIXELS - 2, TILE_PIXELS - 2);
    float toX = view.px(landing.point()[0]);
    float toY = view.py(landing.point()[1]);
    shapes.line(
        view.px(GameUnits.tileCenter(landing.tileX())),
        view.py(GameUnits.tileCenter(landing.tileY())),
        toX,
        toY);
    shapes.circle(toX, toY, POINT_RADIUS);
  }

  /** The side a tile shares with a neighbour, drawn as a line. */
  private static void edge(
      ShapeRenderer shapes, ViewOrientation view, int col, int row, int otherCol, int otherRow) {
    float left = left(view, col);
    float bottom = bottom(view, row);
    if (otherRow == row) {
      // A neighbour along the width: the edge is the boundary column between the two as drawn.
      float x = Math.max(left, left(view, otherCol));
      shapes.line(x, bottom, x, bottom + TILE_PIXELS);
    } else {
      float y = Math.max(bottom, bottom(view, otherRow));
      shapes.line(left, y, left + TILE_PIXELS, y);
    }
  }

  private static float left(ViewOrientation view, int col) {
    return view.left(col * GameUnits.UNITS_PER_TILE, GameUnits.UNITS_PER_TILE);
  }

  private static float bottom(ViewOrientation view, int row) {
    return view.bottom(row * GameUnits.UNITS_PER_TILE, GameUnits.UNITS_PER_TILE);
  }
}
