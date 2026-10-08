package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;

/** Renders the HUD's UI backgrounds: the top and bottom panels around the arena. */
public class HudRenderer {

  private final RenderContext ctx;

  public HudRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /** Render the top and bottom UI background panels. */
  public void renderBackgrounds(OrthographicCamera camera) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_UI_BG);

    // Top UI
    ctx.getShapeRenderer()
        .rect(0, camera.viewportHeight - TOP_UI_HEIGHT, camera.viewportWidth, TOP_UI_HEIGHT);

    // Bottom UI
    ctx.getShapeRenderer().rect(0, 0, camera.viewportWidth, BOTTOM_UI_HEIGHT);

    ctx.getShapeRenderer().end();
  }
}
