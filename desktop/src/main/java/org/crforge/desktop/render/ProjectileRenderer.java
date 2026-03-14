package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.player.Team;

/**
 * Renders active projectiles. Regular projectiles as small yellow circles, chain lightning
 * projectiles as electric blue lines jumping between targets. Position-targeted AOE projectiles
 * also show a landing zone indicator at the target.
 */
public class ProjectileRenderer {

  private final RenderContext ctx;

  public ProjectileRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  public void render(GameState state) {
    // Pass 0: Landing zone indicators for position-targeted AOE projectiles
    Gdx.gl.glEnable(GL20.GL_BLEND);
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    ctx.getShapeRenderer().begin(ShapeType.Filled);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive() || !projectile.isPositionTargeted() || !projectile.hasAoe()) {
        continue;
      }

      float landX = projectile.getTargetX() * TILE_PIXELS;
      float landY = projectile.getTargetY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
      float zoneRadius = projectile.getAoeRadius() * TILE_PIXELS;

      ctx.getShapeRenderer()
          .setColor(
              projectile.getTeam() == Team.BLUE ? COLOR_BLUE_LANDING_ZONE : COLOR_RED_LANDING_ZONE);
      ctx.getShapeRenderer().circle(landX, landY, zoneRadius, CIRCLE_SEGMENTS);
    }

    ctx.getShapeRenderer().end();

    // Pass 1: Regular projectiles as circles
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    ctx.getShapeRenderer().setColor(COLOR_PROJECTILE);

    for (Projectile projectile : state.getProjectiles()) {
      if (!projectile.isActive()
          || projectile.getChainOrigin() != null
          || projectile.getChainedHitCount() > 0) {
        continue;
      }

      float x = projectile.getPosition().getX() * TILE_PIXELS;
      float y = projectile.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;

      // Piercing projectiles render as oriented rectangles showing their hit area
      if (projectile.isPiercing()) {
        float halfWidth = projectile.getAoeRadius() * TILE_PIXELS;
        float halfDepth = Math.max(halfWidth * 0.3f, PROJECTILE_RADIUS);
        float angle =
            (float)
                Math.toDegrees(
                    Math.atan2(projectile.getPiercingDirY(), projectile.getPiercingDirX()));

        ctx.getShapeRenderer()
            .rect(
                x - halfDepth,
                y - halfWidth, // bottom-left corner
                halfDepth,
                halfWidth, // origin (center of rotation)
                halfDepth * 2,
                halfWidth * 2, // size
                1f,
                1f,
                angle); // rotation
        continue;
      }

      // Scale projectile dot for position-targeted AOE projectiles
      float radius = PROJECTILE_RADIUS;
      if (projectile.isPositionTargeted() && projectile.hasAoe()) {
        radius = Math.min(projectile.getAoeRadius() * TILE_PIXELS * 0.3f, 10f);
        radius = Math.max(radius, PROJECTILE_RADIUS);
      }

      ctx.getShapeRenderer().circle(x, y, radius);
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
        float fromY =
            projectile.getChainOrigin().getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
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
