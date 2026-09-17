package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.COLOR_GOLDEN_DEVIATION;
import static org.crforge.desktop.render.RenderConstants.COLOR_GOLDEN_MARKER;
import static org.crforge.desktop.render.RenderConstants.COLOR_GOLDEN_PATH;
import static org.crforge.desktop.render.RenderConstants.unitsToPixels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;

/**
 * Draws a golden scenario's reference trajectory over the arena: a ghost polyline through every
 * tick of it, a hollow ring on the position the reference says the unit should hold right now, and
 * a filled cross on the first tick where the live unit was somewhere else.
 */
public class GoldenTrajectoryRenderer {

  /** Radius in pixels of the ring on the current reference position. */
  private static final float MARKER_RADIUS = 7f;

  /** Half the arm length in pixels of the cross on the first deviating tick. */
  private static final float DEVIATION_ARM = 6f;

  private final RenderContext ctx;

  public GoldenTrajectoryRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Draws the ghost trajectory, the current marker and the deviation marker. */
  public void render(GoldenOverlay overlay) {
    if (overlay.isEmpty()) {
      return;
    }
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Line);

    ctx.getShapeRenderer().setColor(COLOR_GOLDEN_PATH);
    List<int[]> path = overlay.path();
    for (int i = 1; i < path.size(); i++) {
      int[] from = path.get(i - 1);
      int[] to = path.get(i);
      ctx.getShapeRenderer()
          .line(
              unitsToPixels(from[0]),
              unitsToPixels(from[1]) + BOTTOM_UI_HEIGHT,
              unitsToPixels(to[0]),
              unitsToPixels(to[1]) + BOTTOM_UI_HEIGHT);
    }

    int[] current = overlay.current();
    if (current != null) {
      ctx.getShapeRenderer().setColor(COLOR_GOLDEN_MARKER);
      ctx.getShapeRenderer()
          .circle(
              unitsToPixels(current[0]),
              unitsToPixels(current[1]) + BOTTOM_UI_HEIGHT,
              MARKER_RADIUS);
    }

    int[] deviation = overlay.deviation();
    if (deviation != null) {
      float x = unitsToPixels(deviation[0]);
      float y = unitsToPixels(deviation[1]) + BOTTOM_UI_HEIGHT;
      ctx.getShapeRenderer().setColor(COLOR_GOLDEN_DEVIATION);
      ctx.getShapeRenderer().line(x - DEVIATION_ARM, y, x + DEVIATION_ARM, y);
      ctx.getShapeRenderer().line(x, y - DEVIATION_ARM, x, y + DEVIATION_ARM);
      ctx.getShapeRenderer().circle(x, y, DEVIATION_ARM);
    }

    ctx.getShapeRenderer().end();
  }
}
