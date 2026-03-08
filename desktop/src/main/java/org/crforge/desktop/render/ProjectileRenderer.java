package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.projectile.Projectile;

/**
 * Renders active projectiles. Regular projectiles as small yellow circles,
 * chain lightning projectiles as electric blue lines jumping between targets.
 */
public class ProjectileRenderer {

  private final RenderContext ctx;

  public ProjectileRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  public void render(GameState state) {
    // Pass 1: Regular projectiles as circles
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_PROJECTILE);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive() || projectile.getChainOrigin() != null
          || projectile.getChainedHitCount() > 0) {
        continue;
      }

      float x = projectile.getPosition().getX() * TILE_PIXELS;
      float y = projectile.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      ctx.getShapeRenderer().circle(x, y, PROJECTILE_RADIUS);
    }

    ctx.getShapeRenderer().end();

    // Pass 2: Chain lightning as lines
    ctx.getShapeRenderer().begin(ShapeType.Line);
    ctx.getShapeRenderer().setColor(COLOR_CHAIN_LIGHTNING);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive()) {
        continue;
      }

      float toX = projectile.getPosition().getX() * TILE_PIXELS;
      float toY = projectile.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      if (projectile.getChainOrigin() != null) {
        // Chain sub-projectile: line from chain origin entity to current position
        float fromX = projectile.getChainOrigin().getPosition().getX() * TILE_PIXELS;
        float fromY = projectile.getChainOrigin().getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
        ctx.getShapeRenderer().line(fromX, fromY, toX, toY);
      } else if (projectile.getChainedHitCount() > 0) {
        // Primary chain projectile: line from origin to current position
        float fromX = projectile.getOriginX() * TILE_PIXELS;
        float fromY = projectile.getOriginY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
        ctx.getShapeRenderer().line(fromX, fromY, toX, toY);
      }
    }

    ctx.getShapeRenderer().end();
  }
}
