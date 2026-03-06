package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.projectile.Projectile;

/**
 * Renders active projectiles as small yellow circles.
 */
public class ProjectileRenderer {

  private final RenderContext ctx;

  public ProjectileRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  public void render(GameState state) {
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_PROJECTILE);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive()) {
        continue;
      }

      float x = projectile.getPosition().getX() * TILE_PIXELS;
      float y = projectile.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      ctx.getShapeRenderer().circle(x, y, PROJECTILE_RADIUS);
    }

    ctx.getShapeRenderer().end();
  }
}
