/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.COLOR_MODEL_CHOSEN;
import static org.crforge.desktop.render.RenderConstants.COLOR_MODEL_TILE;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.model.ModelView;

/**
 * The model's tile heatmap: each tile shaded by the model's probability of playing its card there,
 * relative to its most likely tile (the square root, so a spread of small chances still shows), and
 * the tile it played on outlined. The tiles come from {@link ModelView}; every tile is drawn the
 * way up the view has the arena.
 */
public class ModelOverlayRenderer {

  /** The shade of the most likely tile; the others are lighter by their probability. */
  private static final float MOST_ALPHA = 0.6f;

  /** Tiles below this share of the most likely one are left unshaded. */
  private static final float LEAST_SHARE = 0.01f;

  private final RenderContext ctx;

  public ModelOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Draws a model view's heatmap.
   *
   * @param model the view, with its tiles
   * @param view which way up the arena is drawn
   */
  public void render(ModelView model, ViewOrientation view) {
    float most = model.largest();
    if (most <= 0f) {
      return;
    }
    ShapeRenderer shapes = ctx.getShapeRenderer();
    Gdx.gl.glEnable(GL20.GL_BLEND);
    shapes.begin(ShapeType.Filled);
    for (int row = 0; row < ModelView.ROWS; row++) {
      for (int col = 0; col < ModelView.COLS; col++) {
        float share = model.probability(col, row) / most;
        if (share < LEAST_SHARE) {
          continue;
        }
        shapes.setColor(
            COLOR_MODEL_TILE.r,
            COLOR_MODEL_TILE.g,
            COLOR_MODEL_TILE.b,
            MOST_ALPHA * (float) Math.sqrt(share));
        shapes.rect(left(view, col), bottom(view, row), TILE_PIXELS, TILE_PIXELS);
      }
    }
    shapes.end();
    if (model.chosen() >= 0) {
      int col = model.chosen() % ModelView.COLS;
      int row = model.chosen() / ModelView.COLS;
      shapes.begin(ShapeType.Line);
      shapes.setColor(COLOR_MODEL_CHOSEN);
      float left = left(view, col);
      float bottom = bottom(view, row);
      shapes.rect(left, bottom, TILE_PIXELS, TILE_PIXELS);
      shapes.rect(left + 1, bottom + 1, TILE_PIXELS - 2, TILE_PIXELS - 2);
      shapes.end();
    }
  }

  private static float left(ViewOrientation view, int col) {
    return view.left(col * GameUnits.UNITS_PER_TILE, GameUnits.UNITS_PER_TILE);
  }

  private static float bottom(ViewOrientation view, int row) {
    return view.bottom(row * GameUnits.UNITS_PER_TILE, GameUnits.UNITS_PER_TILE);
  }
}
